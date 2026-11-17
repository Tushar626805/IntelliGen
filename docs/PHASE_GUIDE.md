# IntelliGen - Phase 2-6 guide (security, UI, exam integrity, AI generation, student dashboard, notifications and tools)

## 1. Before you run anything (one time)
1. **Rotate the credentials that were in the old `application.properties`**: change the MySQL password and
   revoke/regenerate the Gmail app password. Treat the old ones as compromised.
2. In `../springboot_backend/src/src`, copy `application-local.properties.example` to `application-local.properties`
   and fill in your NEW values. That file is git-ignored. (Alternative: set env vars `DB_PASSWORD`,
   `MAIL_USERNAME`, `MAIL_PASSWORD`, optionally `DB_USERNAME` in the IntelliJ Run Configuration.)
3. Make sure MySQL has the database `intelligen_db` (tables are still created by `ddl-auto=update`).

## 2. Run on Windows / IntelliJ
1. IntelliJ: File > Open > select the project folder (the one with `../springboot_backend/pom.xml`); let Maven import finish.
2. Run `InternApplication` (green arrow). Wait for "Started InternApplication".
3. **Open `http://localhost:8080/login.html` in your browser.** Do NOT open the HTML files from disk or via
   IntelliJ's built-in preview port (63342): pages now call the API on the same origin and use a session cookie.
4. AI service (separate terminal):
   ```
   cd FlaskProject
   python -m venv venv
   venv\Scripts\activate
   pip install flask ollama
   ollama pull llama3:8b
   python app.py
   ```
   (`app.py` only needs `flask` and `ollama`; the other packages in requirements.txt belong to the unused
   `mcq_generator.py` pipeline.) Flask now listens on 127.0.0.1 only and debug is off.

## 3. What changed in behaviour (read this)
- **The API now requires login.** Login creates a server session (HttpOnly cookie). Unauthenticated calls get 401;
  wrong-role calls get 403. Users must sign in again after the upgrade.
- Identity is taken from the session. `rollNo`, `studentName`, `username`, `X-College-Name`, `authorizedBranch`
  etc. sent by the browser are ignored (pages still send them; that is harmless).
- Students no longer receive questions/answer keys in exam lists; `/take` still sends questions without answers.
- Password hashes are never serialized. Exam lists no longer contain questions.
- Submitting an exam is rejected when: already submitted (409), outside the time window (+2 min grace),
  wrong college/department, hidden exam, or the account is suspended.
- Saving an exam is rejected when a question has <2 options or its correct answer is not one of the options
  (grading compares option text, so such a question could never be answered correctly).
- New registrations need a password of 8-72 characters, roll number unique per college. Existing accounts unaffected.
- Login: 8 failed attempts per 10 min per user+IP are blocked (429). Password reset: rate-limited, and the e-mail is
  sent asynchronously so timing does not reveal whether an account exists.
- Suspending/deleting a user ends their session on their next request. Role changes apply without re-login.
- Uploads: PDF only (extension + `%PDF-` header), 1-30 questions, filenames sanitized.

## 4. Test checklist
Student: register -> login (lands on student-exam.html) -> exam list loads -> take exam -> submit -> result shows ->
submit the same exam again (expect an error) -> open DevTools and call `/api/exam/all` (expect 403) -> logout ->
press Back and reload a dashboard (expect redirect to login).
Teacher: register (needs Principal approval) -> login -> upload PDF -> edit questions -> publish -> see exam list ->
results (names/roll numbers now appear) -> toggle visibility -> archive/delete with password.
Principal / HOD / Admin: approvals, HOD assignment, promotion, suspend/restore; confirm a principal cannot act on
another college's users (needs two colleges' data).
Password reset: request link (same message for unknown e-mail), use link, reuse link (expect failure).
Security spot checks: `curl http://localhost:8080/api/admin/students` -> 401; log in as student and POST to
`/api/exam/teacher/save` -> 403.

## 5. Phase 3 - exam integrity
**Database:** three NEW tables are created automatically by `ddl-auto=update`: `exam_attempts`,
`exam_attempt_answers`, `exam_attempt_flags`. Nothing existing is altered. (If a student started an exam on the old
version and is mid-sitting during the upgrade, their final submit is refused with "not started"; deploy between exams.)

**How it works**
- Starting an exam is now `POST /api/exam/student/start/{id}`. The server records the start time and fixes the deadline
  (start + duration, never later than the exam end time). Refreshing, reopening or switching device resumes the SAME
  attempt with the SAME deadline: the timer can no longer be reset. (`GET /take/{id}` is resume-only.)
- The browser counts down from the remaining time the server reports and re-syncs after every autosave.
- Autosave: answers and flags are saved ~1 second after each change, plus every 15 s. Saves carry a sequence number so
  an older save arriving late cannot overwrite a newer one. Only answers that match a real option of a real question
  are stored. A status indicator shows Saved / Saving / Retrying / Offline.
