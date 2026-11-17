package com.example.intern.service;

import com.example.intern.model.Exam;
import com.example.intern.model.ExamAttempt;
import com.example.intern.model.Question;
import com.example.intern.model.QuestionResult;
import com.example.intern.model.Result;
import com.example.intern.model.Student;
import com.example.intern.model.Teacher;
import com.example.intern.repository.ExamAttemptRepository;
import com.example.intern.repository.ExamRepository;
import com.example.intern.repository.QuestionResultRepository;
import com.example.intern.repository.ResultRepository;
import com.example.intern.repository.StudentRepository;
import com.example.intern.repository.TeacherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ExamService {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ResultRepository resultRepository;

    @Autowired
    private TeacherRepository teacherRepository;

    @Autowired
    private QuestionResultRepository questionResultRepository;

    // 🚨 INJECTED: Required to look up the student profile for relational mapping
    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ExamAttemptRepository attemptRepository;

    @Autowired
    private NotificationService notificationService;

    // ==========================================
    // 1. SAVE / PUBLISH EXAM WITH COLLEGE DATA
    // ==========================================
    public Exam saveExam(Exam examPayload, String username) {
        Exam exam = new Exam();
        exam.setTitle(examPayload.getTitle());
        exam.setDepartmentName(examPayload.getDepartmentName());
        exam.setSubjectName(examPayload.getSubjectName());
        exam.setTargetSemester(examPayload.getTargetSemester());
        exam.setDurationMinutes(examPayload.getDurationMinutes());
        exam.setStartTime(examPayload.getStartTime());
        exam.setEndTime(examPayload.getEndTime());
        exam.setCreatedByTeacherUsername(username);
        exam.setVisibleToStudents(true); // Default visible on publish

        // Extract and map the college layout from the creating user session
        Optional<Teacher> teacherOpt = teacherRepository.findByUsername(username);
        if (teacherOpt.isPresent()) {
            exam.setCollegeName(teacherOpt.get().getCollegeName());
        } else {
            throw new RuntimeException("Unauthorized: Creating teacher account profile context not found.");
        }

        // Map and link questions back to this exam container for cascading persistence
        List<Question> questions = new ArrayList<>();
        if (examPayload.getQuestions() != null) {
            for (Question qPayload : examPayload.getQuestions()) {
                Question q = new Question();
                q.setQuestionText(qPayload.getQuestionText());
                q.setOptions(qPayload.getOptions());
                q.setCorrectAnswer(qPayload.getCorrectAnswer());
                q.setMarks(qPayload.getMarks());
                q.setExam(exam); // Establishes directional Relationship link
                questions.add(q);
            }
        }
        exam.setQuestions(questions);

        return examRepository.save(exam);
    }

    // ==========================================
    // 2. ATTEMPT-AWARE SUBMISSION & GRADING
    // ==========================================
    /** Extra seconds after the deadline for a last-second submit to arrive over a slow network. */
    public static final long GRACE_SECONDS = 120;
    private static final int DEFAULT_DURATION_MINUTES = 30;

    /** Deadline = start + duration, but never later than the exam's own end time. */
    public static LocalDateTime computeDeadline(Exam exam, LocalDateTime startedAt) {
        int minutes = (exam.getDurationMinutes() != null && exam.getDurationMinutes() > 0)
                ? exam.getDurationMinutes() : DEFAULT_DURATION_MINUTES;
        LocalDateTime deadline = startedAt.plusMinutes(minutes);
        if (exam.getEndTime() != null && exam.getEndTime().isBefore(deadline)) {
            deadline = exam.getEndTime();
        }
        return deadline;
    }

    /** College / department / account checks shared by start, resume, autosave and submit. */
    public Exam requireEligibleExam(Long examId, Student student) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new SubmissionRejectedException(404, "Exam not found."));

        if (!student.isActive() || !student.isApproved()) {
            throw new SubmissionRejectedException(403, "Your account cannot take exams.");
        }
        if (exam.getCollegeName() != null && !exam.getCollegeName().equalsIgnoreCase(student.getCollegeName())) {
            throw new SubmissionRejectedException(403, "Access denied. This exam belongs to another institution.");
        }
        if (exam.getDepartmentName() != null && !exam.getDepartmentName().equalsIgnoreCase(student.getDepartmentName())) {
            throw new SubmissionRejectedException(403, "Access denied. This exam is restricted to the " + exam.getDepartmentName() + " branch.");
        }
        return exam;
    }

    /**
     * Student-initiated submit for the AUTHENTICATED student. Requires a started attempt.
     * - On time: saved drafts are merged with the final payload (payload wins), so nothing typed is lost.
     * - Late (past deadline + grace): ONLY answers saved before the deadline count; the payload is ignored.
     * One transaction: a failure cannot leave half-saved question results behind.
     */
    @Transactional
    public Result evaluateExam(Long examId, Long studentId, Map<String, String> studentAnswers) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new SubmissionRejectedException(403, "Student account not found."));
        Exam exam = requireEligibleExam(examId, student);

        if (resultRepository.existsByExamIdAndStudentId(examId, student.getId())) {
            throw new SubmissionRejectedException(409, "You have already submitted this exam.");
        }

        ExamAttempt attempt = attemptRepository.findByExamIdAndStudentId(examId, student.getId())
                .orElseThrow(() -> new SubmissionRejectedException(409,
                        "This exam was not started. Open it from your dashboard first."));
        if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
            throw new SubmissionRejectedException(409, "You have already submitted this exam.");
        }

        Map<String, String> merged = new HashMap<>();
        attempt.getDraftAnswers().forEach((qid, ans) -> merged.put(String.valueOf(qid), ans));

        boolean late = LocalDateTime.now().isAfter(attempt.getDeadline().plusSeconds(GRACE_SECONDS));
        if (!late && studentAnswers != null) {
            merged.putAll(studentAnswers);
        }
        return gradeAndRecord(exam, student, merged, attempt, false);
    }

    /**
     * Auto-submits an attempt whose deadline has passed, using the answers saved so far.
     * Called when a student returns after the deadline and by the background sweeper.
     */
    @Transactional
    public void finalizeExpiredAttempt(Long attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
            return;
        }
        Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
        Student student = studentRepository.findById(attempt.getStudentId()).orElse(null);
        if (exam == null || student == null) {
            attemptRepository.delete(attempt); // exam or student no longer exists
            return;
        }
        if (resultRepository.existsByExamIdAndStudentId(exam.getId(), student.getId())) {
            attempt.setStatus(ExamAttempt.SUBMITTED);
            attemptRepository.save(attempt);
            return;
        }
        Map<String, String> drafts = new HashMap<>();
        attempt.getDraftAnswers().forEach((qid, ans) -> drafts.put(String.valueOf(qid), ans));
        gradeAndRecord(exam, student, drafts, attempt, true);
    }

    private Result gradeAndRecord(Exam exam, Student student, Map<String, String> answers, ExamAttempt attempt, boolean autoSubmitted) {
        int marksObtained = 0;
        int totalMarks = 0;

        if (exam.getQuestions() != null) {
            for (Question q : exam.getQuestions()) {
                totalMarks += q.getMarks();
                String submittedAnswer = answers.get(q.getId().toString());

                // Null-safe grading: option text must match the stored correct answer (case/whitespace-insensitive)
                boolean isCorrect = AnswerReviewPolicy.sameAnswer(q.getCorrectAnswer(), submittedAnswer);

                if (isCorrect) {
                    marksObtained += q.getMarks();
                }

                QuestionResult qr = new QuestionResult();
                qr.setExamId(exam.getId());
                qr.setQuestionId(q.getId());
                qr.setRollNo(student.getRollNo());
                qr.setCorrect(isCorrect);
                questionResultRepository.save(qr);
            }
        }

        // Keep the answers that were actually graded so the student can review them once the answer key is released.
        // (Previously only autosaved drafts survived, so a last-second change was lost.)
        if (exam.getQuestions() != null) {
            Map<Long, String> finalAnswers = new HashMap<>();
            for (Question q : exam.getQuestions()) {
                String given = answers.get(q.getId().toString());
                if (given != null && !given.isBlank()) {
                    finalAnswers.put(q.getId(), given.length() > 1000 ? given.substring(0, 1000) : given);
                }
            }
            attempt.getDraftAnswers().clear();
            attempt.getDraftAnswers().putAll(finalAnswers);
        }

        Result result = new Result();
        result.setExamId(exam.getId());
        result.setStudentId(student.getId());
        result.setMarksObtained(marksObtained);
        result.setTotalMarks(totalMarks);
        Result saved = resultRepository.save(result);

        attempt.setStatus(ExamAttempt.SUBMITTED);
        attempt.setSubmittedAt(LocalDateTime.now());
        attemptRepository.save(attempt);
        notificationService.resultRecorded(student, exam, saved, autoSubmitted);
        return saved;
    }
}
