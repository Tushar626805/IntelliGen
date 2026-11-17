package com.example.intern.service;

import com.example.intern.model.Exam;
import com.example.intern.model.Notification;
import com.example.intern.model.Result;
import com.example.intern.model.Student;
import com.example.intern.repository.NotificationRepository;
import com.example.intern.repository.ResultRepository;
import com.example.intern.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * In-app notifications. Creating a notification must NEVER break the action that triggers it
 * (publishing an exam, grading a submission), so every producer method swallows its own failures.
 */
@Service
public class NotificationService {

    public static final String STUDENT = "STUDENT";
    public static final String STAFF = "STAFF";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ResultRepository resultRepository;

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    public void notifyUser(String recipientType, Long recipientId, String kind, String title, String message, String link) {
        Notification n = new Notification();
        n.setRecipientType(recipientType);
        n.setRecipientId(recipientId);
        n.setKind(kind);
        n.setTitle(clip(title, 160));
        n.setMessage(clip(message, 500));
        n.setLink(link);
        notificationRepository.save(n);
    }

    /** Tells every active student of the exam's college, department and semester that it is available. */
    public int examPublished(Exam exam) {
        try {
            if (exam.getCollegeName() == null || exam.getDepartmentName() == null) return 0;
            StringBuilder msg = new StringBuilder(exam.getTitle() == null ? "A new exam" : exam.getTitle());
            if (exam.getSubjectName() != null && !exam.getSubjectName().isBlank()) msg.append(" (").append(exam.getSubjectName()).append(")");
            if (exam.getStartTime() != null) msg.append(" opens ").append(exam.getStartTime().format(WHEN));
            if (exam.getEndTime() != null) msg.append(exam.getStartTime() != null ? " and" : "").append(" closes ").append(exam.getEndTime().format(WHEN));
            msg.append(".");
            int sent = 0;
            for (Student s : studentRepository.findByCollegeNameAndDepartmentNameAndIsActiveTrue(exam.getCollegeName(), exam.getDepartmentName())) {
                if (!s.isApproved()) continue;
                if (exam.getTargetSemester() != null && !exam.getTargetSemester().equals(s.getCurrentSemester())) continue;
                notifyUser(STUDENT, s.getId(), "EXAM_PUBLISHED", "New exam available", msg.toString(), "student-exam.html");
                sent++;
            }
            return sent;
        } catch (Exception e) {
            System.err.println("Could not create exam notifications: " + e.getMessage());
            return 0;
        }
    }

    /** Tells every student who SUBMITTED the exam that the correct answers can now be reviewed. */
    public int answersReleased(Exam exam) {
        try {
            String msg = (exam.getTitle() == null ? "Your exam" : exam.getTitle())
                    + ": the correct answers have been released. Open My Results to compare them with your answers.";
            int sent = 0;
            for (Result r : resultRepository.findByExamId(exam.getId())) {
                if (r.getStudentId() == null) continue;
                notifyUser(STUDENT, r.getStudentId(), "ANSWERS_RELEASED", "Answers Released", msg, "student-exam.html");
                sent++;
            }
            return sent;
        } catch (Exception e) {
            System.err.println("Could not create answer-release notifications: " + e.getMessage());
            return 0;
        }
    }

    public void resultRecorded(Student student, Exam exam, Result result, boolean autoSubmitted) {
        try {
            int total = result.getTotalMarks();
            int pct = total > 0 ? Math.round(result.getMarksObtained() * 100f / total) : 0;
            String msg = exam.getTitle() + ": you scored " + result.getMarksObtained() + "/" + total + " (" + pct + "%).";
            if (autoSubmitted) msg += " Your saved answers were submitted automatically when time ran out.";
            notifyUser(STUDENT, student.getId(), autoSubmitted ? "AUTO_SUBMITTED" : "RESULT", "Your result is available", msg, "student-exam.html");
        } catch (Exception e) {
            System.err.println("Could not create result notification: " + e.getMessage());
        }
    }

    public void aiFinished(Long staffId, int generated) {
        try {
            String msg = generated > 0
                    ? generated + " question" + (generated == 1 ? " is" : "s are") + " ready for your review in the exam editor."
                    : "The generation finished. Open the exam editor to see the result.";
            notifyUser(STAFF, staffId, "AI_DONE", "Question generation finished", msg, null);
        } catch (Exception e) {
            System.err.println("Could not create AI notification: " + e.getMessage());
        }
    }

    public List<Notification> latest(String recipientType, Long recipientId) {
        return notificationRepository.findTop30ByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(recipientType, recipientId);
    }

    public long unreadCount(String recipientType, Long recipientId) {
        return notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(recipientType, recipientId);
    }

    /** Marks one notification read, but only if it belongs to the caller. */
    @Transactional
    public boolean markRead(Long id, String recipientType, Long recipientId) {
        Optional<Notification> found = notificationRepository.findById(id);
        if (found.isEmpty()) return false;
        Notification n = found.get();
        if (!recipientType.equals(n.getRecipientType()) || !recipientId.equals(n.getRecipientId())) return false;
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            notificationRepository.save(n);
        }
        return true;
    }

    @Transactional
    public int markAllRead(String recipientType, Long recipientId) {
        List<Notification> unread = notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNull(recipientType, recipientId);
        LocalDateTime now = LocalDateTime.now();
        for (Notification n : unread) {
            n.setReadAt(now);
            notificationRepository.save(n);
        }
        return unread.size();
    }

    @Transactional
    public void purgeOlderThanDays(int days) {
        notificationRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(days));
    }
}
