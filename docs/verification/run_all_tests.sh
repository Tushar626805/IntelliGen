#!/usr/bin/env bash
# Runs every verification suite. Needs: python3, node, and a JDK (or JRE with the jdk.compiler module).
# Run from the project root:  bash docs/verification/run_all_tests.sh
set -e
echo "### Flask pipeline";  python3 docs/verification/test_flask_pipeline.py 2>&1 | tail -3
echo "### Review module";   node docs/verification/test_ai_review.js | tail -1; node docs/verification/test_ai_review_dom.js | tail -1
echo "### Student page"; node docs/verification/test_student_page.js | tail -1
echo "### Phase 6 front-end"; node docs/verification/test_phase6.js | tail -1
echo "### Java logic (compiled against small stand-ins for Spring/Jakarta, not the real libraries)"
J="java -m jdk.compiler/com.sun.tools.javac.Main -proc:none -encoding UTF-8"
OUT=$(mktemp -d); SRCROOT=src/main/java
SRC=$(cd $SRCROOT && find com/example/intern/model com/example/intern/repository com/example/intern/security com/example/intern/controller -name "*.java"
      for f in ExamService AttemptService AttemptSweeper ExamValidator UploadValidator SubmissionRejectedException FileStorageService AiServiceException AiJobRegistry MlIntegrationService NotificationService; do echo com/example/intern/service/$f.java; done)
(cd $SRCROOT && $J -d "$OUT" $(find ../../../docs/verification/java/stubs -name "*.java") $SRC)
$J -cp "$OUT" -d "$OUT" docs/verification/java/com/example/intern/service/AiLogicTest.java docs/verification/java/DraftFlowTest.java docs/verification/java/AttemptLogicTest.java docs/verification/java/NotifyTest.java
for t in com.example.intern.service.AiLogicTest DraftFlowTest AttemptLogicTest NotifyTest; do java -cp "$OUT" $t | tail -1; done
