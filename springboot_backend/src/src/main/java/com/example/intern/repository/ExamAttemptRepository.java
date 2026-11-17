package com.example.intern.repository;

import com.example.intern.model.ExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {

    Optional<ExamAttempt> findByExamIdAndStudentId(Long examId, Long studentId);

    /** Attempts still open although their deadline is long past (student closed the browser, lost power...). */
    List<ExamAttempt> findByStatusAndDeadlineBefore(String status, LocalDateTime cutoff);

    void deleteByExamId(Long examId);
}
