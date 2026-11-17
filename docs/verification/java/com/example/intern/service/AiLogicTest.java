package com.example.intern.service;

import com.example.intern.model.*;
import java.util.*;

/** Pure-logic tests for the AI plumbing and exam validation (same package to reach package-private helpers). */
public class AiLogicTest {
    static int passed = 0, failed = 0;
    static void check(String n, boolean ok) { if (ok) { passed++; System.out.println("  PASS  " + n); } else { failed++; System.out.println("  FAIL  " + n); } }

    static Exam exam(String title, List<Question> qs) { Exam e = new Exam(); e.setTitle(title); e.setQuestions(qs); return e; }
    static Question q(String text, List<String> opts, String ans) { Question x = new Question(); x.setQuestionText(text); x.setOptions(opts); x.setCorrectAnswer(ans); x.setMarks(1); return x; }

    public static void main(String[] a) {
        System.out.println("== Flask reply parsing / error mapping");
        check("extractId finds the job id", "abc123".equals(MlIntegrationService.extractId("{\"id\":\"abc123\",\"success\":true}")));
        check("extractId tolerates spacing", "abc123".equals(MlIntegrationService.extractId("{ \"id\" : \"abc123\" }")));
        check("extractId null when missing", MlIntegrationService.extractId("{\"success\":true}") == null && MlIntegrationService.extractId(null) == null);
        check("extractError reads the message", "Please provide more text content.".equals(MlIntegrationService.extractError("{\"success\":false,\"error\":\"Please provide more text content.\"}")));
        check("extractError unescapes quotes", "Bad \"x\" value".equals(MlIntegrationService.extractError("{\"error\":\"Bad \\\"x\\\" value\"}")));
        check("extractError null for non-JSON", MlIntegrationService.extractError("<html>oops</html>") == null);
        AiServiceException e400 = MlIntegrationService.mapFailure(400, "{\"error\":\"'num_questions' must be a number.\"}");
        check("400 passes Flask's message through", e400.getHttpStatus() == 400 && e400.getMessage().contains("num_questions"));
        check("404 stays 404", MlIntegrationService.mapFailure(404, "{\"error\":\"Job not found or expired.\"}").getHttpStatus() == 404);
        AiServiceException e401 = MlIntegrationService.mapFailure(401, "{\"error\":\"Unauthorized.\"}");
        check("Flask 401 becomes a 502 config hint (never a 401 to the browser)", e401.getHttpStatus() == 502 && e401.getMessage().contains("ai.service.key"));
        AiServiceException e500 = MlIntegrationService.mapFailure(500, "{\"error\":\"The AI model is not reachable.\"}");
        check("Flask 500 becomes 502 with its friendly message", e500.getHttpStatus() == 502 && e500.getMessage().contains("not reachable"));
        check("garbage 500 body gets a generic message", MlIntegrationService.mapFailure(500, "Traceback (most recent call last)...").getMessage().contains("failed"));

        System.out.println("== Job finished detection");
        check("done status detected", MlIntegrationService.isDone("{\"status\":\"done\",\"id\":\"x\"}") && MlIntegrationService.isDone("{ \"status\" : \"done\" }"));
        check("running / error are not done", !MlIntegrationService.isDone("{\"status\":\"running\"}") && !MlIntegrationService.isDone("{\"status\":\"error\"}") && !MlIntegrationService.isDone(null));
        check("generated count read from the result meta", MlIntegrationService.generatedCount("{\"result\":{\"meta\":{\"generated\":7,\"requested\":8}}}") == 7 && MlIntegrationService.generatedCount("{}") == 0);
        AiJobRegistry once = new AiJobRegistry();
        check("finished-notification flag fires once per job", once.markNotified("j1") && !once.markNotified("j1") && once.markNotified("j2"));

        System.out.println("== Job registry isolates users");
        AiJobRegistry reg = new AiJobRegistry();
        String id = reg.register(7L, "flask-1");
        check("owner can find own job", reg.find(id, 7L) != null && "flask-1".equals(reg.find(id, 7L).flaskJobId()));
        check("another user cannot see it", reg.find(id, 8L) == null);
        check("unknown/null ids give null", reg.find("nope", 7L) == null && reg.find(null, 7L) == null);
        check("ids are unguessable-looking and unique", !id.equals(reg.register(7L, "flask-1")) && id.length() >= 32);

        System.out.println("== ExamValidator");
        List<String> opts = List.of("a", "b", "c", "d");
        check("valid exam passes", ExamValidator.firstProblem(exam("T", List.of(q("Q", opts, "a")))) == null);
        check("missing title rejected", ExamValidator.firstProblem(exam(" ", List.of(q("Q", opts, "a")))) != null);
        check("answer not among options rejected", ExamValidator.firstProblem(exam("T", List.of(q("Q", opts, "z")))).contains("Question 1"));
        check("answer match ignores case/space", ExamValidator.firstProblem(exam("T", List.of(q("Q", opts, "  A ")))) == null);
        check("fewer than 2 options rejected", ExamValidator.firstProblem(exam("T", List.of(q("Q", List.of("a"), "a")))) != null);
        check("option over 255 chars rejected", ExamValidator.firstProblem(exam("T", List.of(q("Q", List.of("a", "x".repeat(300)), "a")))).contains("255"));
        check("second bad question is numbered 2", ExamValidator.firstProblem(exam("T", List.of(q("Q", opts, "a"), q("Q2", opts, "nope")))).contains("Question 2"));

        System.out.println("== ExamValidator.prepareForSave");
        Exam d = exam("T", new ArrayList<>(List.of(q("", opts, "a"))));
        d.setId(99L); d.getQuestions().get(0).setMarks(0); d.getQuestions().get(0).setSourceExcerpt("x".repeat(900)); d.setVisibleToStudents(true);
        ExamValidator.prepareForSave(d, true, "Default");
        check("draft: status DRAFT, hidden, id cleared", "DRAFT".equals(d.getStatus()) && !d.isVisibleToStudents() && d.getId() == null);
        Question pq = d.getQuestions().get(0);
        check("question defaults applied (text, marks, back-link)", "Default".equals(pq.getQuestionText()) && pq.getMarks() == 1);
        check("source excerpt truncated to 600", pq.getSourceExcerpt().length() == 600);
        Exam p = exam("T", new ArrayList<>(List.of(q("Q", opts, "a")))); p.setVisibleToStudents(true);
        ExamValidator.prepareForSave(p, false, "Default");
        check("publish: status PUBLISHED and visibility untouched", "PUBLISHED".equals(p.getStatus()) && p.isVisibleToStudents());

        System.out.println("\nRESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
