# How this project was verified

Run everything with `bash docs/verification/run_all_tests.sh` (needs python3, node and a JDK).

| Suite | What it proves | What it does NOT prove |
|---|---|---|
| `test_flask_pipeline.py` (22) | AI pipeline logic with a scripted fake model: answer resolution, duplicate / unsupported / malformed rejection, page + source mapping, chunk coverage, top-up, cancel, job API, API key | Quality of the real Llama model's output |
| `test_ai_review.js` / `test_ai_review_dom.js` (44 + 24) | Review module: answer matching never guesses, validation, payload shape, generate/poll/cancel flow, escaping of AI text, editing handlers | Real browser rendering and layout |
| `java/` (26 + 48 + 50) | Real `ExamService`, `AttemptService`, `ExamController` (drafts/publish/replace/authorisation), `HodController` save, validators, AI error mapping, job registry, exam attempts (deadlines, autosave, late submit, sweeper) | Spring wiring, Hibernate mappings, transactions, `SecurityConfig`, PDFBox. These ran against small hand-written stand-ins for Spring/Jakarta (`java/stubs`), so they are only confirmed when the real app runs |

Nothing here replaces starting the real application and clicking through it (see PHASE_GUIDE.md checklists).
