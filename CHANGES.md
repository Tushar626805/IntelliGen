# IntelliGen: cumulative change set (Phases B to H, the Answer Review feature and the final bug hunt)

Overlay these files onto your project root (the folder containing `springboot_backend/` and `docs/`).
`application.properties` is NOT included and was not modified.

## Answer review with teacher-controlled release (new feature)
Students review each question with the option THEY chose next to the CORRECT option, once a teacher releases the answers.
- Who releases: the exam's creator, the HOD of that branch, or the principal (the existing `canManageExam` rule).
  The control is on the results screen of the teacher and principal dashboards ("Student Answer Review" panel:
  Release Answers / Hide Answers, with a confirmation each time).
- When: the server refuses release (HTTP 409) until the exam window has closed AND the 2-minute submission grace period
  (`ExamService.GRACE_SECONDS`, during which a student may still submit) is over, so the key cannot leak to anyone still answering. Releasing is reversible, and releasing twice does not notify twice.
- Students: until release, the student sees their score but not the answers ("Correct answers will be available once your
  teacher releases them"). The review endpoint sends no questions and no key before release, and only to a student who
  submitted that exam. Released students also get an in-app "Answers Released" notification.
- Database: ONE new nullable column `exams.answers_released_at` (added automatically by `ddl-auto=update`; existing exams and
  data untouched and read as "not released"). To roll back, drop the column; nothing else depends on it.
- API (all under the existing role rules: `/api/exam/**` is TEACHER/HOD/PRINCIPAL only, `/api/exam/student/**` students only):
  - `GET  /api/exam/{id}/answer-release`  -> {released, releasedAt, canRelease, examEndsAt, canManage}
  - `PUT  /api/exam/{id}/answer-release`  body {"released": true|false}
  - `GET  /api/exam/student/review/{examId}` (student's own result only)
  - `GET  /api/exam/results/student/me` gains the read-only field `answersReleased`.
- `ExamService.gradeAndRecord` now stores the answers it graded in `exam_attempt_answers` (table already existed), because
  a last-second change after the final autosave used to be lost. Grading logic is unchanged; the comparison moved into
  `AnswerReviewPolicy.sameAnswer` with identical logic (trimmed, case-insensitive).
- Results submitted BEFORE this change may have incomplete stored answers; the screen says so instead of marking them wrong.

## Phase D: shared shell, headings, labels
- layout.js: role label normalised ("Student Portal"), skip link, aria-current on the active tab, menu button state
  (aria-expanded), Escape closes the mobile menu, decorative icons hidden from assistive tech.
- Heading levels fixed (no skipped levels; one visible H1 per view). Empty-state titles are no longer headings;
  dialog titles are H2 (ui.js).
- Forced UPPERCASE removed from form labels, table headings and sub-headings; form labels use the shared `field-label`
  style and are associated with their inputs; unlabelled department fields given accessible names.
- Fixed double-escaping on the HOD/teacher top bar (names such as O'Brien or R&D displayed as HTML entities).
- Placeholder organisation name "Tech Institute" replaced by the neutral "Your College".

## Phase E: results tables and messages (teacher and principal dashboards)
- New shared `results-table.js` replaces two divergent copies of the results-table code.
  - Checks the HTTP status (before, a 403/500 reply caused the message "Error loading results mapping arrays").
  - Friendly error with a Try Again button; no "See console" text any more.
  - Zero-mark exams show a dash instead of "NaN%"; rows are built in one pass (the old loop re-parsed the table per student).
  - Search by name or roll number, sortable columns (keyboard accessible, aria-sort), "Showing x of y students" status line.
  - Horizontal scrolling on small screens. Column headers are now identical on both pages
    (Roll No, Student Name, Score, Percentage; the principal page used "Name" and "%").
- A failing analytics call no longer hides the results; the export button is reset on every open (it could keep the previous exam's data).
- Developer-style messages replaced ("Perfect structural standing", "Error rendering failure blocks", etc.).
- New tests: docs/verification/test_results_table.js (17 checks); browser suite extended to 100 checks.
- Overflow check: 9 pages x 3 widths (1280/768/375) show no horizontal overflow.

## Phase F: AI question generation screens
Audit result: the existing AI workflow was already solid (upload progress, queue/section progress, cancel, retry after network
blips, quality flags, source excerpts with page numbers, required review of flagged questions, publish confirmation that says the
checks are heuristic). Keyboard operation of the upload area was already handled. The Flask service, its endpoints
(`/generate_mcq`, `POST/GET/DELETE /jobs`), the Spring proxy, the model and the job logic are UNCHANGED. Changes are UI only:
- Unsaved-work guard: AI-generated or edited questions now trigger the browser's "leave site?" prompt on refresh, tab close or
  navigation (a generation takes minutes of local-model time). Cleared by Save/Publish/Reset; reopening a saved draft is "clean".
- Slow-run hint: if no visible progress for 3 minutes, a status message explains the model may be busy and suggests
  cancelling and trying fewer questions. (Generation itself is never aborted by the UI.)
- Progress bar has an accessible name; icons hidden from assistive technology.
- Generator form: visible "Number of Questions" and "Difficulty" labels (previously a bare "Qty" placeholder and an unlabelled select),
  helper text with the real limits (PDF only, 20 MB, readable text required, scanned PDFs not supported; all verified in code),
  an honest description (the old text said "IGen ... instantly"), and "Generate Questions" on the button.
- Title Case for labels built in JavaScript that the earlier pass could not reach: Save as Draft, Add from Question Bank,
  Could Not Generate Questions, Publish Exam / Keep Reviewing, Text Match: Strong / Partial, AI-Drafted, Source (Page N),
  Submit Answers, Saving Responses..., Analyzing Department Records...; difficulty badges now read Medium/Hard (were raw lowercase).
- Tests: browser suite now 157 checks (form clarity, queued/progress, success cards, unsaved-work guard, slow hint, cancel,
  model failure, upload rejection); ai-review unit suite 47 checks; one existing expectation updated ("Source (Page 3)").

## Phase G: responsive and accessibility review
Audit found, and this phase fixes:
- Nine modal dialogs (teacher 4, principal 4, HOD 1) had no dialog semantics, no accessible name, no focus movement and no
  keyboard trap. `ui.js` now handles every `.modal-overlay` centrally: panel gets role="dialog", aria-modal and a name from its
  heading; focus moves to the first form field (or first control) on open; Tab/Shift+Tab stay inside; focus returns to the opener
  on close. Pages are unchanged: they still just toggle the `hidden` class. (Escape behaviour is unchanged: it still does not
  close create-exam forms, by design, so typed work is not lost.)
- Secondary text used Tailwind `slate-400` (#94a3b8, 2.6:1 on white) in ~30 places. The theme now maps slate-400 to #5f6f86
  (5.1:1 on white, 4.9:1 on slate-50, 4.7:1 on slate-100). Other slate shades are untouched.
- All table header cells now declare scope="col".
- Notification items show a real focus ring (previously only a faint background change).
- Verified already fine and left alone: toast live region, reduced-motion rules, keyboard-operable upload area, focus rings on
  buttons/fields/links, skip link, aria-current, headings, form labels.
- Tests: browser suite now 192 checks (dialogs, focus, Tab trap, table scope, 320/375/768/1280px reflow on all 9 pages;
  proven to fail against the pre-Phase-G ui.js); new test_theme_contrast.js (8 checks).
- Also fixed a race in the browser suite's cancel test (it passed or failed depending on timing).

## Phase H: run guide and housekeeping
- docs/RUN-GUIDE.md: exact Windows commands for MySQL credentials, Ollama, Flask (must be started from FlaskProject because the
  classifier is loaded by relative path), Spring Boot, every test suite, a 10-minute manual acceptance checklist, troubleshooting.
- .gitignore (project root): keeps application-local.properties, uploads, build output and virtual environments out of source control.

## Login page, second pass (right-hand panel)
- The right half was a small card floating in empty space. It now has: a larger, roomier card (bigger fields and button),
  a top-right "Create an Account" action (moves below the card on phones), a "One Sign-In for Every Role" section listing the five
  roles (information only, not a selector, so nobody can pick their own privileges), a support line, and a very subtle dot grid.
  Built with the stylesheet's own classes (no Tailwind utilities), so it renders the same with or without the CDN.
- If the page is opened from IntelliJ's built-in preview (port 63342) it now says sign-in cannot work there and where to open it
  (normally http://localhost:8080/login.html); a 404 from the login call explains the same thing instead of a bare "status 404".
- The show/hide password button no longer depends on Tailwind's reset for its styling.

## Final bug hunt (fresh review of the finished package)
Found and fixed:
- Answer release could be triggered during the 2-minute submission grace period (a student could read the key, then submit changed
  answers). Release now requires end time + grace. `isReleased` no longer compares clocks (database rounding could hide answers
  for a moment); a stored release time simply means released. The panel shows the real earliest-release time.
- Security/privacy: 26 places in ExamController, HodController and PrincipalController returned `e.getMessage()` straight to the
  browser. For unexpected failures that is a Hibernate/MySQL message (table, column and constraint names), and a null message made
  `Map.of` throw inside the catch block. New `SafeErrors.message(e)` keeps deliberate user-facing messages ("Teacher profile not
  found"), hides technical ones behind a generic message, logs the real error on the server, and never returns null.
  Typed exceptions that carry an HTTP status (SubmissionRejectedException, AiServiceException) are unchanged.
- Exam player: option groups are now `role="radiogroup"` labelled by their question (screen readers previously heard bare options).
- Register page: a non-JSON server error showed "Is the Spring Boot backend running?"; now status-based, plain-language messages
  (same fix as the login page).
- Ten dialog buttons the earlier passes missed are now Title Case (Suspend Access, Revoke Account, Run Promotion, Advance Semesters,
  Start Exam, Keep Working, Delete Draft, Keep It, Not Yet).
Checked and found correct (no change needed): zip integrity, no junk files, consistent line endings; every Java helper signature and
import used by the new code; ExamAttempt column length vs the 1000-character cap; attempt deadlines are already capped at the exam
end time; static cross-reference of every page (assets, links, ids, inline handlers, module members); JSON naming of boolean flags.
Tests added: SafeErrorsCheck.java (14), data-path rendering of all five dashboards with hostile data (proven to catch a re-introduced
double-escape bug), exam player (full exam, failures, retry), register / forgot / reset behaviour. Browser suite: 278 checks.

## Files
Java: pom.xml, SafeErrors.java (new), HodController.java, PrincipalController.java, ExamController.java, ExamService.java, NotificationService.java, ResultRepository.java, Exam.java, AnswerReviewPolicy.java (new).
Static: results-table.js (new), answer-release.js (new), style.css, theme.js, layout.js, ui.js, ai-review.js, list-filter.js, notifications.js, login.html, register.html,
forgot-password.html, reset-password.html, student-exam.html, exam-player.html, admin/hod/principal/teacher dashboards.
Tests: docs/verification/test_answer_review.js, test_answer_release.js, test_results_table.js, test_theme_contrast.js, AnswerReviewPolicyCheck.java, browser/test_browser.py (new);
test_student_page.js (two label expectations updated).

## Run the checks
node docs/verification/test_answer_review.js
node docs/verification/test_results_table.js
node docs/verification/test_answer_release.js
node docs/verification/test_theme_contrast.js
java docs/verification/AnswerReviewPolicyCheck.java
pip install playwright && playwright install chromium && python docs/verification/browser/test_browser.py
(The older Node tests expect `src/main/...`; with your `src/src/main` layout create a symlink or adjust their path.)

## Not verified
Spring/Maven compile and runtime (new Java passes a parse-only syntax check, not a full compile), MySQL, Ollama,
and the final Tailwind-styled appearance (the sandbox has no network for the Tailwind CDN).
