import com.example.intern.model.*;
import com.example.intern.repository.*;
import com.example.intern.service.*;

import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class AttemptLogicTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
    static void expectReject(String name, int status, Runnable r) {
        try { r.run(); check(name + " (expected HTTP " + status + ", got success)", false); }
        catch (SubmissionRejectedException e) { check(name + " -> " + e.getHttpStatus() + " \"" + e.getMessage() + "\"", e.getHttpStatus() == status); }
        catch (Exception e) { check(name + " (unexpected " + e + ")", false); }
    }

    // ---------- in-memory repositories via dynamic proxy ----------
    static Object idOf(Object e) throws Exception { return e.getClass().getMethod("getId").invoke(e); }
    static void setId(Object e, Long id) throws Exception {
        Field f = e.getClass().getDeclaredField("id"); f.setAccessible(true); f.set(e, id);
    }
    static Object get(Object e, String m) throws Exception { return e.getClass().getMethod(m).invoke(e); }

    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> iface, List<Object> store) {
        AtomicLong seq = new AtomicLong(1000);
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, m, a) -> {
            switch (m.getName()) {
                case "save": {
                    Object e = a[0];
                    if (idOf(e) == null) setId(e, seq.incrementAndGet());
                    store.removeIf(x -> { try { return idOf(x).equals(idOf(e)); } catch (Exception ex) { return false; } });
                    store.add(e); return e;
                }
                case "findById": { for (Object x : store) if (idOf(x).equals(a[0])) return Optional.of(x); return Optional.empty(); }
                case "findAll": return new ArrayList<>(store);
                case "delete": store.remove(a[0]); return null;
                case "deleteById": store.removeIf(x -> { try { return idOf(x).equals(a[0]); } catch (Exception ex) { return false; } }); return null;
                case "count": return (long) store.size();
                case "existsById": { for (Object x : store) if (idOf(x).equals(a[0])) return true; return false; }
                case "existsByExamIdAndStudentId":
                    for (Object x : store) if (get(x, "getExamId").equals(a[0]) && get(x, "getStudentId").equals(a[1])) return true; return false;
                case "findByExamIdAndStudentId":
                    for (Object x : store) if (get(x, "getExamId").equals(a[0]) && get(x, "getStudentId").equals(a[1])) return Optional.of(x); return Optional.empty();
                case "findByStatusAndDeadlineBefore": {
                    List<Object> out = new ArrayList<>();
                    for (Object x : store) if (get(x, "getStatus").equals(a[0]) && ((LocalDateTime) get(x, "getDeadline")).isBefore((LocalDateTime) a[1])) out.add(x);
                    return out;
                }
                case "deleteByExamId": store.removeIf(x -> { try { return get(x, "getExamId").equals(a[0]); } catch (Exception ex) { return false; } }); return null;
                case "toString": return iface.getSimpleName() + "Fake";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: throw new UnsupportedOperationException("fake repo: " + m.getName());
            }
        });
    }

    static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value);
    }

    // ---------- fixtures ----------
    static List<Object> exams, students, attempts, results, qresults;
    static ExamService examService; static AttemptService attemptService;
    static Exam exam; static Student alice, bob;

    static void reset() throws Exception {
        exams = new ArrayList<>(); students = new ArrayList<>(); attempts = new ArrayList<>(); results = new ArrayList<>(); qresults = new ArrayList<>();
        ExamRepository er = repo(ExamRepository.class, exams);
        StudentRepository sr = repo(StudentRepository.class, students);
        ExamAttemptRepository ar = repo(ExamAttemptRepository.class, attempts);
        ResultRepository rr = repo(ResultRepository.class, results);
        QuestionResultRepository qr = repo(QuestionResultRepository.class, qresults);
        examService = new ExamService(); attemptService = new AttemptService();
        inject(examService, "examRepository", er); inject(examService, "studentRepository", sr);
        inject(examService, "attemptRepository", ar); inject(examService, "resultRepository", rr);
        inject(examService, "questionResultRepository", qr);
        inject(attemptService, "attemptRepository", ar); inject(attemptService, "examRepository", er);
        inject(attemptService, "studentRepository", sr); inject(attemptService, "resultRepository", rr);
        inject(attemptService, "examService", examService);

        exam = new Exam(); exam.setId(1L); exam.setTitle("Physics"); exam.setDepartmentName("CS"); exam.setCollegeName("C1");
        exam.setDurationMinutes(30); exam.setVisibleToStudents(true);
        List<Question> qs = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            Question q = new Question(); q.setId(i); q.setQuestionText("Q" + i); q.setOptions(List.of("a", "b", "c")); q.setCorrectAnswer("a"); q.setMarks(1); q.setExam(exam); qs.add(q);
        }
        exam.setQuestions(qs); exams.add(exam);
        alice = student(1L, "R1", "C1", "CS"); bob = student(2L, "R2", "C1", "CS");
    }
    static Student student(Long id, String roll, String college, String dept) {
        Student s = new Student(); s.setId(id); s.setName("S" + id); s.setUsername("s" + id + "@x"); s.setRollNo(roll);
        s.setCollegeName(college); s.setDepartmentName(dept); s.setApproved(true); s.setActive(true); students.add(s); return s;
    }
    static ExamAttempt attemptOf(Long studentId) { return (ExamAttempt) attempts.stream().filter(x -> ((ExamAttempt) x).getStudentId().equals(studentId)).findFirst().orElse(null); }
    static Map<String,String> ans(String... kv) { Map<String,String> m = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i+1]); return m; }
    static Result resultOf(Long studentId) { return (Result) results.stream().filter(x -> ((Result) x).getStudentId().equals(studentId)).findFirst().orElse(null); }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. Start / resume keeps the SERVER deadline");
        reset();
        ExamAttempt a1 = attemptService.startOrResume(1L, 1L, true);
        check("attempt created IN_PROGRESS", ExamAttempt.IN_PROGRESS.equals(a1.getStatus()));
        long mins = java.time.Duration.between(a1.getStartedAt(), a1.getDeadline()).toMinutes();
        check("deadline = start + 30 min", mins == 30);
        LocalDateTime d0 = a1.getDeadline();
        ExamAttempt a2 = attemptService.startOrResume(1L, 1L, true);
        check("second start (refresh) returns the SAME attempt", a2.getId().equals(a1.getId()));
        check("deadline unchanged after refresh", a2.getDeadline().equals(d0));
        check("only one attempt row exists", attempts.size() == 1);
        expectReject("resume-only without attempt (bob) is refused", 409, () -> attemptService.startOrResume(1L, 2L, false));
        check("resume-only for existing attempt works", attemptService.startOrResume(1L, 1L, false).getId().equals(a1.getId()));

        System.out.println("== 2. Deadline never exceeds the exam end time");
        reset(); exam.setEndTime(LocalDateTime.now().plusMinutes(10));
        ExamAttempt capped = attemptService.startOrResume(1L, 1L, true);
        check("deadline capped to endTime (10 < 30 min)", java.time.Duration.between(LocalDateTime.now(), capped.getDeadline()).toMinutes() <= 10);

        System.out.println("== 3. Autosave: sequencing, validation");
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a", "2", "b"), List.of(2L));
        ExamAttempt at = attemptOf(1L);
        check("answers saved", "a".equals(at.getDraftAnswers().get(1L)) && "b".equals(at.getDraftAnswers().get(2L)));
        check("flag saved", at.getFlaggedQuestions().contains(2L));
        attemptService.autosave(1L, 1L, 1, ans("1", "c"), List.of());
        check("same seq ignored (no overwrite)", "a".equals(attemptOf(1L).getDraftAnswers().get(1L)));
        attemptService.autosave(1L, 1L, 0, ans("1", "c"), List.of());
        check("older seq ignored", "a".equals(attemptOf(1L).getDraftAnswers().get(1L)));
        attemptService.autosave(1L, 1L, 2, ans("1", "A", "99", "a", "2", "not-an-option", "x", "a", "3", "x".repeat(2000)), List.of(1L, 99L));
        at = attemptOf(1L);
        check("option match is case-insensitive", "A".equals(at.getDraftAnswers().get(1L)));
        check("unknown question id dropped", !at.getDraftAnswers().containsKey(99L));
        check("non-option answer dropped", !at.getDraftAnswers().containsKey(2L));
        check("non-numeric key dropped; oversize answer dropped", at.getDraftAnswers().size() == 1);
        check("unknown flag dropped, valid kept", at.getFlaggedQuestions().equals(Set.of(1L)));
        check("snapshot REPLACES previous (q2 gone)", !at.getDraftAnswers().containsKey(2L));
        expectReject("autosave without an attempt (bob)", 409, () -> attemptService.autosave(1L, 2L, 1, ans(), List.of()));

        System.out.println("== 4. Submit merges drafts with the final payload");
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a"), List.of());
        Result r = examService.evaluateExam(1L, 1L, ans("2", "a"));
        check("draft q1 + payload q2 both counted (2/3)", r.getMarksObtained() == 2 && r.getTotalMarks() == 3);
        check("attempt marked SUBMITTED", ExamAttempt.SUBMITTED.equals(attemptOf(1L).getStatus()));
        expectReject("duplicate submit refused", 409, () -> examService.evaluateExam(1L, 1L, ans("1", "a")));
        check("still exactly one result", results.size() == 1);
        check("3 question results recorded", qresults.size() == 3);
        expectReject("autosave after submit refused", 409, () -> attemptService.autosave(1L, 1L, 5, ans("1", "a"), List.of()));
        expectReject("restart after submit refused", 403, () -> attemptService.startOrResume(1L, 1L, true));

        System.out.println("== 5. Payload overrides a draft");
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a"), List.of());
        r = examService.evaluateExam(1L, 1L, ans("1", "b"));
        check("changed answer (a -> b) scores 0", r.getMarksObtained() == 0);

        System.out.println("== 6. Submit without starting");
        reset();
        expectReject("submit with no attempt refused", 409, () -> examService.evaluateExam(1L, 1L, ans("1", "a")));
        check("no result created", results.isEmpty());

        System.out.println("== 7. Late submit counts ONLY saved answers");
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a"), List.of());
        attemptOf(1L).setDeadline(LocalDateTime.now().minusMinutes(10));   // simulate: 10 minutes past the deadline
        r = examService.evaluateExam(1L, 1L, ans("1", "a", "2", "a", "3", "a"));
        check("late payload ignored: only the saved answer scores (1/3)", r.getMarksObtained() == 1);
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptOf(1L).setDeadline(LocalDateTime.now().minusSeconds(30));   // within the 2-minute grace
        r = examService.evaluateExam(1L, 1L, ans("1", "a", "2", "a", "3", "a"));
        check("submit within grace period counts everything (3/3)", r.getMarksObtained() == 3);
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptOf(1L).setDeadline(LocalDateTime.now().minusMinutes(10));
        expectReject("autosave after deadline+grace refused", 403, () -> attemptService.autosave(1L, 1L, 9, ans("1", "a"), List.of()));

        System.out.println("== 8. Returning after the deadline auto-submits saved answers");
        reset(); attemptService.startOrResume(1L, 1L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a", "2", "a"), List.of());
        attemptOf(1L).setDeadline(LocalDateTime.now().minusMinutes(10));
        ExamAttempt back = attemptService.startOrResume(1L, 1L, true);
        check("attempt auto-submitted on return", ExamAttempt.SUBMITTED.equals(back.getStatus()));
        check("graded from saved answers (2/3)", resultOf(1L) != null && resultOf(1L).getMarksObtained() == 2);
        expectReject("further start refused", 403, () -> attemptService.startOrResume(1L, 1L, true));

        System.out.println("== 9. Background sweeper");
        reset(); attemptService.startOrResume(1L, 1L, true); attemptService.startOrResume(1L, 2L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a"), List.of());
        attemptOf(1L).setDeadline(LocalDateTime.now().minusMinutes(20));   // abandoned long ago
        attemptOf(2L).setDeadline(LocalDateTime.now().minusMinutes(1));    // just expired: user may still submit
        int n = attemptService.sweepExpired();
        check("sweeper finalized exactly the abandoned attempt", n == 1);
        check("abandoned attempt graded from autosave (1/3)", resultOf(1L) != null && resultOf(1L).getMarksObtained() == 1);
        check("recently expired attempt left alone (still in progress)", resultOf(2L) == null && ExamAttempt.IN_PROGRESS.equals(attemptOf(2L).getStatus()));
        check("sweeper is idempotent", attemptService.sweepExpired() == 0 && results.size() == 1);

        System.out.println("== 10. Eligibility");
        reset(); Student other = student(3L, "R3", "C2", "CS");
        expectReject("other college refused", 403, () -> attemptService.startOrResume(1L, 3L, true));
        Student wrongDept = student(4L, "R4", "C1", "ME");
        expectReject("other department refused", 403, () -> attemptService.startOrResume(1L, 4L, true));
        Student suspended = student(5L, "R5", "C1", "CS"); suspended.setActive(false);
        expectReject("suspended student refused", 403, () -> attemptService.startOrResume(1L, 5L, true));
        expectReject("unknown exam refused", 404, () -> attemptService.startOrResume(77L, 1L, true));

        System.out.println("== 11. Visibility and schedule");
        reset(); exam.setVisibleToStudents(false);
        expectReject("hidden exam cannot be started", 403, () -> attemptService.startOrResume(1L, 1L, true));
        reset(); attemptService.startOrResume(1L, 1L, true); exam.setVisibleToStudents(false);
        check("teacher hiding the exam mid-sitting does NOT block autosave", attemptService.autosave(1L, 1L, 1, ans("1", "a"), List.of()) != null);
        check("...nor submission", examService.evaluateExam(1L, 1L, ans("2", "a")).getMarksObtained() == 2);
        reset(); exam.setStartTime(LocalDateTime.now().plusHours(1));
        expectReject("exam not started yet", 403, () -> attemptService.startOrResume(1L, 1L, true));
        reset(); exam.setEndTime(LocalDateTime.now().minusMinutes(1));
        expectReject("exam window already closed", 403, () -> attemptService.startOrResume(1L, 1L, true));

        System.out.println("== 12. Students are independent");
        reset(); attemptService.startOrResume(1L, 1L, true); attemptService.startOrResume(1L, 2L, true);
        attemptService.autosave(1L, 1L, 1, ans("1", "a"), List.of()); attemptService.autosave(1L, 2L, 1, ans("1", "b"), List.of());
        check("alice and bob have separate drafts", "a".equals(attemptOf(1L).getDraftAnswers().get(1L)) && "b".equals(attemptOf(2L).getDraftAnswers().get(1L)));
        examService.evaluateExam(1L, 1L, ans());
        check("bob can still continue after alice submitted", attemptService.autosave(1L, 2L, 2, ans("2", "a"), List.of()) != null);

        System.out.println("\nRESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
