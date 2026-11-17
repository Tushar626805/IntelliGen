package com.example.intern.service;

import com.example.intern.model.Exam;
import com.example.intern.model.Question;

/** Server-side sanity checks for exams a teacher/HOD is about to publish. */
public final class ExamValidator {

    private ExamValidator() { }

    /** @return a human-readable problem, or null when the exam is acceptable. */
    public static String firstProblem(Exam exam) {
        if (exam.getTitle() == null || exam.getTitle().isBlank()) {
            return "Exam title is required.";
        }
        if (exam.getQuestions() == null) {
            return null;
        }
        int n = 0;
        for (Question q : exam.getQuestions()) {
            n++;
            if (q.getOptions() == null || q.getOptions().size() < 2) {
                return "Question " + n + " needs at least two options.";
            }
            for (String option : q.getOptions()) {
                if (option != null && option.length() > 255) {
                    return "Question " + n + " has an option longer than 255 characters.";
                }
            }
            String answer = q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer().trim();
            boolean matches = q.getOptions().stream()
                    .anyMatch(o -> o != null && o.trim().equalsIgnoreCase(answer));
            if (!matches) {
                // Grading compares the chosen option's text to this value, so a non-matching
                // answer would make the question impossible to answer correctly.
                return "Question " + n + ": the correct answer must match one of its options exactly.";
            }
        }
        return null;
    }

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /**
     * Normalises an exam coming from the browser before it is stored: new ids, the correct status,
     * drafts hidden from students, and sane question defaults. Draft = saved but NOT visible to students
     * until a teacher explicitly publishes it.
     */
    public static void prepareForSave(Exam exam, boolean draft, String defaultQuestionText) {
        exam.setId(null);
        exam.setStatus(draft ? STATUS_DRAFT : STATUS_PUBLISHED);
        if (draft) {
            exam.setVisibleToStudents(false);
        }
        if (exam.getQuestions() != null) {
            for (Question q : exam.getQuestions()) {
                q.setId(null);
                q.setExam(exam);
                if (q.getQuestionText() == null || q.getQuestionText().isBlank()) {
                    q.setQuestionText(defaultQuestionText);
                }
                Integer marks = q.getMarks();
                if (marks == null || marks <= 0) {
                    q.setMarks(1);
                }
                if (q.getSourceExcerpt() != null && q.getSourceExcerpt().length() > 600) {
                    q.setSourceExcerpt(q.getSourceExcerpt().substring(0, 600));
                }
            }
        }
    }
}