- Offline or connection lost: a banner appears, answers are kept in the browser, and are pushed to the server as soon as
  the connection returns (also after a refresh).
- Flagging: each question can be flagged for review; the navigator shows answered / flagged / unanswered.
- Submit: needs a started attempt. Saved answers are merged with the final payload, so nothing typed is lost. A retried
  submit after a lost reply shows "submitted" instead of an error.
- Late rules: up to 2 minutes after the deadline is accepted (network grace). After that, only answers saved BEFORE the
  deadline count. If a student never returns, a background job (every minute) auto-submits their saved answers 5 minutes
  after the deadline. Returning after the deadline also auto-submits and tells the student.
- If a teacher hides an exam while a student is sitting it, that student can still save and submit.
- Question order is shuffled but stable for the whole attempt (same order after a refresh).

**Phase 3 test checklist**
1. Start an exam, answer 2 questions, flag one, press F5: the same questions order, answers, flags and (almost) the same
   remaining time come back.
2. Start an exam, answer a question, close the tab, reopen from the dashboard after a minute: remaining time is lower by
   about a minute, answers restored.
3. DevTools > Network > Offline, change answers, wait, go Online: banner shows then hides, status returns to Saved.
4. Submit, then try Start again: refused. Try `/api/exam/student/autosave/<id>` after submit: 409.
5. Start an exam with a 1-minute duration and leave the tab open past the deadline: it auto-submits.
6. Start, close the browser and do NOT return: about 6 minutes after the deadline the result appears (check logs for
   "AttemptSweeper: auto-submitted").

## 6. Phase 4 - AI question generation and review

**Start the AI service** (separate terminal, optional settings in brackets):
```
cd FlaskProject
venv\Scripts\activate
ollama pull llama3:8b          (once)
python app.py                  [set IGEN_MODEL, IGEN_NUM_CTX, IGEN_API_KEY first if you want]
```
If you set `IGEN_API_KEY` for Flask, set the same value as `AI_SERVICE_KEY` for the Spring app.

**What is new**
- Upload a PDF (click, press Enter, or drag and drop). The page checks type and size (PDF, max 20 MB, 1-30 questions)
  and choose Easy / Medium / Hard. Difficulty only guides the AI; it is not guaranteed.
- Generation is a background job with real progress: upload %, section x of y, questions accepted so far, elapsed time,
  and a Cancel button. Errors say what is wrong (e.g. "The AI model is not reachable. Make sure Ollama is running...").
- The whole document is used: long PDFs are split into sections spread across the document instead of cutting the end
  off (Ollama's own default context of 2048 tokens silently cut long prompts before; it is now set to 4096).
- Each AI draft is checked automatically and DISCARDED (listed with the reason) if: the answer does not match an
  option (nothing is ever guessed), options are duplicated or not exactly 4, it uses "all/none of the above", it
  duplicates another question, or it cannot be matched to the uploaded text. Survivors show a source excerpt and page
  (when the PDF has text pages), a "Text match: strong/partial" badge and warnings (very similar options, answer much
  longer than the others...). These checks are text-overlap heuristics. **They do not verify that a question or its
  answer is correct**, and the page says so. Read every question.
- The old behaviour of silently marking option A as correct when the AI's answer was unclear is gone: such questions
  are highlighted and cannot be saved until you choose the answer.
- Review editor: edit the question, all options, the correct answer (radio button) and marks; delete with Undo; tick
  "I have checked this question" to clear a warning.
- **Drafts:** "Save as draft" stores the exam hidden from students. Drafts appear in your exam list with Edit / Publish /
  Delete. Publishing asks for confirmation and needs subject, semester, start and end time. Students can never see or
  start a draft. A published exam cannot be edited (it may already have attempts).
- Teachers and HODs can only create exams for their own branch (the server enforces it); principals choose the department.
- New nullable columns on `questions` (created automatically): `ai_generated`, `source_page`, `source_excerpt`.

**Phase 4 test checklist**
1. Generate 5 questions from a real text PDF. Watch the progress bar and stage text. Confirm the summary banner appears.
2. Click Cancel in the middle of a generation: it stops and the Flask job ends.
3. Stop Ollama and click Generate: you get the "not reachable" message, not a blank error.
4. Upload a scanned (image-only) PDF: you are told it has no readable text.
5. Ask for more questions than the PDF supports: you see "Only N of M questions passed the automatic checks".
6. Edit a question, change the correct answer, set marks to 2, Save as draft. Reopen it with Edit. Publish it.
7. Log in as a student of that department: the draft is NOT listed; after publishing it is.
8. Try to save with two identical options or no correct answer: saving is blocked with a clear message.
9. The model was NOT changed or replaced. To judge its quality on your own PDFs, compare "generated vs requested" and the
   discarded-drafts list over several uploads.

## 7. Phase 5 - student dashboard and auth polish
- The student portal now uses the same sidebar shell as the staff pages (collapses to a menu button on phones) with three
  views: **Overview**, **My exams**, **My results**.
- **Overview:** welcome, four stats (open now, completed, average, missed), a "Coming up" list ordered by deadline,
  study suggestions, a score-trend chart (with the 40% pass mark) and progress by subject. Charts are plain SVG/CSS
  (no new libraries) and have text alternatives.
- **My exams:** exams already completed or past their deadline are no longer offered (before, they appeared and then
  failed when clicked); future exams show "Starts ..." with a disabled button; missed exams are listed from the real
  `/missed` endpoint.
- **My results:** search by title and filter by subject; clear empty states.
- **Suggestions are computed only from the student's own results** (subjects averaging below 60%, a drop or rise versus
  earlier exams, missed exams, exams closing within 48 hours). They are not AI and say "based only on your results".
  Topic-level suggestions are not possible because questions carry no topic tags.
