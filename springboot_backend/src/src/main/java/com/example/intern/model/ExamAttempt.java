package com.example.intern.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One student's sitting of one exam. The SERVER owns the clock: startedAt and deadline are set when the
 * student starts, so refreshing the page or changing devices cannot reset the timer. Autosaved answers and
 * flagged questions live here until the exam is submitted (or the deadline passes and they are auto-submitted).
 */
@Entity
@Table(name = "exam_attempts",
        uniqueConstraints = @UniqueConstraint(name = "uk_attempt_exam_student", columnNames = {"exam_id", "student_id"}))
public class ExamAttempt {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUBMITTED = "SUBMITTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime deadline;

    private LocalDateTime lastSavedAt;
    private LocalDateTime submittedAt;

    /** Highest autosave sequence number accepted; older (out-of-order) saves are ignored. */
    @Column(nullable = false)
    private long lastSeq = 0;

    @Column(nullable = false, length = 20)
    private String status = IN_PROGRESS;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "exam_attempt_answers", joinColumns = @JoinColumn(name = "attempt_id"))
    @MapKeyColumn(name = "question_id")
    @Column(name = "answer", length = 1000)
    private Map<Long, String> draftAnswers = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "exam_attempt_flags", joinColumns = @JoinColumn(name = "attempt_id"))
    @Column(name = "question_id")
    private Set<Long> flaggedQuestions = new HashSet<>();

    public Long getId() { return id; }
    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }
    public LocalDateTime getLastSavedAt() { return lastSavedAt; }
    public void setLastSavedAt(LocalDateTime lastSavedAt) { this.lastSavedAt = lastSavedAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<Long, String> getDraftAnswers() { return draftAnswers; }
    public void setDraftAnswers(Map<Long, String> draftAnswers) { this.draftAnswers = draftAnswers; }
    public Set<Long> getFlaggedQuestions() { return flaggedQuestions; }
    public void setFlaggedQuestions(Set<Long> flaggedQuestions) { this.flaggedQuestions = flaggedQuestions; }
}
