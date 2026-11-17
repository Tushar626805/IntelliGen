package com.example.intern.controller;

import com.example.intern.model.*;
import com.example.intern.repository.*;
import com.example.intern.security.CurrentUser;
import com.example.intern.security.RateLimiter;
import com.example.intern.security.SessionUser;
import com.example.intern.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exam endpoints. URL-level role rules live in SecurityConfig; this class additionally enforces
 * that every action uses the LOGGED-IN user's identity and stays inside their college / ownership.
 * Client-supplied identity parameters (rollNo, studentName, username, X-College-Name) are ignored.
 */
@RestController
@RequestMapping("/api/exam")
public class ExamController {

    private static final long TEN_MINUTES = 10 * 60 * 1000L;

    @Autowired private FileStorageService fileStorageService;
    @Autowired private PdfProcessingService pdfProcessingService;
    @Autowired private MlIntegrationService mlIntegrationService;
    @Autowired private ExamRepository examRepository;
    @Autowired private ResultRepository resultRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ExamArchiveRepository archiveRepository;
    @Autowired private ExamService examService;
    @Autowired private QuestionResultRepository questionResultRepository;
    @Autowired private PdfGeneratorService pdfGeneratorService;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private AttemptService attemptService;
    @Autowired private NotificationService notificationService;
    @Autowired private ExamAttemptRepository attemptRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Striped locks: serialize concurrent submissions by the same student for the same exam
    private static final Object[] SUBMIT_LOCKS = new Object[64];
    static { for (int i = 0; i < SUBMIT_LOCKS.length; i++) SUBMIT_LOCKS[i] = new Object(); }

    private static Object lockFor(Long examId, Long studentId) {
        return SUBMIT_LOCKS[Math.floorMod(Objects.hash(examId, studentId), SUBMIT_LOCKS.length)];
    }

    // ==========================================
    // Helpers
    // ==========================================
    private static String errorJson(String message) {
        String safe = message == null ? "Unknown error" : message
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
        return "{\"error\": \"" + safe + "\"}";
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
    }

    private Student currentStudent() {
        return studentRepository.findById(CurrentUser.get().id())
                .orElseThrow(() -> new RuntimeException("Student account not found."));
    }

    /** Staff may see an exam they created, or exams of their branch (HOD/teacher) / college (principal). */
    private boolean canViewExam(Exam e) {
        SessionUser me = CurrentUser.get();
        boolean owner = me.username() != null && me.username().equals(e.getCreatedByTeacherUsername());
        if (e.getCollegeName() == null) {
            return owner; // legacy exams without a college are only visible to their creator
        }
        if (!CurrentUser.sameCollege(e.getCollegeName())) {
            return false;
        }
        if ("PRINCIPAL".equals(me.role())) return true;
        return owner || CurrentUser.sameBranch(e.getDepartmentName());
    }

    /** Managing (visibility, deleting) is narrower: creator, HOD of that branch, or the college principal. */
    private boolean canManageExam(Exam e) {
        SessionUser me = CurrentUser.get();
        boolean owner = me.username() != null && me.username().equals(e.getCreatedByTeacherUsername());
        if (e.getCollegeName() == null) {
            return owner;
        }
        if (!CurrentUser.sameCollege(e.getCollegeName())) {
            return false;
        }
        if ("PRINCIPAL".equals(me.role())) return true;
        if ("HOD".equals(me.role()) && CurrentUser.sameBranch(e.getDepartmentName())) return true;
        return owner;
    }

    private boolean canAccessArchive(ExamArchive a) {
        SessionUser me = CurrentUser.get();
        if (me.username() != null && me.username().equals(a.getArchivedByUsername())) return true;
        Optional<Teacher> archiver = teacherRepository.findByUsername(a.getArchivedByUsername());
        if (archiver.isEmpty() || !CurrentUser.sameCollege(archiver.get().getCollegeName())) return false;
        if ("PRINCIPAL".equals(me.role())) return true;
        return "HOD".equals(me.role()) && CurrentUser.sameBranch(archiver.get().getBranchName());
    }

    /** Archive list item without the (large) PDF bytes. */
    private Map<String, Object> archiveSummary(ExamArchive a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("examTitle", a.getExamTitle());
        m.put("archivedByUsername", a.getArchivedByUsername());
        m.put("archivedAt", a.getArchivedAt());
        return m;
    }

