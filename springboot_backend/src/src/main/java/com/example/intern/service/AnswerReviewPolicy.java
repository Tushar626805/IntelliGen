package com.example.intern.service;

import java.time.LocalDateTime;

/**
 * Rules for the student "review answers" feature. Kept free of Spring/JPA types so it can be unit-tested on its own.
 *
 * <p>Release is a TEACHER decision ({@code Exam.answersReleasedAt}). A teacher can only release once nobody can still be
 * answering: the exam window has closed AND the short submission grace period (students may submit a little after their
 * deadline) has passed. Otherwise a student could read the key and then submit changed answers.
 */
public final class AnswerReviewPolicy {
    private AnswerReviewPolicy() { }

    /** The earliest moment a teacher may release the answers, or null if the exam has no end time. */
    public static LocalDateTime releasableFrom(LocalDateTime examEndTime, long graceSeconds) {
        return examEndTime == null ? null : examEndTime.plusSeconds(Math.max(0L, graceSeconds));
    }

    /** True once the exam window and the submission grace period are over, i.e. a teacher is allowed to release. */
    public static boolean canRelease(LocalDateTime examEndTime, LocalDateTime now, long graceSeconds) {
        LocalDateTime from = releasableFrom(examEndTime, graceSeconds);
        return from != null && now != null && !now.isBefore(from);
    }

    /** True if a teacher has released the answers (a release time exists). No clock comparison, so rounding cannot hide them. */
    public static boolean isReleased(LocalDateTime releasedAt) {
        return releasedAt != null;
    }

    /**
     * The grading comparison: trimmed, case-insensitive match against the stored correct answer.
     * ExamService grading and the review screen both call this so they can never disagree.
     */
    public static boolean sameAnswer(String correctAnswer, String givenAnswer) {
        return correctAnswer != null && givenAnswer != null
                && correctAnswer.trim().equalsIgnoreCase(givenAnswer.trim());
    }
}
