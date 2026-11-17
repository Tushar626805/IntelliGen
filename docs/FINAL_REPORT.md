# IntelliGen - final report (Phases 1-6)

Everything below was produced by reading and changing your uploaded project. **Nothing was run against the real
application**: no Maven build, MySQL, Ollama or browser was available where this was prepared. Verification is described
honestly in section 8.

## 1. What was implemented
- **Security foundation:** secrets removed from source; real server-side login sessions with CSRF protection; role rules on
  every API area; identity taken from the session (never from request parameters); college/branch/ownership checks; password
  hashes and answer keys no longer leak; rate limits on login, registration, password reset and AI jobs; hardened uploads;
  Flask bound to localhost with debug off. BCrypt, role redirects and the reset flow were preserved.
- **Exam integrity:** the server owns the clock (start time and deadline persisted), autosave with sequence numbers, answer
  recovery after refresh or lost connection, flagging, duplicate/late-submit rules, automatic submission of abandoned attempts.
- **AI pipeline:** page-aware chunking over the whole document, difficulty guidance, automatic checks (answer resolution,
  duplicates, option quality, text overlap with the upload), source excerpt and page per question, real job progress with
  cancel, honest "not verified" labelling, and the silent "option A is correct" fallback removed.
- **Review and publishing:** full question editor, drafts that students can never see, edit/publish/delete drafts, publish
  confirmation, teachers/HODs restricted to their own branch.
- **UI:** indigo/slate design system, student dashboard rebuilt on the shared shell (overview, exams, results, charts,
  suggestions from real data), search/filter, notification bell, question bank, CSV export, accessible password toggles.

## 2. Features: complete vs incomplete
| Requested in your brief | Status |
|---|---|
| Login/registration polish, validation, Forgot link | Done (login already had most of it) |
| Student dashboard: welcome, upcoming, results, charts, subject progress | Done |
| Teacher dashboard: create/edit/schedule/publish exams, generate and review AI questions, question bank | Done |
| Exam interface: timer, navigator, flags, autosave, confirmation, recovery, backend enforcement | Done |
| Results: score, %, history, teacher reports | Done; **correct answers/explanations for students: not built** (neither is stored) |
| Password reset (generic reply, expiry, single use, BCrypt) | Preserved; rate limiting and non-revealing timing added |
| AI wizard with drag-and-drop, progress, validation | Done |
| Interactive analytics with subject/exam/date filters | **Partial**: filters on student results and staff exam lists; no filters inside the teacher analytics chart |
| Notifications (scheduled exams, results, processing) | Done, in-app only; no reminders or email |
| Adaptive practice recommendations | **Partial**: subject-level hints from real results; no practice content exists |
| Export reports | CSV of results and the existing PDF archive |
| Responsive layout, keyboard focus, reduced motion | Shared shell and styles are responsive; **not visually verified** |

## 3. Files created, modified, removed (versus your original upload)
**Removed:** none

**Modified (33):**
- `FlaskProject/app.py`
- `FlaskProject/requirements.txt`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`

**Created (41 files, plus 78 small test stand-ins under `docs/verification/java/stubs/`):**
- `.gitignore`
- `docs/PHASE_GUIDE.md`
- `docs/verification/AttemptLogicTest.java`
- `docs/verification/README.md`
- `docs/verification/java/AttemptLogicTest.java`
- `docs/verification/java/DraftFlowTest.java`
- `docs/verification/java/NotifyTest.java`
- `docs/verification/java/com/example/intern/service/AiLogicTest.java`
- `docs/verification/run_all_tests.sh`
- `docs/verification/test_ai_review.js`
- `docs/verification/test_ai_review_dom.js`
- `docs/verification/test_flask_pipeline.py`
- `docs/verification/test_phase6.js`
- `docs/verification/test_student_page.js`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`
- `../springboot_backend/src/src`

## 4. Dependencies and database changes
- **No new Maven dependencies.** Python needs only `flask` and `ollama` (requirements.txt was re-encoded to UTF-8; the other
  packages in it belong to the unused `mcq_generator.py`).
- **Tables created automatically (`ddl-auto=update`):** `exam_attempts`, `exam_attempt_answers`, `exam_attempt_flags`,
  `notifications`.
- **Columns added to `questions`:** `ai_generated`, `source_page`, `source_excerpt` (all nullable).
- Nothing existing is altered or dropped. New configuration: `application-local.properties` (secrets), optional
  `AI_SERVICE_URL`, `AI_SERVICE_KEY`/`IGEN_API_KEY`, `SESSION_COOKIE_SECURE`, `IGEN_MODEL`, `IGEN_NUM_CTX`.

## 5. Run on Windows / IntelliJ
See `docs/PHASE_GUIDE.md` sections 1-2 (rotate the old credentials first, create `application-local.properties`, run
`InternApplication`, open `http://localhost:8080/login.html`, start Flask and Ollama in a second terminal).

## 6. Test steps
Per-role checklists are in `docs/PHASE_GUIDE.md` (phases 2-6). Run the automated checks with
`bash docs/verification/run_all_tests.sh`.

## 7. Remaining bugs, limitations and security concerns
**Highest priority**
1. **College membership is not verified.** Tenancy is the free-text college name typed at registration, and students are
   approved automatically. A student who types another college's exact name would be treated as belonging to it. Fixing this
   needs a design decision (college registry, join codes or approval of students); it was not changed because it alters your
   registration rules.
2. **Rotate the credentials** that were in the original `application.properties` (database and Gmail app password).
3. **Start the app and report errors.** The Java was never compiled against Spring Boot 4 / Java 25.

**Other**
- No Content-Security-Policy yet (pages use inline scripts and CDNs); Tailwind runs from the CDN.
- Rate limits, submit locks and AI job ids live in memory, so only one server instance is supported.
- `spring.jpa.open-in-view=true` and `ddl-auto=update` are kept for compatibility; review both before production.
- Set `SESSION_COOKIE_SECURE=true` and use HTTPS before any public deployment. Nothing was deployed or changed in production.
- AI output can still be wrong or ambiguous; the automatic checks reduce but cannot remove that, and the model was not
  evaluated or replaced. Scanned PDFs are not supported.
- `PasswordHashGenerator.java` still contains a sample password: delete it if unused. Existing duplicate roll numbers are not
  repaired. Principals/HODs/teachers whose role changes keep working sessions refreshed per request, but a deleted user's
  data (attempts, notifications) is not cleaned up.

## 8. What was verified, and how
| Check | Result |
|---|---|
| Flask pipeline, job API (scripted fake model) | 22 pass |
| Review module, DOM output, student page, phase-6 helpers (fake DOM) | 44 + 24 + 27 + 34 pass |
| Java business logic (real service/controller classes, fake repositories, stand-ins for Spring) | 30 + 48 + 50 + 27 pass |
| Every page's scripts exist; every API call permitted for its role | 61 references, 0 mismatches |
| Old secrets present anywhere in the package | 0 |

Not verified: Spring/Hibernate wiring and mappings, `SecurityConfig`, PDFBox, real Ollama output, any browser rendering.