    /** Re-verifies the logged-in staff member's password before sensitive actions. Returns an error response or null. */
    private ResponseEntity<?> verifyPassword(Map<String, String> payload, String action) {
        SessionUser me = CurrentUser.get();
        String rawPassword = payload == null ? null : payload.get("password");
        if (rawPassword == null || rawPassword.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Password is required."));
        }
        if (!rateLimiter.allow(action + ":" + me.id(), 5, TEN_MINUTES)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many password attempts. Please wait a few minutes."));
        }
        Teacher teacher = teacherRepository.findById(me.id()).orElse(null);
        if (teacher == null || !passwordEncoder.matches(rawPassword, teacher.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Incorrect password."));
        }
        return null;
    }

    // ==========================================
    // 1. CREATION & AI GENERATION
    // ==========================================
    @PostMapping("/teacher/upload-notes")
    public ResponseEntity<String> uploadNotesAndGenerateExam(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(value = "num", defaultValue = "5") int num,
                                                             @RequestParam(value = "difficulty", defaultValue = "medium") String difficulty) {
        String level = difficulty == null ? "medium" : difficulty.trim().toLowerCase();
        if (!level.equals("easy") && !level.equals("medium") && !level.equals("hard")) {
            return ResponseEntity.badRequest().body(errorJson("Difficulty must be easy, medium or hard."));
        }
        String uploadProblem = UploadValidator.firstProblem(file, num);
        if (uploadProblem != null) {
            return ResponseEntity.badRequest().body(errorJson(uploadProblem));
        }

        try {
            String savedFilePath = fileStorageService.saveTeacherNotes(file);
            String text = pdfProcessingService.extractTextFromPdf(savedFilePath);

            if (text == null || text.trim().length() < 100) {
                return ResponseEntity.badRequest().body(errorJson("The uploaded PDF does not contain enough readable digital text."));
            }

            String aiResponse = mlIntegrationService.generateQuestionsFromText(text, num, level);

            // Strip Markdown code fences if the LLM wrapped its JSON in them
            if (aiResponse != null) {
                aiResponse = aiResponse.trim();
                if (aiResponse.startsWith("```json")) {
                    aiResponse = aiResponse.substring(7);
                }
                if (aiResponse.endsWith("```")) {
                    aiResponse = aiResponse.substring(0, aiResponse.length() - 3);
                }
                aiResponse = aiResponse.trim();
            }

            return ResponseEntity.ok().header("Content-Type", "application/json").body(aiResponse);
        } catch (AiServiceException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(errorJson(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(errorJson("Question generation failed: " + SafeErrors.message(e)));
        }
    }

    /**
     * Saves an exam. draft=true stores it as a DRAFT that students cannot see until it is published.
     * replaces=<id> atomically swaps an existing DRAFT of the caller for this edited version.
     */
    @Transactional
    @PostMapping("/teacher/save")
    public ResponseEntity<?> saveExamToDatabase(@RequestBody Exam exam,
                                                @RequestParam(value = "username", required = false) String ignoredClientUsername,
                                                @RequestParam(value = "draft", defaultValue = "false") boolean draft,
                                                @RequestParam(value = "replaces", required = false) Long replaces) {
        try {
            SessionUser me = CurrentUser.get();

            String problem = ExamValidator.firstProblem(exam);
            if (problem != null) {
                return ResponseEntity.badRequest().body("Error: " + problem);
            }

            Exam oldDraft = null;
            if (replaces != null) {
                oldDraft = examRepository.findById(replaces).orElse(null);
                if (oldDraft == null) {
                    return ResponseEntity.status(404).body("Error: The draft you are editing no longer exists.");
                }
                if (!ExamValidator.STATUS_DRAFT.equals(oldDraft.getStatus())) {
                    return ResponseEntity.status(409).body("Error: Only drafts can be edited. Published exams cannot be changed.");
                }
                if (!canManageExam(oldDraft)) {
                    return ResponseEntity.status(403).body("Error: You cannot edit this draft.");
                }
            }

            ExamValidator.prepareForSave(exam, draft, "Generated Question");

            // Creator and college always come from the session, never from the request body.
            // When a draft is edited by someone who manages it (HOD/principal), the original author is kept.
            exam.setCreatedByTeacherUsername(oldDraft != null && oldDraft.getCreatedByTeacherUsername() != null
                    ? oldDraft.getCreatedByTeacherUsername() : me.username());
            exam.setCollegeName(me.collegeName());

            // Teachers and HODs can only set exams for their own branch. Principals choose the department.
            if ("TEACHER".equals(me.role()) || "HOD".equals(me.role())) {
                exam.setDepartmentName(me.branchName());
            } else if (exam.getDepartmentName() == null || exam.getDepartmentName().isBlank()) {
                return ResponseEntity.badRequest().body("Error: Choose the department this exam is for.");
            }

            examRepository.save(exam);
            if (oldDraft != null) {
                examRepository.deleteById(oldDraft.getId());
            }
            if (!draft) {
                notificationService.examPublished(exam);
            }
            return ResponseEntity.ok((draft ? "Draft saved with ID: " : "Exam successfully saved with ID: ") + exam.getId());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error saving to database: " + SafeErrors.message(e));
        }
    }

    /** Publishing is the teacher's explicit approval: the draft becomes visible to students. */
    @PutMapping("/teacher/{examId}/publish")
    public ResponseEntity<?> publishDraft(@PathVariable Long examId) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
        if (!canManageExam(exam)) return forbidden();
        if (!ExamValidator.STATUS_DRAFT.equals(exam.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", "This exam is already published."));
        }
        if (exam.getQuestions() == null || exam.getQuestions().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Add at least one question before publishing."));
        }
        String problem = ExamValidator.firstProblem(exam);
        if (problem != null) {
            return ResponseEntity.badRequest().body(Map.of("error", problem));
        }
        if (exam.getSubjectName() == null || exam.getSubjectName().isBlank() || exam.getTargetSemester() == null
                || exam.getStartTime() == null || exam.getEndTime() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Set the subject, semester, start time and end time (Edit the draft) before publishing."));
        }
        if (!exam.getEndTime().isAfter(exam.getStartTime())) {
            return ResponseEntity.badRequest().body(Map.of("error", "The end time must be after the start time."));
        }
        exam.setStatus(ExamValidator.STATUS_PUBLISHED);
        exam.setVisibleToStudents(true);
        examRepository.save(exam);
        notificationService.examPublished(exam);
        return ResponseEntity.ok(Map.of("message", "Exam published. Students can now see it."));
    }

    /** Full draft (with questions and answers) for the person who manages it, so it can be reopened in the editor. */
    @GetMapping("/teacher/{examId}/draft")
    public ResponseEntity<?> getDraft(@PathVariable Long examId) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
        if (!canManageExam(exam)) return forbidden();
        if (!ExamValidator.STATUS_DRAFT.equals(exam.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", "Only drafts can be reopened for editing."));
        }
        return ResponseEntity.ok(exam);
    }

    @DeleteMapping("/teacher/{examId}/draft")
    public ResponseEntity<?> deleteDraft(@PathVariable Long examId) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
        if (!canManageExam(exam)) return forbidden();
        if (!ExamValidator.STATUS_DRAFT.equals(exam.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", "Published exams must be archived, not deleted."));
        }
        examRepository.deleteById(examId);
        return ResponseEntity.ok(Map.of("message", "Draft deleted."));
    }

    /**
     * Questions from exams the caller may view (own exams, own branch, or whole college for principals),
     * newest first, one entry per distinct question text. Used to reuse questions in a new exam.
     */
    @GetMapping("/teacher/question-bank")
    public ResponseEntity<?> questionBank(@RequestParam(value = "q", required = false) String q,
                                          @RequestParam(value = "subject", required = false) String subject) {
        SessionUser me = CurrentUser.get();
        Map<Long, Exam> visible = new LinkedHashMap<>();
        if (me.collegeName() != null) {
            for (Exam e : examRepository.findByCollegeName(me.collegeName())) visible.put(e.getId(), e);
        }
        for (Exam e : examRepository.findByCreatedByTeacherUsername(me.username())) visible.put(e.getId(), e);

        List<Exam> exams = visible.values().stream().filter(this::canViewExam)
                .sorted(Comparator.comparing(Exam::getId, Comparator.reverseOrder())).collect(Collectors.toList());
        Set<String> subjects = new TreeSet<>();
        for (Exam e : exams) {
            if (e.getSubjectName() != null && !e.getSubjectName().isBlank()) subjects.add(e.getSubjectName());
        }

        String needle = q == null ? "" : q.trim().toLowerCase();
        boolean filterSubject = subject != null && !subject.isBlank();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Exam e : exams) {
            if (filterSubject && !subject.equalsIgnoreCase(e.getSubjectName())) continue;
            if (e.getQuestions() == null) continue;
            for (Question qu : e.getQuestions()) {
                if (qu.getQuestionText() == null) continue;
                String text = qu.getQuestionText().trim();
                if (!seen.add(text.toLowerCase().replaceAll("\\s+", " "))) continue;
                if (!needle.isEmpty() && !(text.toLowerCase().contains(needle)
                        || (e.getTitle() != null && e.getTitle().toLowerCase().contains(needle)))) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("questionText", text);
                m.put("options", qu.getOptions());
                m.put("correctAnswer", qu.getCorrectAnswer());
                m.put("marks", qu.getMarks());
                m.put("aiGenerated", qu.getAiGenerated());
                m.put("subjectName", e.getSubjectName());
                m.put("examTitle", e.getTitle());
                out.add(m);
                if (out.size() >= 200) break;
            }
            if (out.size() >= 200) break;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("subjects", subjects);
        body.put("questions", out);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/create")
    public ResponseEntity<?> createExam(@RequestBody Map<String, Object> payload) {
        try {
            SessionUser me = CurrentUser.get();
            Exam exam = new Exam();
            exam.setTitle((String) payload.get("title"));
            exam.setDepartmentName((String) payload.get("departmentName"));

            if (exam.getTitle() == null || exam.getTitle().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Exam title is required."));
            }
            if (payload.get("durationMinutes") != null) {
                exam.setDurationMinutes(Integer.valueOf(payload.get("durationMinutes").toString()));
            }
            if (payload.get("targetSemester") != null) {
                exam.setTargetSemester(Integer.valueOf(payload.get("targetSemester").toString()));
            }

            exam.setCreatedByTeacherUsername(me.username());
            exam.setCollegeName(me.collegeName());
            if ("TEACHER".equals(me.role()) || "HOD".equals(me.role())) {
                exam.setDepartmentName(me.branchName());
            }

            examRepository.save(exam);
            return ResponseEntity.ok(Map.of("message", "Exam created successfully!", "id", exam.getId()));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Duration and semester must be numbers."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Database error: " + SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 2. EXAM VISIBILITY & TARGETING
    // ==========================================
    @PutMapping("/teacher/{examId}/toggle-visibility")
    public ResponseEntity<?> toggleExamVisibility(@PathVariable Long examId) {
        try {
            Exam exam = examRepository.findById(examId).orElse(null);
            if (exam == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
            }
            if (!canManageExam(exam)) return forbidden();
            if (ExamValidator.STATUS_DRAFT.equals(exam.getStatus())) {
                return ResponseEntity.status(409).body(Map.of("error", "Publish the draft first. Drafts are never visible to students."));
            }

            exam.setVisibleToStudents(!exam.isVisibleToStudents());
            examRepository.save(exam);

            String status = exam.isVisibleToStudents() ? "Visible to Students" : "Hidden from Students";
            return ResponseEntity.ok(Map.of("message", "Exam is now " + status, "isVisible", exam.isVisibleToStudents()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @GetMapping("/student/available")
    public ResponseEntity<?> getAvailableExamsForStudent(@RequestParam(required = false) String rollNo) {
        try {
            Student student = currentStudent();

            if (student.getCurrentSemester() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Your student profile is missing a semester/year assignment."));
            }

            // Fetch ONLY visible exams meant for this exact department and semester
            List<Exam> targetedExams = examRepository.findByDepartmentNameAndTargetSemesterAndIsVisibleToStudentsTrue(
                    student.getDepartmentName(), student.getCurrentSemester());

            // Ensure the student only sees exams of their own college; never send questions or answer keys
            List<Exam> isolatedExams = targetedExams.stream()
                    .filter(exam -> student.getCollegeName() != null && student.getCollegeName().equalsIgnoreCase(exam.getCollegeName()))
                    .map(exam -> {
                        Exam copy = exam.summaryCopy();
                        copy.setCreatedByTeacherUsername(null);
                        return copy;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(isolatedExams);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 3. STUDENT TAKING EXAMS
    // ==========================================
    @GetMapping("/student/missed")
    public ResponseEntity<?> getMissedExamsForStudent(@RequestParam(required = false) String rollNo) {
        try {
            Student student = currentStudent();

            if (student.getCurrentSemester() == null || student.getDepartmentName() == null || student.getCollegeName() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Student profile is missing college, department, or semester information."));
            }

            LocalDateTime now = LocalDateTime.now();
            List<Exam> targetedExams = examRepository.findByDepartmentNameAndTargetSemesterAndIsVisibleToStudentsTrue(
                    student.getDepartmentName(), student.getCurrentSemester());

            List<Exam> missedExams = targetedExams.stream()
                    .filter(exam -> student.getCollegeName().equalsIgnoreCase(exam.getCollegeName()))
                    .filter(exam -> exam.getEndTime() != null && now.isAfter(exam.getEndTime()))
                    .filter(exam -> !resultRepository.existsByExamIdAndStudentId(exam.getId(), student.getId()))
                    .map(exam -> {
                        Exam copy = exam.summaryCopy();
                        copy.setCreatedByTeacherUsername(null);
                        return copy;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(missedExams);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    /**
     * Starts the exam (the server records the start time and fixes the deadline) or resumes the existing
     * attempt with its ORIGINAL deadline and saved answers. POST because it changes state: a link or image on
     * another site must not be able to start a student's timer.
     */
    @PostMapping("/student/start/{examId}")
    public ResponseEntity<?> startExam(@PathVariable Long examId) {
        return openPaper(examId, true);
    }

    /** Resume-only variant (never creates an attempt). Kept for compatibility with the old URL. */
    @GetMapping("/student/take/{examId}")
    public ResponseEntity<?> getExamForStudent(@PathVariable Long examId, @RequestParam(required = false) String rollNo) {
        return openPaper(examId, false);
    }

    private ResponseEntity<?> openPaper(Long examId, boolean allowCreate) {
        Long studentId = CurrentUser.get().id();
        try {
            ExamAttempt attempt;
            synchronized (lockFor(examId, studentId)) {
                attempt = attemptService.startOrResume(examId, studentId, allowCreate);
            }
            if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
                return ResponseEntity.status(403).body(Map.of("error",
                        "Your time ran out. The answers you had saved were submitted automatically."));
            }
            Exam exam = examRepository.findById(examId)
                    .orElseThrow(() -> new SubmissionRejectedException(404, "Exam not found."));
            return ResponseEntity.ok(buildPaper(exam, attempt));
        } catch (SubmissionRejectedException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Could not open the exam. Please try again."));
        }
    }

    /** The paper WITHOUT answer keys, plus the server-side timing and any saved progress. */
    private Map<String, Object> buildPaper(Exam exam, ExamAttempt attempt) {
        LocalDateTime now = LocalDateTime.now();

        Map<String, Object> paper = new HashMap<>();
        paper.put("id", exam.getId());
        paper.put("title", exam.getTitle());
        paper.put("subjectName", exam.getSubjectName());
        paper.put("departmentName", exam.getDepartmentName());
        paper.put("durationMinutes", exam.getDurationMinutes());
        paper.put("startTime", exam.getStartTime());
        paper.put("endTime", exam.getEndTime());

        if (exam.getQuestions() != null) {
            // Shuffled, but with a seed fixed per attempt so the order is identical after a refresh
            List<Question> ordered = new ArrayList<>(exam.getQuestions());
            ordered.sort(Comparator.comparing(Question::getId));
            Collections.shuffle(ordered, new Random(attempt.getId() * 31L + exam.getId()));

            List<Map<String, Object>> safeQuestions = ordered.stream().map(q -> {
                Map<String, Object> safeQ = new HashMap<>();
                safeQ.put("id", q.getId());
                safeQ.put("questionText", q.getQuestionText());
                safeQ.put("options", q.getOptions());
                safeQ.put("marks", q.getMarks());
                return safeQ;
            }).collect(Collectors.toList());
            paper.put("questions", safeQuestions);
        }

        Map<String, String> savedAnswers = new HashMap<>();
        attempt.getDraftAnswers().forEach((qid, ans) -> savedAnswers.put(String.valueOf(qid), ans));

        Map<String, Object> timing = new HashMap<>();
        timing.put("startedAt", attempt.getStartedAt());
        timing.put("deadline", attempt.getDeadline());
        timing.put("serverNow", now);
        timing.put("remainingSeconds", Math.max(0, Duration.between(now, attempt.getDeadline()).getSeconds()));
        timing.put("seq", attempt.getLastSeq());
        timing.put("answers", savedAnswers);
        timing.put("flagged", new ArrayList<>(attempt.getFlaggedQuestions()));
        paper.put("attempt", timing);
        return paper;
    }

    /** Stores a snapshot of the student's current answers/flags. Body: {seq, answers:{qid:text}, flagged:[qid]}. */
    @PutMapping("/student/autosave/{examId}")
    public ResponseEntity<?> autosave(@PathVariable Long examId, @RequestBody Map<String, Object> body) {
        Long studentId = CurrentUser.get().id();
        try {
            long seq = body.get("seq") instanceof Number n ? n.longValue() : 0L;

            Map<String, String> answers = new HashMap<>();
            if (body.get("answers") instanceof Map<?, ?> raw) {
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    if (e.getKey() != null && e.getValue() instanceof String text) {
                        answers.put(String.valueOf(e.getKey()), text);
                    }
                }
            }
            List<Long> flagged = new ArrayList<>();
            if (body.get("flagged") instanceof List<?> rawFlags) {
                for (Object o : rawFlags) {
                    if (o instanceof Number n2) flagged.add(n2.longValue());
                }
            }

            ExamAttempt attempt;
            synchronized (lockFor(examId, studentId)) {
                attempt = attemptService.autosave(examId, studentId, seq, answers, flagged);
            }

            Map<String, Object> out = new HashMap<>();
            out.put("savedAt", String.valueOf(attempt.getLastSavedAt()));
            out.put("seq", attempt.getLastSeq());
            out.put("remainingSeconds", Math.max(0, Duration.between(LocalDateTime.now(), attempt.getDeadline()).getSeconds()));
            return ResponseEntity.ok(out);
        } catch (SubmissionRejectedException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Could not save your answers."));
        }
    }

    @PostMapping("/student/submit/{examId}")
    public ResponseEntity<?> submitStudentExam(
            @PathVariable Long examId,
            @RequestParam(required = false) String studentName,
            @RequestParam(required = false) String rollNo,
            @RequestBody Map<String, String> studentAnswers
    ) {
        Long studentId = CurrentUser.get().id();
        Object lock = lockFor(examId, studentId);
        try {
            Result result;
            synchronized (lock) {
                result = examService.evaluateExam(examId, studentId, studentAnswers);
            }
            return ResponseEntity.ok("Score saved: " + result.getMarksObtained() + "/" + result.getTotalMarks());
        } catch (SubmissionRejectedException e) {
            return ResponseEntity.status(e.getHttpStatus()).body("Error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error: Your submission could not be saved. Please try again.");
        }
    }

    // ==========================================
    // 4. ANALYTICS & RESULTS FETCHING
    // ==========================================
    /** Exams in the caller's college (plus any legacy exams they created). Questions/answer keys are not included. */
    @GetMapping("/all")
    public ResponseEntity<List<Exam>> getAllExams(@RequestHeader(value = "X-College-Name", required = false) String ignoredClientCollege) {
        SessionUser me = CurrentUser.get();
        Map<Long, Exam> visible = new LinkedHashMap<>();
        if (me.collegeName() != null) {
            for (Exam e : examRepository.findByCollegeName(me.collegeName())) visible.put(e.getId(), e);
        }
        for (Exam e : examRepository.findByCreatedByTeacherUsername(me.username())) visible.put(e.getId(), e);

        return ResponseEntity.ok(visible.values().stream().map(Exam::summaryCopy).collect(Collectors.toList()));
    }

    @GetMapping("/results/exam/{examId}")
    public ResponseEntity<?> getResultsForExam(@PathVariable Long examId) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
        if (!canViewExam(exam)) return forbidden();

        // Lean response: student identity fields only (no password hash, no embedded exam/answer keys).
        // Both flat (studentName/rollNo) and nested (student.*) shapes are provided for the existing pages.
        List<Map<String, Object>> rows = resultRepository.findByExamId(examId).stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("examId", r.getExamId());
            m.put("studentId", r.getStudentId());
            m.put("marksObtained", r.getMarksObtained());
            m.put("totalMarks", r.getTotalMarks());
            m.put("submissionTime", r.getSubmissionTime());
            Student st = r.getStudent();
            if (st != null) {
                m.put("studentName", st.getName());
                m.put("rollNo", st.getRollNo());
                Map<String, Object> sm = new HashMap<>();
                sm.put("id", st.getId());
                sm.put("name", st.getName());
                sm.put("rollNo", st.getRollNo());
                sm.put("prnNo", st.getPrnNo());
                sm.put("departmentName", st.getDepartmentName());
                sm.put("collegeName", st.getCollegeName());
                m.put("student", sm);
            }
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{examId}/analytics")
    public ResponseEntity<?> getExamAnalytics(@PathVariable Long examId,
                                              @RequestHeader(value = "X-College-Name", required = false) String ignoredClientCollege) {
        try {
            Exam exam = examRepository.findById(examId).orElse(null);
            if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found"));
            if (!canViewExam(exam)) return forbidden();

            List<Result> submissions = resultRepository.findByExamId(examId);

            String college = exam.getCollegeName() != null ? exam.getCollegeName() : CurrentUser.get().collegeName();
            List<Student> totalEligibleStudents = studentRepository
                    .findByCollegeNameAndDepartmentNameAndIsActiveTrue(college, exam.getDepartmentName()).stream()
                    .filter(s -> exam.getTargetSemester() != null && exam.getTargetSemester().equals(s.getCurrentSemester()))
                    .collect(Collectors.toList());

            long passed = 0;
            long failed = 0;

            for (Result r : submissions) {
                double percentage = r.getTotalMarks() > 0 ? ((double) r.getMarksObtained() / r.getTotalMarks()) * 100 : 0;
                if (percentage >= 40.0) passed++; else failed++;
            }

            long absent = Math.max(0, totalEligibleStudents.size() - submissions.size());

            Map<String, Object> analytics = new HashMap<>();
            analytics.put("totalEligible", totalEligibleStudents.size());
            analytics.put("submitted", submissions.size());
            analytics.put("passed", passed);
            analytics.put("failed", failed);
            analytics.put("absent", absent);

            return ResponseEntity.ok(analytics);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    /** Current answer-release state of an exam, for the staff who can view its results. */
    @GetMapping("/{examId}/answer-release")
    public ResponseEntity<?> answerReleaseStatus(@PathVariable Long examId) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
        if (!canViewExam(exam)) return forbidden();
        return ResponseEntity.ok(answerReleaseBody(exam));
    }

    /**
     * Teacher (creator), HOD of the branch or principal releases the correct answers to students, or hides them again.
     * Releasing is refused until the exam window has closed so the answer key cannot leak to students still sitting the exam.
     * Body: {"released": true|false}
     */
    @PutMapping("/{examId}/answer-release")
    public ResponseEntity<?> setAnswerRelease(@PathVariable Long examId, @RequestBody Map<String, Object> body) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
        if (!canManageExam(exam)) return forbidden();
        Object flag = body == null ? null : body.get("released");
        if (!(flag instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Send {\"released\": true} or {\"released\": false}."));
        }
        LocalDateTime now = LocalDateTime.now();
        boolean release = (Boolean) flag;
        if (release) {
            if (!AnswerReviewPolicy.canRelease(exam.getEndTime(), now, ExamService.GRACE_SECONDS)) {
                return ResponseEntity.status(409).body(Map.of("error",
                        "Answers can be released only after the exam window has closed and the submission grace period is over."));
            }
            if (exam.getAnswersReleasedAt() == null) {          // releasing twice must not notify twice
                exam.setAnswersReleasedAt(now);
                examRepository.save(exam);
                notificationService.answersReleased(exam);
            }
        } else if (exam.getAnswersReleasedAt() != null) {
            exam.setAnswersReleasedAt(null);
            examRepository.save(exam);
        }
        return ResponseEntity.ok(answerReleaseBody(exam));
    }

    private Map<String, Object> answerReleaseBody(Exam exam) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("examId", exam.getId());
        m.put("released", AnswerReviewPolicy.isReleased(exam.getAnswersReleasedAt()));
        m.put("releasedAt", exam.getAnswersReleasedAt());
        m.put("canRelease", AnswerReviewPolicy.canRelease(exam.getEndTime(), now, ExamService.GRACE_SECONDS));
        m.put("examEndsAt", exam.getEndTime());
        m.put("releasableFrom", AnswerReviewPolicy.releasableFrom(exam.getEndTime(), ExamService.GRACE_SECONDS));
        m.put("canManage", canManageExam(exam));
        return m;
    }

    /**
     * Answer review for the logged-in student: their own answers next to the correct ones.
     * Only a student who has SUBMITTED this exam can open it, and the answer key is included only after a teacher has
     * released the answers (Exam.answersReleasedAt). Until then, the response contains no questions and no key.
     */
    @Transactional(readOnly = true)
    @GetMapping("/student/review/{examId}")
    public ResponseEntity<?> reviewAnswers(@PathVariable Long examId) {
        Long studentId = CurrentUser.get().id();
        try {
            Result result = resultRepository.findFirstByExamIdAndStudentId(examId, studentId).orElse(null);
            if (result == null) {
                return ResponseEntity.status(404).body(Map.of("error", "No submitted result was found for this exam."));
            }
            Exam exam = examRepository.findById(examId).orElse(null);
            if (exam == null) {
                return ResponseEntity.status(404).body(Map.of("error", "This exam is no longer available."));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("examId", exam.getId());
            body.put("examTitle", exam.getTitle());
            body.put("subjectName", exam.getSubjectName());
            body.put("marksObtained", result.getMarksObtained());
            body.put("totalMarks", result.getTotalMarks());

            boolean released = AnswerReviewPolicy.isReleased(exam.getAnswersReleasedAt());
            body.put("released", released);
            if (!released) {
                body.put("message", "Your teacher has not released the correct answers yet.");
                return ResponseEntity.ok(body);
            }

            ExamAttempt attempt = attemptRepository.findByExamIdAndStudentId(examId, studentId).orElse(null);
            Map<Long, String> given = attempt != null ? attempt.getDraftAnswers() : Collections.<Long, String>emptyMap();
            // Results recorded before final answers were stored have no saved answers; the UI says so honestly.
            body.put("answersRecorded", !given.isEmpty());

            List<Question> questions = exam.getQuestions() == null
                    ? new ArrayList<Question>() : new ArrayList<Question>(exam.getQuestions());
            questions.sort(Comparator.comparing(Question::getId));

            List<Map<String, Object>> items = new ArrayList<>();
            for (Question q : questions) {
                String yourAnswer = given.get(q.getId());
                boolean correct = AnswerReviewPolicy.sameAnswer(q.getCorrectAnswer(), yourAnswer);
                int marks = q.getMarks() == null ? 1 : q.getMarks();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("questionId", q.getId());
                item.put("questionText", q.getQuestionText());
                item.put("options", q.getOptions() == null ? new ArrayList<String>() : new ArrayList<String>(q.getOptions()));
                item.put("correctAnswer", q.getCorrectAnswer());
                item.put("yourAnswer", yourAnswer);
                item.put("isCorrect", correct);
                item.put("marks", marks);
                item.put("marksAwarded", correct ? marks : 0);
                items.add(item);
            }
            body.put("questions", items);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "The answer review could not be loaded. Please try again."));
        }
    }

    /** A student's own results. The name in the URL is ignored: results are looked up by the logged-in student's id. */
    @Transactional(readOnly = true)
    @GetMapping("/results/student/{studentName}")
    public ResponseEntity<?> getResultsForStudent(@PathVariable String studentName) {
        try {
            List<Result> results = resultRepository.findByStudentId(CurrentUser.get().id());

            List<Map<String, Object>> safeResults = results.stream().map(r -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", r.getId());
                map.put("marksObtained", r.getMarksObtained());
                map.put("totalMarks", r.getTotalMarks());
                map.put("submissionTime", r.getSubmissionTime());
                map.put("examId", r.getExamId());

                if (r.getExam() != null) {
                    map.put("examTitle", r.getExam().getTitle());
                    map.put("subjectName", r.getExam().getSubjectName());
                    // Tells the UI whether the answer review can be opened (no answer key is sent here)
                    map.put("answersReleased", AnswerReviewPolicy.isReleased(r.getExam().getAnswersReleasedAt()));
                } else {
                    map.put("examTitle", "Exam #" + r.getId());
                }
                return map;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(safeResults);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    // ==========================================
    // 5. SECURE ARCHIVAL & DELETION
    // ==========================================
    @Transactional
    @PostMapping("/archive-delete/{examId}")
    public ResponseEntity<?> archiveAndDelete(@PathVariable Long examId, @RequestBody Map<String, String> payload) {
        try {
            Exam exam = examRepository.findById(examId).orElse(null);
            if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
            if (!canManageExam(exam)) return forbidden();

            ResponseEntity<?> passwordProblem = verifyPassword(payload, "archive-delete");
            if (passwordProblem != null) return passwordProblem;

            byte[] pdfBytes = pdfGeneratorService.generateExamReport(examId);

            ExamArchive archive = new ExamArchive();
            archive.setExamTitle(exam.getTitle());
            archive.setArchivedByUsername(CurrentUser.get().username());
            archive.setPdfData(pdfBytes);
            archiveRepository.save(archive);

            attemptRepository.deleteByExamId(examId);
            examRepository.deleteById(examId);
            return ResponseEntity.ok(Map.of("message", "Exam securely archived and deleted."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    /** Archives the caller is allowed to see (own; branch for HOD; college for principal) - without PDF bytes. */
    @GetMapping("/all-archives")
    public ResponseEntity<?> getAllArchives(@RequestHeader(value = "X-College-Name", required = false) String ignoredClientCollege) {
        try {
            List<Map<String, Object>> archives = archiveRepository.findAll().stream()
                    .filter(this::canAccessArchive)
                    .map(this::archiveSummary)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(archives);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/retrieve-archive/{archiveId}")
    public ResponseEntity<?> retrieveArchive(@PathVariable Long archiveId, @RequestBody Map<String, String> payload) {
        try {
            ExamArchive archive = archiveRepository.findById(archiveId).orElse(null);
            if (archive == null) return ResponseEntity.status(404).body(Map.of("error", "Archive not found."));
            if (!canAccessArchive(archive)) return forbidden();

            ResponseEntity<?> passwordProblem = verifyPassword(payload, "retrieve-archive");
            if (passwordProblem != null) return passwordProblem;

            String safeName = (archive.getExamTitle() == null ? "exam" : archive.getExamTitle())
                    .replaceAll("[^A-Za-z0-9._ -]", "_");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "attachment; filename=\"" + safeName + ".pdf\"")
                    .body(archive.getPdfData());

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @GetMapping("/{examId}/hardest-questions")
    public ResponseEntity<?> getHardestQuestions(@PathVariable Long examId) {
        try {
            Exam exam = examRepository.findById(examId).orElse(null);
            if (exam == null) return ResponseEntity.status(404).body(Map.of("error", "Exam not found."));
            if (!canViewExam(exam)) return forbidden();

            List<Object[]> hardest = questionResultRepository.getHardestQuestions(examId);
            return ResponseEntity.ok(hardest);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    /** The caller's own archives. The username parameter is ignored. */
    @GetMapping("/my-archives")
    public ResponseEntity<?> getMyArchives(@RequestParam(required = false) String username) {
        List<Map<String, Object>> mine = archiveRepository.findByArchivedByUsername(CurrentUser.get().username())
                .stream().map(this::archiveSummary).collect(Collectors.toList());
        return ResponseEntity.ok(mine);
    }
}
