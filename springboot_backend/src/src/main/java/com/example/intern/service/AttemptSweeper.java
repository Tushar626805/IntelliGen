package com.example.intern.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute, auto-submits exam attempts that were abandoned after their deadline. */
@Component
@EnableScheduling
public class AttemptSweeper {

    @Autowired private AttemptService attemptService;
    @Autowired private NotificationService notificationService;

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void sweep() {
        try {
            int n = attemptService.sweepExpired();
            if (n > 0) {
                System.out.println("AttemptSweeper: auto-submitted " + n + " abandoned exam attempt(s).");
            }
        } catch (Exception e) {
            System.err.println("AttemptSweeper failed: " + e.getMessage());
        }
    }

    /** Keeps the notifications table small: anything older than 90 days is removed once a day. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOldNotifications() {
        try {
            notificationService.purgeOlderThanDays(90);
        } catch (Exception e) {
            System.err.println("Notification cleanup failed: " + e.getMessage());
        }
    }
}
