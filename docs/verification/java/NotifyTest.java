import com.example.intern.controller.*;
import com.example.intern.model.*;
import com.example.intern.repository.*;
import com.example.intern.security.SessionUser;
import com.example.intern.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.*;

public class NotifyTest {
    static int passed = 0, failed = 0;
    static void check(String n, boolean ok) { if (ok) { passed++; System.out.println("  PASS  " + n); } else { failed++; System.out.println("  FAIL  " + n); } }
    static void login(Long id, String user, String role, String college, String branch) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new SessionUser(id, user, user, role, college, branch), null, List.of()));
    }
    static Student student(List<Object> store, Long id, String college, String dept, int sem, boolean active, boolean approved) {
        Student s = new Student(); s.setId(id); s.setName("S" + id); s.setUsername("s" + id); s.setRollNo("R" + id); s.setCollegeName(college); s.setDepartmentName(dept);
        s.setCurrentSemester(sem); s.setActive(active); s.setApproved(approved); store.add(s); return s;
    }
    static Exam exam(String title, String subject, int sem) {
        Exam e = new Exam(); e.setTitle(title); e.setSubjectName(subject); e.setTargetSemester(sem); e.setDurationMinutes(30);
        e.setStartTime(LocalDateTime.now().plusDays(1)); e.setEndTime(LocalDateTime.now().plusDays(2));
        Question q = new Question(); q.setQuestionText("Question about " + title); q.setOptions(List.of("a", "b", "c", "d")); q.setCorrectAnswer("a"); q.setMarks(1);
        e.setQuestions(new ArrayList<>(List.of(q))); return e;
    }
    static long countFor(List<Object> notes, String type, Long id) { return notes.stream().filter(x -> { Notification n = (Notification) x; return n.getRecipientType().equals(type) && n.getRecipientId().equals(id); }).count(); }

    public static void main(String[] args) throws Exception {
        List<Object> notes = new ArrayList<>(), students = new ArrayList<>(), exams = new ArrayList<>();
        NotificationService ns = DraftFlowTest.notifier(notes, students);
        student(students, 1L, "C1", "CS", 3, true, true);   // target
        student(students, 2L, "C1", "CS", 4, true, true);   // other semester
        student(students, 3L, "C1", "ME", 3, true, true);   // other department
        student(students, 4L, "C2", "CS", 3, true, true);   // other college
        student(students, 5L, "C1", "CS", 3, false, true);  // suspended
        student(students, 6L, "C1", "CS", 3, true, false);  // not approved

        System.out.println("== Exam published -> the right students");
        Exam e = exam("Physics Midterm", "Physics", 3); e.setCollegeName("C1"); e.setDepartmentName("CS");
        check("exactly one eligible student notified", ns.examPublished(e) == 1);
        Notification n = (Notification) notes.get(0);
        check("it is student 1, kind EXAM_PUBLISHED, message has title, subject and closing time", n.getRecipientId() == 1L && n.getRecipientType().equals("STUDENT") && n.getKind().equals("EXAM_PUBLISHED")
                && n.getMessage().contains("Physics Midterm") && n.getMessage().contains("Physics") && n.getMessage().contains("closes"));
        notes.clear();
        e.setTargetSemester(null);
        check("no target semester -> all active, approved students of that department (2)", ns.examPublished(e) == 2 && countFor(notes, "STUDENT", 3L) == 0 && countFor(notes, "STUDENT", 4L) == 0);
        check("exam without college/department notifies nobody", ns.examPublished(new Exam()) == 0);

        System.out.println("== Hooks in the controllers");
        notes.clear();
        ExamRepository er = DraftFlowTest.repo(ExamRepository.class, exams);
        ExamController ec = new ExamController(); DraftFlowTest.inject(ec, "examRepository", er); DraftFlowTest.inject(ec, "notificationService", ns);
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        Exam d = exam("Draft exam", "Physics", 3);
        ec.saveExamToDatabase(d, null, true, null);
        check("saving a DRAFT notifies nobody", notes.isEmpty());
        Exam stored = (Exam) exams.get(0);
        check("publishing the draft notifies the class", ec.publishDraft(stored.getId()).status == 200 && countFor(notes, "STUDENT", 1L) == 1);
        notes.clear();
        ec.saveExamToDatabase(exam("Direct", "Maths", 3), null, false, null);
        check("publishing directly notifies the class", countFor(notes, "STUDENT", 1L) == 1 && countFor(notes, "STUDENT", 2L) == 0);

        System.out.println("== Results (including auto-submit) notify the student");
        notes.clear();
        List<Object> attempts = new ArrayList<>(), results = new ArrayList<>(), qres = new ArrayList<>();
        ExamService es = new ExamService(); AttemptService as = new AttemptService();
        StudentRepository sr = DraftFlowTest.repo(StudentRepository.class, students); ExamAttemptRepository ar = DraftFlowTest.repo(ExamAttemptRepository.class, attempts); ResultRepository rr = DraftFlowTest.repo(ResultRepository.class, results);
        for (Object[] f : new Object[][]{{es, "notificationService", ns}, {es, "examRepository", er}, {es, "studentRepository", sr}, {es, "attemptRepository", ar}, {es, "resultRepository", rr},
                {es, "questionResultRepository", DraftFlowTest.repo(QuestionResultRepository.class, qres)}, {as, "attemptRepository", ar}, {as, "examRepository", er}, {as, "studentRepository", sr}, {as, "resultRepository", rr}, {as, "examService", es}}) DraftFlowTest.inject(f[0], (String) f[1], f[2]);
        Exam live = exam("Live", "Physics", 3); live.setCollegeName("C1"); live.setDepartmentName("CS"); live.setStartTime(LocalDateTime.now().minusHours(1)); live.setEndTime(LocalDateTime.now().plusHours(2));
        live.setStatus("PUBLISHED"); live.setVisibleToStudents(true); er.save(live);
        Long qid = 500L; try { var f = Question.class.getDeclaredField("id"); f.setAccessible(true); f.set(live.getQuestions().get(0), qid); } catch (Exception ex) { throw ex; }
        as.startOrResume(live.getId(), 1L, true);
        es.evaluateExam(live.getId(), 1L, Map.of("500", "a"));
        Notification r = (Notification) notes.get(notes.size() - 1);
        check("normal submit -> RESULT with the score", r.getKind().equals("RESULT") && r.getMessage().contains("1/1") && r.getMessage().contains("100%") && r.getRecipientId() == 1L);
        check("RESULT does not mention auto-submit", !r.getMessage().contains("automatically"));
        as.startOrResume(live.getId(), 2L, true);
        ExamAttempt a2 = (ExamAttempt) attempts.stream().filter(x -> ((ExamAttempt) x).getStudentId() == 2L).findFirst().get();
        a2.setDeadline(LocalDateTime.now().minusMinutes(10));
        as.startOrResume(live.getId(), 2L, true);
        Notification auto = (Notification) notes.stream().filter(x -> ((Notification) x).getRecipientId() == 2L && ((Notification) x).getKind().equals("AUTO_SUBMITTED")).findFirst().orElse(null);
        check("returning after the deadline -> AUTO_SUBMITTED notice", auto != null && auto.getMessage().contains("automatically"));
        // a failing notifier must never break grading
        ExamService es2 = new ExamService();
        for (Object[] f : new Object[][]{{es2, "notificationService", new NotificationService()}, {es2, "examRepository", er}, {es2, "studentRepository", sr}, {es2, "attemptRepository", ar}, {es2, "resultRepository", DraftFlowTest.repo(ResultRepository.class, new ArrayList<>())}, {es2, "questionResultRepository", DraftFlowTest.repo(QuestionResultRepository.class, new ArrayList<>())}}) DraftFlowTest.inject(f[0], (String) f[1], f[2]);
        student(students, 7L, "C1", "CS", 3, true, true);
        ExamAttempt a7 = new ExamAttempt(); a7.setExamId(live.getId()); a7.setStudentId(7L); a7.setStartedAt(LocalDateTime.now()); a7.setDeadline(LocalDateTime.now().plusHours(1)); ar.save(a7);
        check("a broken notification service does NOT break grading", es2.evaluateExam(live.getId(), 7L, Map.of("500", "a")).getMarksObtained() == 1);
        check("producers never throw when unconfigured", new NotificationService().examPublished(live) == 0);

        System.out.println("== API: own notifications only, ids overlap between students and staff");
        notes.clear();
        ns.notifyUser("STUDENT", 1L, "RESULT", "Student one", "m", null);
        ns.notifyUser("STAFF", 1L, "AI_DONE", "Staff one", "m", null);
        ns.notifyUser("STUDENT", 2L, "RESULT", "Student two", "m", null);
        NotificationController nc = new NotificationController(); DraftFlowTest.inject(nc, "notificationService", ns);
        login(1L, "s1", "STUDENT", "C1", "CS");
        @SuppressWarnings("unchecked") Map<String, Object> mine = (Map<String, Object>) nc.list().body;
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) mine.get("items");
        check("student 1 sees only their own (not staff 1, not student 2)", items.size() == 1 && "Student one".equals(items.get(0).get("title")) && ((Long) mine.get("unread")) == 1L);
        Long staffNoteId = ((Notification) notes.stream().filter(x -> ((Notification) x).getRecipientType().equals("STAFF")).findFirst().get()).getId();
        check("student 1 cannot mark STAFF 1's notification (same numeric id)", nc.read(staffNoteId).status == 404);
        login(1L, "t1", "TEACHER", "C1", "CS");
        @SuppressWarnings("unchecked") Map<String, Object> staff = (Map<String, Object>) nc.list().body;
        check("staff 1 sees only the staff notification", ((List<?>) staff.get("items")).size() == 1 && ((Long) staff.get("unread")) == 1L);
        check("staff 1 can read their own", nc.read(staffNoteId).status == 200 && ((Long) ((Map<?, ?>) nc.list().body).get("unread")) == 0L);
        login(2L, "s2", "STUDENT", "C1", "CS");
        check("read-all marks only the caller's", ((Map<?, ?>) nc.readAll().body).get("marked").equals(1) && countFor(notes, "STUDENT", 1L) == 1
                && notes.stream().filter(x -> ((Notification) x).getRecipientId() == 1L && ((Notification) x).getRecipientType().equals("STUDENT")).allMatch(x -> ((Notification) x).getReadAt() == null));

        System.out.println("== Latest 30, newest first; retention");
        notes.clear();
        for (int i = 0; i < 35; i++) { ns.notifyUser("STUDENT", 9L, "RESULT", "N" + i, "m", null); ((Notification) notes.get(notes.size() - 1)).setCreatedAt(LocalDateTime.now().minusMinutes(100 - i)); }
        List<Notification> latest = ns.latest("STUDENT", 9L);
        check("only 30 returned, newest first (N34 ... N5)", latest.size() == 30 && latest.get(0).getTitle().equals("N34") && latest.get(29).getTitle().equals("N5"));
        ((Notification) notes.get(0)).setCreatedAt(LocalDateTime.now().minusDays(120));
        ns.purgeOlderThanDays(90);
        check("purge removes only notifications older than 90 days", notes.size() == 34 && notes.stream().noneMatch(x -> ((Notification) x).getTitle().equals("N0")));
        ns.notifyUser("STUDENT", 9L, "RESULT", "x".repeat(400), "y".repeat(900), null);
        Notification big = (Notification) notes.get(notes.size() - 1);
        check("over-long text is clipped to the column sizes", big.getTitle().length() <= 160 && big.getMessage().length() <= 500);

        System.out.println("== Question bank");
        exams.clear();
        ExamController qc = new ExamController(); DraftFlowTest.inject(qc, "examRepository", er);
        Exam A = exam("Physics A", "Physics", 3); A.setCollegeName("C1"); A.setDepartmentName("CS"); A.setCreatedByTeacherUsername("t1@x");
        Question extra = new Question(); extra.setQuestionText("What is velocity?"); extra.setOptions(List.of("a", "b", "c", "d")); extra.setCorrectAnswer("b"); extra.setMarks(2); A.getQuestions().add(extra);
        Exam B = exam("Maths B", "Maths", 3); B.setCollegeName("C1"); B.setDepartmentName("CS"); B.setCreatedByTeacherUsername("t2@x");
        B.getQuestions().get(0).setQuestionText("What is velocity?");       // duplicate text of A's second question
        Question uniq = new Question(); uniq.setQuestionText("What is a derivative?"); uniq.setOptions(List.of("a", "b", "c", "d")); uniq.setCorrectAnswer("c"); uniq.setMarks(1); B.getQuestions().add(uniq);
        Exam C = exam("Mech C", "Mechanics", 3); C.setCollegeName("C1"); C.setDepartmentName("ME"); C.setCreatedByTeacherUsername("t9@x");
        Exam D = exam("Other college", "Physics", 3); D.setCollegeName("C2"); D.setDepartmentName("CS"); D.setCreatedByTeacherUsername("t8@x");
        for (Exam x : List.of(A, B, C, D)) er.save(x);
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        @SuppressWarnings("unchecked") Map<String, Object> bank = (Map<String, Object>) qc.questionBank(null, null).body;
        List<?> qs = (List<?>) bank.get("questions");
        check("teacher sees own + same-branch questions, deduplicated, none from other branch/college (3 distinct)", qs.size() == 3);
        check("subjects list covers visible exams only", bank.get("subjects").toString().equals("[Maths, Physics]"));
        @SuppressWarnings("unchecked") Map<String, Object> phys = (Map<String, Object>) qc.questionBank(null, "physics").body;
        check("subject filter (case-insensitive)", ((List<?>) phys.get("questions")).size() == 2);
        @SuppressWarnings("unchecked") Map<String, Object> srch = (Map<String, Object>) qc.questionBank("velocity", null).body;
        check("text search", ((List<?>) srch.get("questions")).size() == 1);
        check("entries carry options, correct answer and marks for reuse", ((Map<?, ?>) ((List<?>) srch.get("questions")).get(0)).get("correctAnswer") != null && ((Map<?, ?>) ((List<?>) srch.get("questions")).get(0)).get("marks") != null);
        login(20L, "p@x", "PRINCIPAL", "C1", "ADMINISTRATION");
        check("principal sees the whole college (other branch too) but not another college", ((List<?>) ((Map<?, ?>) qc.questionBank(null, null).body).get("questions")).size() == 4);
        login(11L, "t5@x", "TEACHER", "C1", "ME");
        check("a teacher of another branch does not see CS questions", ((List<?>) ((Map<?, ?>) qc.questionBank(null, "Physics").body).get("questions")).isEmpty());

        System.out.println("\nRESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
