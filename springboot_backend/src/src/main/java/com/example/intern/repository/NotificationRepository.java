package com.example.intern.repository;

import com.example.intern.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop30ByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(String recipientType, Long recipientId);

    long countByRecipientTypeAndRecipientIdAndReadAtIsNull(String recipientType, Long recipientId);

    List<Notification> findByRecipientTypeAndRecipientIdAndReadAtIsNull(String recipientType, Long recipientId);

    void deleteByCreatedAtBefore(LocalDateTime cutoff);
}
