package com.example.intern.controller;

import com.example.intern.model.Notification;
import com.example.intern.security.CurrentUser;
import com.example.intern.security.SessionUser;
import com.example.intern.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The logged-in user's own notifications. The recipient always comes from the session. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired private NotificationService notificationService;

    private static String typeOf(SessionUser me) {
        return "STUDENT".equals(me.role()) ? NotificationService.STUDENT : NotificationService.STAFF;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        SessionUser me = CurrentUser.get();
        String type = typeOf(me);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Notification n : notificationService.latest(type, me.id())) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", n.getId());
            m.put("kind", n.getKind());
            m.put("title", n.getTitle());
            m.put("message", n.getMessage());
            m.put("link", n.getLink());
            m.put("createdAt", n.getCreatedAt());
            m.put("read", n.getReadAt() != null);
            items.add(m);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("unread", notificationService.unreadCount(type, me.id()));
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll() {
        SessionUser me = CurrentUser.get();
        return ResponseEntity.ok(Map.of("marked", notificationService.markAllRead(typeOf(me), me.id())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> read(@PathVariable Long id) {
        SessionUser me = CurrentUser.get();
        if (!notificationService.markRead(id, typeOf(me), me.id())) {
            return ResponseEntity.status(404).body(Map.of("error", "Notification not found."));
        }
        return ResponseEntity.ok(Map.of("message", "Marked as read."));
    }
}
