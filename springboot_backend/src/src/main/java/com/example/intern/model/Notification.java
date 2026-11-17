package com.example.intern.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** An in-app notification. Student and staff ids overlap (separate tables), so the recipient type is stored too. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String recipientType;   // STUDENT or STAFF

    @Column(nullable = false)
    private Long recipientId;

    @Column(nullable = false, length = 30)
    private String kind;            // EXAM_PUBLISHED, RESULT, AUTO_SUBMITTED, AI_DONE

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 500)
    private String message;

    @Column(length = 200)
    private String link;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime readAt;

    public Long getId() { return id; }
    public String getRecipientType() { return recipientType; }
    public void setRecipientType(String recipientType) { this.recipientType = recipientType; }
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long recipientId) { this.recipientId = recipientId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