- Backend: the student's own results now also include `examId` and `subjectName` (additive).
- Register and reset-password pages gained an accessible show/hide password button. Login already had one, plus the
  Forgot-password link, spinner and error alert, so it was left unchanged.
- Not done: no screenshot/visual review of any page (no browser here), and no separate "recent activity" feed.

**Phase 5 test checklist**
1. Log in as a student: the sidebar appears; on a phone-width window it becomes a menu button.
2. With one open exam, one completed and one expired exam in your class: only the open one has a Start button.
3. Complete two exams in different subjects: the chart, subject bars and suggestions fill in; open My results and use
   the search box and subject filter.
4. Register page: the eye button shows/hides the password and works with the keyboard (Tab, Enter/Space).

## 8. Phase 6 - notifications, search/filters, question bank, export
**Database:** one NEW table `notifications` is created automatically. Nothing existing is altered.

- **Notifications (bell in the top bar of every portal, refreshed every minute):**
  - students are told when an exam for their college, department and semester is published (draft saves notify nobody);
  - students get their result the moment a submission is graded, with a separate note if the answers were submitted
    automatically because time ran out;
  - staff are told when an AI generation finishes (useful if they navigated away).
  Each user only ever sees their own (student and staff ids overlap in the database, so the type is stored too and
  tested). "Mark all read", click-to-open, Esc to close, unread count announced to screen readers. Notifications older than
  90 days are deleted nightly. A failure to create a notification never blocks publishing or grading.
- **Search and filter** on the exam lists of the Teacher, HOD and Principal pages (text + Published/Drafts, live count).
  Students can search their results and filter by subject and **date range**.
- **Question bank:** in the exam editor, "Add from question bank" lists questions from exams you created, your branch's
  exams (principals: the whole college), searchable and filterable by subject, de-duplicated. Reused questions go
  through the normal review editor and can be edited before saving.
- **CSV export** of an exam's results (Teacher and Principal results window). Names that start with `=`, `+`, `-` or `@` are
  neutralised so a spreadsheet cannot run them as formulas.

**Phase 6 test checklist**
1. Publish an exam as a teacher; log in as a student of that class: the bell shows 1, the panel names the exam and times.
2. Submit it: a "result available" notification appears. Let another attempt expire: you get the "submitted
   automatically" note after returning.
3. Log in as a student and a teacher with the same numeric id (e.g. both id 1): each sees only their own notifications.
4. Teacher page: type in the search box and switch the status filter; the count updates; drafts only appear under Drafts.
5. Create an exam, click "Add from question bank", select two questions, add: they appear in the editor.
6. Open an exam's results and click "Export CSV"; open the file in Excel/Sheets.

## 9. Known limits / still to do
- Java could NOT be compiled against the real libraries where this was prepared (no Maven/dependencies). Project classes
  were type-checked against hand-written stand-ins and their logic tested (docs/verification), but `SecurityConfig`,
  Spring/Hibernate behaviour (including the new `notifications` table) and PDFBox are unverified until you start the app.
  Please send me any compile/startup error.
- No page was run in a real browser; JavaScript was syntax-checked and tested against fake DOMs, so layout and visual
  quality still need your eyes.
- **Not built:** adaptive practice (there is no practice content, and the system stores neither the answers a student
  chose nor explanations, so only the subject-level hints from Phase 5 exist); results review with correct answers
  and explanations; interactive chart filters in the teacher analytics window; scheduled "exam closing soon" reminders;
  email/push notifications (in-app only).
- The AI quality depends on your Llama model; the checks reduce but cannot remove wrong or ambiguous questions.
- Scanned PDFs (images) are not supported (no OCR). Page numbers appear only for text PDFs.
- Saving a draft needs at least one question on screen (the draft button lives with the question list).
- Multiple server instances are not supported (rate limits, submit locks and AI job ids are in memory).
- `PasswordHashGenerator.java` (default package) contains a sample password: delete it if you do not use it.
- Existing duplicate roll numbers in your database are not repaired automatically.
- Tailwind still loads from the CDN (fine for development, replace with a build step before production).
- Set `SESSION_COOKIE_SECURE=true` and serve over HTTPS before any public deployment.
