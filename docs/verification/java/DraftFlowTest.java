import com.example.intern.controller.*;
import com.example.intern.model.*;
import com.example.intern.repository.*;
import com.example.intern.security.SessionUser;
import com.example.intern.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class DraftFlowTest {
    static int passed = 0, failed = 0;
    static void check(String n, boolean ok) { if (ok) { passed++; System.out.println("  PASS  " + n); } else { failed++; System.out.println("  FAIL  " + n); } }
    static void is(String n, ResponseEntity<?> r, int status) { check(n + " -> " + r.status, r.status == status); }

    static Object idOf(Object e) throws Exception { return e.getClass().getMethod("getId").invoke(e); }
    static void setId(Object e, Long id) throws Exception { Field f = e.getClass().getDeclaredField("id"); f.setAccessible(true); f.set(e, id); }
    @SuppressWarnings("unchecked")
    static <T> T repo(Class<T> iface, List<Object> store) {
        AtomicLong seq = new AtomicLong(100);
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, m, a) -> {
            String n = m.getName();
            switch (n) {
                case "save": { Object e = a[0]; if (idOf(e) == null) setId(e, seq.incrementAndGet());
                    store.removeIf(x -> { try { return idOf(x).equals(idOf(e)); } catch (Exception ex) { return false; } }); store.add(e); return e; }
                case "findById": { for (Object x : store) if (idOf(x).equals(a[0])) return Optional.of(x); return Optional.empty(); }
                case "findAll": return new ArrayList<>(store);
                case "deleteById": store.removeIf(x -> { try { return idOf(x).equals(a[0]); } catch (Exception ex) { return false; } }); return null;
                case "delete": store.remove(a[0]); return null;
                case "existsByExamIdAndStudentId":
                    for (Object x : store) if (Objects.equals(x.getClass().getMethod("getExamId").invoke(x), a[0]) && Objects.equals(x.getClass().getMethod("getStudentId").invoke(x), a[1])) return true;
                    return false;
                case "findByExamIdAndStudentId":
                    for (Object x : store) if (Objects.equals(x.getClass().getMethod("getExamId").invoke(x), a[0]) && Objects.equals(x.getClass().getMethod("getStudentId").invoke(x), a[1])) return Optional.of(x);
                    return Optional.empty();
                case "toString": return "fake"; case "hashCode": return 1; case "equals": return p == a[0];
                case "findTop30ByRecipientTypeAndRecipientIdOrderByCreatedAtDesc": {
                    List<Object> out = new ArrayList<>();
                    for (Object x : store) if (Objects.equals(x.getClass().getMethod("getRecipientType").invoke(x), a[0]) && Objects.equals(x.getClass().getMethod("getRecipientId").invoke(x), a[1])) out.add(x);
                    out.sort((p1, p2) -> { try { return ((LocalDateTime) p2.getClass().getMethod("getCreatedAt").invoke(p2)).compareTo((LocalDateTime) p1.getClass().getMethod("getCreatedAt").invoke(p1)); } catch (Exception ex) { return 0; } });
                    return out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
                }
                case "countByRecipientTypeAndRecipientIdAndReadAtIsNull":
                case "findByRecipientTypeAndRecipientIdAndReadAtIsNull": {
                    List<Object> out = new ArrayList<>();
                    for (Object x : store) if (Objects.equals(x.getClass().getMethod("getRecipientType").invoke(x), a[0]) && Objects.equals(x.getClass().getMethod("getRecipientId").invoke(x), a[1]) && x.getClass().getMethod("getReadAt").invoke(x) == null) out.add(x);
                    return n.startsWith("count") ? (Object) (long) out.size() : out;
                }
                case "deleteByCreatedAtBefore": store.removeIf(x -> { try { return ((LocalDateTime) x.getClass().getMethod("getCreatedAt").invoke(x)).isBefore((LocalDateTime) a[0]); } catch (Exception ex) { return false; } }); return null;
                case "findByCollegeNameAndDepartmentNameAndIsActiveTrue": {
                    List<Object> out = new ArrayList<>();
                    for (Object x : store) if (Objects.equals(x.getClass().getMethod("getCollegeName").invoke(x), a[0]) && Objects.equals(x.getClass().getMethod("getDepartmentName").invoke(x), a[1]) && (Boolean) x.getClass().getMethod("isActive").invoke(x)) out.add(x);
                    return out;
                }
                default:
                    if (n.startsWith("findBy") && a.length == 1) {
                        String getter = "get" + n.substring(6); List<Object> out = new ArrayList<>();
                        for (Object x : store) if (Objects.equals(x.getClass().getMethod(getter).invoke(x), a[0])) out.add(x); return out;
                    }
                    throw new UnsupportedOperationException(n);
            }
        });
    }
    static void inject(Object t, String f, Object v) throws Exception { Field x = t.getClass().getDeclaredField(f); x.setAccessible(true); x.set(t, v); }
    static NotificationService notifier(List<Object> notes, List<Object> students) throws Exception {
        NotificationService ns = new NotificationService();
        inject(ns, "notificationRepository", repo(NotificationRepository.class, notes)); inject(ns, "studentRepository", repo(StudentRepository.class, students));
        return ns;
    }
    static void login(Long id, String user, String role, String college, String branch) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new SessionUser(id, user, user, role, college, branch), null, List.of()));
    }

    static List<Object> exams; static ExamController ec; static HodController hc;
    static Exam payload(String title) {
        Exam e = new Exam(); e.setTitle(title); e.setSubjectName("Physics"); e.setTargetSemester(3); e.setDurationMinutes(30);
        e.setCreatedByTeacherUsername("someone-else@x"); e.setCollegeName("EVIL-COLLEGE"); e.setDepartmentName("ME");
        Question q = new Question(); q.setQuestionText("Which?"); q.setOptions(List.of("a", "b", "c", "d")); q.setCorrectAnswer("a"); q.setAiGenerated(true); q.setSourcePage(2); q.setSourceExcerpt("evidence");
        e.setStartTime(LocalDateTime.now().plusDays(1)); e.setEndTime(LocalDateTime.now().plusDays(2)); e.setQuestions(new ArrayList<>(List.of(q))); return e;
    }
    static Exam stored(Object body) { return (Exam) exams.stream().filter(x -> body.toString().endsWith(String.valueOf(((Exam) x).getId()))).findFirst().orElse(null); }

    public static void main(String[] args) throws Exception {
        exams = new ArrayList<>();
        ec = new ExamController(); hc = new HodController();
        ExamRepository er = repo(ExamRepository.class, exams);
        NotificationService nsMain = notifier(new ArrayList<>(), new ArrayList<>());
        inject(ec, "examRepository", er); inject(hc, "examRepository", er); inject(ec, "notificationService", nsMain); inject(hc, "notificationService", nsMain);

        System.out.println("== Saving as draft");
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        ResponseEntity<?> r = ec.saveExamToDatabase(payload("Draft A"), null, true, null);
        is("save draft", r, 200);
        Exam draft = (Exam) exams.get(0); Long draftId = draft.getId();
        check("status DRAFT and hidden from students", "DRAFT".equals(draft.getStatus()) && !draft.isVisibleToStudents());
        check("creator/college forced from session, not payload", "t1@x".equals(draft.getCreatedByTeacherUsername()) && "C1".equals(draft.getCollegeName()));
        Question sq = draft.getQuestions().get(0);
        check("AI provenance persisted on the question", Boolean.TRUE.equals(sq.getAiGenerated()) && sq.getSourcePage() == 2 && "evidence".equals(sq.getSourceExcerpt()));

        check("teacher's exam is forced to the teacher's own branch (payload said ME)", "CS".equals(draft.getDepartmentName()));
        Exam bad = payload("Bad"); bad.getQuestions().get(0).setCorrectAnswer("not an option");
        is("invalid exam refused", ec.saveExamToDatabase(bad, null, true, null), 400);
        check("nothing stored for the invalid exam", exams.size() == 1);

        System.out.println("== Drafts are protected");
        is("toggle-visibility on a draft refused", ec.toggleExamVisibility(draftId), 409);
        login(11L, "t2@x", "TEACHER", "C1", "CS");
        is("other teacher cannot reopen the draft", ec.getDraft(draftId), 403);
        is("other teacher cannot publish it", ec.publishDraft(draftId), 403);
        is("other teacher cannot delete it", ec.deleteDraft(draftId), 403);
        login(13L, "t3@x", "TEACHER", "C2", "CS");
        is("teacher of another college cannot publish it", ec.publishDraft(draftId), 403);
        is("...nor reopen it", ec.getDraft(draftId), 403);

        System.out.println("== Reopen, edit (replace) and publish");
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        r = ec.getDraft(draftId); is("owner reopens the draft", r, 200);
        check("reopened draft includes answers for editing", r.body instanceof Exam && ((Exam) r.body).getQuestions().get(0).getCorrectAnswer().equals("a"));
        r = ec.saveExamToDatabase(payload("Draft A (edited)"), null, true, draftId); is("replace draft with edited version", r, 200);
        check("old draft gone, exactly one draft remains", exams.size() == 1 && ((Exam) exams.get(0)).getTitle().contains("edited") && !((Exam) exams.get(0)).getId().equals(draftId));
        Long editedId = ((Exam) exams.get(0)).getId();
        is("replacing a missing draft -> 404", ec.saveExamToDatabase(payload("x"), null, true, 9999L), 404);
        login(11L, "t2@x", "TEACHER", "C1", "CS");
        is("replacing someone else's draft -> 403", ec.saveExamToDatabase(payload("x"), null, true, editedId), 403);
        check("their draft is untouched", exams.size() == 1);

        login(12L, "hod@x", "HOD", "C1", "CS");
        is("HOD of the same branch can publish", ec.publishDraft(editedId), 200);
        Exam pub = (Exam) exams.get(0);
        check("now PUBLISHED and visible", "PUBLISHED".equals(pub.getStatus()) && pub.isVisibleToStudents());
        is("publishing twice refused", ec.publishDraft(editedId), 409);
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        is("published exam cannot be reopened as a draft", ec.getDraft(editedId), 409);
        is("published exam cannot be 'replaced'", ec.saveExamToDatabase(payload("x"), null, true, editedId), 409);
        is("published exam cannot be deleted as a draft", ec.deleteDraft(editedId), 409);
        is("normal visibility toggle still works once published", ec.toggleExamVisibility(editedId), 200);

        System.out.println("== Publishing needs a schedule");
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        Exam unscheduled = payload("Unscheduled"); unscheduled.setStartTime(null); unscheduled.setEndTime(null);
        is("a draft may be saved without a schedule", ec.saveExamToDatabase(unscheduled, null, true, null), 200);
        Exam us = (Exam) exams.stream().filter(x -> "Unscheduled".equals(((Exam) x).getTitle())).findFirst().get();
        r = ec.publishDraft(us.getId()); is("...but cannot be published without start/end", r, 400);
        check("message tells the teacher what to do", String.valueOf(r.body).contains("start time"));
        us.setStartTime(LocalDateTime.now().plusDays(2)); us.setEndTime(LocalDateTime.now().plusDays(1));
        is("end before start cannot be published", ec.publishDraft(us.getId()), 400);
        us.setStartTime(LocalDateTime.now().plusDays(1)); us.setEndTime(LocalDateTime.now().plusDays(2));
        is("valid schedule can be published", ec.publishDraft(us.getId()), 200);

        System.out.println("== Editing someone else's draft keeps the author");
        login(10L, "t1@x", "TEACHER", "C1", "CS");
        ec.saveExamToDatabase(payload("Author test"), null, true, null);
        Exam at = (Exam) exams.stream().filter(x -> "Author test".equals(((Exam) x).getTitle())).findFirst().get();
        login(12L, "hod@x", "HOD", "C1", "CS");
        is("HOD edits the teacher's draft", ec.saveExamToDatabase(payload("Author test v2"), null, true, at.getId()), 200);
        Exam at2 = (Exam) exams.stream().filter(x -> "Author test v2".equals(((Exam) x).getTitle())).findFirst().get();
        check("original author t1@x is preserved (not replaced by the HOD)", "t1@x".equals(at2.getCreatedByTeacherUsername()));
        check("old version is gone", exams.stream().noneMatch(x -> "Author test".equals(((Exam) x).getTitle())));
        login(10L, "t1@x", "TEACHER", "C1", "CS");

        System.out.println("== Publishing rules");
        Exam empty = payload("Empty"); empty.setQuestions(new ArrayList<>());
        ec.saveExamToDatabase(empty, null, true, null);
        Exam emptyDraft = (Exam) exams.stream().filter(x -> "Empty".equals(((Exam) x).getTitle())).findFirst().get();
        is("a draft without questions cannot be published", ec.publishDraft(emptyDraft.getId()), 400);
        is("owner can delete the empty draft", ec.deleteDraft(emptyDraft.getId()), 200);
        check("it is really deleted", exams.stream().noneMatch(x -> "Empty".equals(((Exam) x).getTitle())));
        r = ec.saveExamToDatabase(payload("Direct publish"), null, false, null); is("normal (non-draft) save still publishes immediately", r, 200);
        Exam direct = (Exam) exams.stream().filter(x -> "Direct publish".equals(((Exam) x).getTitle())).findFirst().get();
        check("direct publish is PUBLISHED and visible", "PUBLISHED".equals(direct.getStatus()) && direct.isVisibleToStudents());
        login(20L, "p@x", "PRINCIPAL", "C1", "ADMINISTRATION");
        check("principal of the same college can save a draft", ec.saveExamToDatabase(payload("P draft"), null, true, null).status == 200);
        Exam noDept = payload("No dept"); noDept.setDepartmentName(null);
        is("principal must choose a department", ec.saveExamToDatabase(noDept, null, true, null), 400);
        check("principal's chosen department is kept", "ME".equals(((Exam) exams.stream().filter(x -> "P draft".equals(((Exam) x).getTitle())).findFirst().get()).getDepartmentName()));

        System.out.println("== HOD can save drafts too");
        login(12L, "hod@x", "HOD", "C1", "CS");
        ResponseEntity<?> hr = hc.saveBranchExam(null, null, true, payload("HOD draft"));
        is("HOD saves a draft", hr, 200);
        Exam hd = (Exam) exams.stream().filter(x -> "HOD draft".equals(((Exam) x).getTitle())).findFirst().get();
        check("HOD draft: DRAFT, hidden, branch/college/creator from session", "DRAFT".equals(hd.getStatus()) && !hd.isVisibleToStudents()
                && "CS".equals(hd.getDepartmentName()) && "C1".equals(hd.getCollegeName()) && "hod@x".equals(hd.getCreatedByTeacherUsername()));
        hr = hc.saveBranchExam(null, null, false, payload("HOD live"));
        Exam hl = (Exam) exams.stream().filter(x -> "HOD live".equals(((Exam) x).getTitle())).findFirst().get();
        check("HOD normal save still publishes", hr.status == 200 && "PUBLISHED".equals(hl.getStatus()));

        System.out.println("== Students can never start a draft");
        List<Object> students = new ArrayList<>(), attempts = new ArrayList<>(), results = new ArrayList<>(), qres = new ArrayList<>();
        ExamService es = new ExamService(); AttemptService as = new AttemptService();
        StudentRepository sr = repo(StudentRepository.class, students); ExamAttemptRepository ar = repo(ExamAttemptRepository.class, attempts);
        ResultRepository rr = repo(ResultRepository.class, results);
        for (Object[] f : new Object[][]{{es, "notificationService", notifier(new ArrayList<>(), students)}, {es, "examRepository", er}, {es, "studentRepository", sr}, {es, "attemptRepository", ar}, {es, "resultRepository", rr},
                {es, "questionResultRepository", repo(QuestionResultRepository.class, qres)},
                {as, "attemptRepository", ar}, {as, "examRepository", er}, {as, "studentRepository", sr}, {as, "resultRepository", rr}, {as, "examService", es}}) inject(f[0], (String) f[1], f[2]);
        Student s = new Student(); s.setId(1L); s.setCollegeName("C1"); s.setDepartmentName("CS"); s.setRollNo("R1"); s.setApproved(true); s.setActive(true); students.add(s);
        Exam d2 = payload("Sneaky"); d2.setDepartmentName("CS"); d2.setCollegeName("C1");
        ExamValidator.prepareForSave(d2, true, "q"); d2.setVisibleToStudents(true);   // even if visibility were somehow true...
        er.save(d2);
        try { as.startOrResume(d2.getId(), 1L, true); check("draft start must be refused", false); }
        catch (SubmissionRejectedException e) { check("draft start refused -> " + e.getHttpStatus() + " \"" + e.getMessage() + "\"", e.getHttpStatus() == 403); }
        d2.setStatus("PUBLISHED"); d2.setStartTime(LocalDateTime.now().minusMinutes(5)); d2.setEndTime(LocalDateTime.now().plusHours(1));
        check("same exam can be started once published", as.startOrResume(d2.getId(), 1L, true) != null);

        System.out.println("\nRESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
