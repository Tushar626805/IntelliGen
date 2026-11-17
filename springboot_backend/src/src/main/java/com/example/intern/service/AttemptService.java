package com.example.intern.service;

import com.example.intern.model.Exam;
import com.example.intern.model.ExamAttempt;
import com.example.intern.model.Question;
import com.example.intern.model.Student;
import com.example.intern.repository.ExamAttemptRepository;
import com.example.intern.repository.ExamRepository;
import com.example.intern.repository.ResultRepository;
import com.example.intern.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Lifecycle of an exam attempt: start/resume, autosave, and cleanup of abandoned attempts. */
@Service
public class AttemptService {

    private static final int MAX_ANSWER_LENGTH = 1000;
    /** Abandoned attempts are auto-submitted this long after their deadline (well past the submit grace period). */
    private static final long SWEEP_AFTER_MINUTES = 5;

    @Autowired private ExamAttemptRepository attemptRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ResultRepository resultRepository;
    @Autowired private ExamService examService;

    /**
     * Starts a new attempt, or resumes the existing one with its ORIGINAL deadline.
     * If the student comes back after the deadline, the saved answers are submitted automatically and the
     * returned attempt has status SUBMITTED.
     *
     * @param allowCreate false for "resume only" callers (a plain GET must never start a timer)
     */
    @Transactional
    public ExamAttempt startOrResume(Long examId, Long studentId, boolean allowCreate) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new SubmissionRejectedException(403, "Student account not found."));
        Exam exam = examService.requireEligibleExam(examId, student);

        if (resultRepository.existsByExamIdAndStudentId(examId, studentId)) {
            throw new SubmissionRejectedException(403, "You have already completed this exam.");
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<ExamAttempt> existing = attemptRepository.findByExamIdAndStudentId(examId, studentId);
        if (existing.isPresent()) {
            ExamAttempt attempt = existing.get();
            if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
                throw new SubmissionRejectedException(403, "You have already completed this exam.");
            }
            if (now.isAfter(attempt.getDeadline().plusSeconds(ExamService.GRACE_SECONDS))) {
                examService.finalizeExpiredAttempt(attempt.getId());
                return attemptRepository.findById(attempt.getId()).orElse(attempt);
            }
            return attempt;
        }

        if (!allowCreate) {
            throw new SubmissionRejectedException(409, "Start this exam from your dashboard.");
        }
        if (ExamValidator.STATUS_DRAFT.equals(exam.getStatus())) {
            throw new SubmissionRejectedException(403, "This exam has not been published yet.");
        }
        if (!exam.isVisibleToStudents()) {
            throw new SubmissionRejectedException(403, "This exam is currently hidden by the instructor.");
        }
        if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) {
            throw new SubmissionRejectedException(403, "Exam starts at: " + exam.getStartTime());
        }
        if (exam.getEndTime() != null && !now.isBefore(exam.getEndTime())) {
            throw new SubmissionRejectedException(403, "Exam has ended!");
        }

        ExamAttempt attempt = new ExamAttempt();
        attempt.setExamId(examId);
        attempt.setStudentId(studentId);
        attempt.setStartedAt(now);
        attempt.setDeadline(ExamService.computeDeadline(exam, now));
        return attemptRepository.save(attempt);
    }

    /**
     * Stores a full snapshot of the student's current answers and flagged questions.
     * Sequence numbers make this safe against out-of-order requests: a snapshot older than the
     * last accepted one is ignored (the call still succeeds so the client does not retry forever).
     */
    @Transactional
    public ExamAttempt autosave(Long examId, Long studentId, long seq,
                                Map<String, String> answers, Collection<Long> flagged) {
        ExamAttempt attempt = attemptRepository.findByExamIdAndStudentId(examId, studentId)
                .orElseThrow(() -> new SubmissionRejectedException(409, "This exam has not been started."));

        if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
            throw new SubmissionRejectedException(409, "This exam has already been submitted.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(attempt.getDeadline().plusSeconds(ExamService.GRACE_SECONDS))) {
            throw new SubmissionRejectedException(403, "Time is up. Answers can no longer be saved.");
        }
        if (seq <= attempt.getLastSeq()) {
            return attempt; // stale snapshot, a newer one is already stored
        }

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new SubmissionRejectedException(404, "Exam not found."));

        // Only accept answers that belong to this exam's questions AND match one of that question's options
        Map<Long, Set<String>> optionsByQuestion = new HashMap<>();
        if (exam.getQuestions() != null) {
            for (Question q : exam.getQuestions()) {
                Set<String> normalized = new HashSet<>();
                if (q.getOptions() != null) {
                    for (String o : q.getOptions()) {
                        if (o != null) normalized.add(o.trim().toLowerCase());
                    }
                }
                optionsByQuestion.put(q.getId(), normalized);
            }
        }

        Map<Long, String> cleanAnswers = new HashMap<>();
        for (Map.Entry<String, String> e : answers.entrySet()) {
            Long qid;
            try {
                qid = Long.valueOf(e.getKey().trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            Set<String> allowed = optionsByQuestion.get(qid);
            String value = e.getValue();
            if (allowed == null || value == null || value.length() > MAX_ANSWER_LENGTH) continue;
            if (allowed.contains(value.trim().toLowerCase())) {
                cleanAnswers.put(qid, value);
            }
        }

        Set<Long> cleanFlags = new HashSet<>();
        for (Long id : flagged) {
            if (id != null && optionsByQuestion.containsKey(id)) cleanFlags.add(id);
        }

        attempt.getDraftAnswers().clear();
        attempt.getDraftAnswers().putAll(cleanAnswers);
        attempt.getFlaggedQuestions().clear();
        attempt.getFlaggedQuestions().addAll(cleanFlags);
        attempt.setLastSeq(seq);
        attempt.setLastSavedAt(now);
        return attemptRepository.save(attempt);
    }

    /** Auto-submits attempts abandoned long after their deadline, using whatever was autosaved. */
    public int sweepExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(SWEEP_AFTER_MINUTES);
        List<ExamAttempt> stale = attemptRepository.findByStatusAndDeadlineBefore(ExamAttempt.IN_PROGRESS, cutoff);
        int done = 0;
        for (ExamAttempt a : stale) {
            try {
                examService.finalizeExpiredAttempt(a.getId()); // each call is its own transaction
                done++;
            } catch (Exception e) {
                System.err.println("Could not auto-submit attempt " + a.getId() + ": " + e.getMessage());
            }
        }
        return done;
    }
}
