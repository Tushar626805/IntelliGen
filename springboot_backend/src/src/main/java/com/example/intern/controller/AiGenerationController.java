package com.example.intern.controller;

import com.example.intern.security.CurrentUser;
import com.example.intern.security.RateLimiter;
import com.example.intern.security.SessionUser;
import com.example.intern.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Background question generation with real progress. Upload a PDF, get a job id, poll for progress,
 * cancel if needed. Available to teachers, HODs and principals (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/ai")
public class AiGenerationController {

    private static final long TEN_MINUTES = 10 * 60 * 1000L;

    @Autowired private FileStorageService fileStorageService;
    @Autowired private PdfProcessingService pdfProcessingService;
    @Autowired private MlIntegrationService mlIntegrationService;
    @Autowired private AiJobRegistry jobRegistry;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private NotificationService notificationService;

    @PostMapping("/jobs")
    public ResponseEntity<?> startJob(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "num", defaultValue = "5") int num,
                                      @RequestParam(value = "difficulty", defaultValue = "medium") String difficulty) {
        SessionUser me = CurrentUser.get();

        if (!rateLimiter.allow("ai-start:" + me.id(), 6, TEN_MINUTES)) {
            return ResponseEntity.status(429).body(Map.of("error", "You have started several generations recently. Please wait a few minutes."));
        }

        String level = difficulty == null ? "medium" : difficulty.trim().toLowerCase();
        if (!level.equals("easy") && !level.equals("medium") && !level.equals("hard")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Difficulty must be easy, medium or hard."));
        }
        String uploadProblem = UploadValidator.firstProblem(file, num);
        if (uploadProblem != null) {
            return ResponseEntity.badRequest().body(Map.of("error", uploadProblem));
        }

        try {
            String savedFilePath = fileStorageService.saveTeacherNotes(file);
            String text = pdfProcessingService.extractTextFromPdf(savedFilePath);
            if (text == null || text.replace("\f", "").trim().length() < 100) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "The PDF does not contain enough readable text. Scanned (image-only) PDFs are not supported."));
            }
            String flaskJobId = mlIntegrationService.startJob(text, num, level);
            String jobId = jobRegistry.register(me.id(), flaskJobId);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (AiServiceException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "The PDF could not be processed. Please try another file."));
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> jobStatus(@PathVariable String jobId) {
        AiJobRegistry.JobInfo info = jobRegistry.find(jobId, CurrentUser.get().id());
        if (info == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Job not found or expired."));
        }
        try {
            String json = mlIntegrationService.getJob(info.flaskJobId());
            if (MlIntegrationService.isDone(json) && jobRegistry.markNotified(jobId)) {
                notificationService.aiFinished(CurrentUser.get().id(), MlIntegrationService.generatedCount(json));
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        } catch (AiServiceException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<?> cancelJob(@PathVariable String jobId) {
        AiJobRegistry.JobInfo info = jobRegistry.find(jobId, CurrentUser.get().id());
        if (info == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Job not found or expired."));
        }
        try {
            mlIntegrationService.cancelJob(info.flaskJobId());
        } catch (AiServiceException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("message", "Cancelled."));
    }
}
