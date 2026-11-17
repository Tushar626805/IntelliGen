# IntelliGen: how to run and test it on Windows

Commands are for **Windows PowerShell** (they also work in Command Prompt unless noted).
`<project>` means the folder that contains `springboot_backend`, `FlaskProject` and `docs`.

## 0. What must be installed
| Tool | Needed for | Check with |
|---|---|---|
| JDK 25 (`pom.xml` sets `java.version` 25, Spring Boot 4.0.3) | Spring backend | `java -version` |
| Maven 3.9+ | building the backend | `mvn -v` |
| MySQL 8, database `intelligen_db` | data | `mysql --version` |
| Python 3.10+ | Flask AI service | `python --version` |
| Ollama with model `llama3:8b` | question generation | `ollama list` |
| Node.js 18+ and Python Playwright (optional) | the test suites | `node -v` |

## 1. Database credentials (do this before the first start)
The Spring app imports `application-local.properties` and it **overrides** `application.properties`.
Create `<project>\springboot_backend\application-local.properties` (or in `src\src\main\resources\`):

    spring.datasource.username=YOUR_MYSQL_USER
    spring.datasource.password=YOUR_MYSQL_PASSWORD
    spring.mail.username=your-gmail-address@gmail.com
    spring.mail.password=YOUR_GMAIL_APP_PASSWORD

A template is `src\src\main\resources\application-local.properties.example`. This file is listed in `.gitignore`.
If you have no `D:` drive, add two more lines (the defaults point to `D:/uploads/...`):

    file.upload-dir=C:/IntelliGen/uploads/notes/
    file.result-dir=C:/IntelliGen/uploads/results/

On first start Hibernate (`ddl-auto=update`) adds the new `answers_released_at` column to `exams`. Nothing is dropped.

## 2. Start the AI service (Flask + Ollama)
    ollama pull llama3:8b          # one time, several GB
    ollama list                    # confirm llama3:8b is listed (Ollama normally runs as a background service)

    cd <project>\FlaskProject      # MUST run from this folder: the classifier file is loaded by relative path
    python -m venv .venv
    .venv\Scripts\Activate.ps1     # Command Prompt: .venv\Scripts\activate.bat
    pip install -r requirements.txt
    python -m spacy download en_core_web_sm
    python app.py                  # listens on http://127.0.0.1:5000

The first start downloads NLTK/transformer data and can take a few minutes. Smoke test (new window):

    curl.exe http://127.0.0.1:5000/jobs/none
    # expect {"error":"Job not found or expired.", ...}  (or "Unauthorized." if you set IGEN_API_KEY): either way Flask is up

Optional shared secret between the two services (set BEFORE starting each):

    $env:IGEN_API_KEY = "choose-a-long-random-value"      # Flask window
    $env:AI_SERVICE_KEY = "choose-a-long-random-value"    # Spring window (same value)

## 3. Build and start the Spring backend
    cd <project>\springboot_backend
    mvn clean package
    mvn spring-boot:run            # or: java -jar target\intern-0.0.1-SNAPSHOT.jar

Open **http://localhost:8080/login.html** (served by Spring itself, not an IDE preview).
Port 8080 busy? `netstat -ano | findstr :8080`, then stop that process or set `server.port` in `application-local.properties`.

## 4. Run the checks
Create a folder junction once so the older Node tests find `src\main\...` (no admin rights needed; Command Prompt):

    cd <project>
    mklink /J src springboot_backend\src\src

Then:

    node docs\verification\test_ai_review.js
    node docs\verification\test_ai_review_dom.js
    node docs\verification\test_student_page.js
    node docs\verification\test_phase6.js
    node docs\verification\test_answer_review.js
    node docs\verification\test_answer_release.js
    node docs\verification\test_results_table.js
    node docs\verification\test_theme_contrast.js
    java docs\verification\AnswerReviewPolicyCheck.java
    java docs\verification\SafeErrorsCheck.java
    python docs\verification\test_flask_pipeline.py

Real-browser suite (mocked backend, needs no database):

    pip install playwright
    playwright install chromium
    python docs\verification\browser\test_browser.py

## 5. Manual acceptance checklist (about 10 minutes)
1. Sign in as each role; each lands on its own dashboard. A wrong password shows an error, not a blank page.
2. Teacher: Create Exam, upload a small text-based PDF, Generate Questions; the progress panel moves; review, then Save as Draft.
   Refresh the page while questions are on screen: the browser should warn you.
3. Publish the exam; sign in as a student, take it, submit.
4. Student: My Results shows the score and "Correct answers will be available once your teacher releases them".
5. After the exam end time AND a 2-minute submission grace period have passed, Teacher: View Results, Release Answers, confirm.
   (Before that the button is absent and the panel says when release becomes possible.) The student gets a notification.
6. Student: My Results, Review Answers shows their chosen option and the correct one for every question.
7. Teacher: Hide Answers; the student's Review Answers button disappears after refresh.
8. Check each page at phone width (browser dev tools) and with the keyboard only (Tab, Shift+Tab, Enter, Esc).

## 6. Troubleshooting
| Symptom | Likely cause and fix |
|---|---|
| `Access denied for user` at start | wrong values in `application-local.properties` |
| Generation says the AI service is unavailable | Flask not running, wrong port, or `AI_SERVICE_KEY`/`IGEN_API_KEY` differ |
| Generation fails immediately with a model error | Ollama not running or `llama3:8b` not pulled (`ollama list`) |
| First generation is very slow | the model is loading into memory; later runs are faster. A second teacher waits ("Waiting for another generation") because the local model handles one job at a time |
| "scanned (image-only) PDFs are not supported" | the PDF has no selectable text; use a text-based PDF (limit 20 MB) |
| `mvn` fails with "release version 25 not supported" | the JDK is older than 25; install JDK 25 and set `JAVA_HOME` |
| Release Answers refused | the exam window has not closed yet, or its 2-minute submission grace period is still running (by design) |
