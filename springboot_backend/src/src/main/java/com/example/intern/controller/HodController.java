package com.example.intern.controller;

import com.example.intern.security.CurrentUser;
import com.example.intern.model.Teacher;
import com.example.intern.model.Student;
import com.example.intern.model.Exam;
import com.example.intern.model.Question;
import com.example.intern.repository.TeacherRepository;
import com.example.intern.repository.StudentRepository;
import com.example.intern.repository.ExamRepository;
import com.example.intern.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Optional;

@RestController
@RequestMapping("/api/hod")
public class HodController {

    @Autowired private TeacherRepository teacherRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private NotificationService notificationService;
    private ExamRepository examRepository;
    @Autowired private PromotionService promotionService;

    // 🚨 NEW AI GENERATION DEPENDENCIES
    @Autowired private FileStorageService fileStorageService;
    @Autowired private PdfProcessingService pdfProcessingService;
    @Autowired private MlIntegrationService mlIntegrationService;

    /** The HOD's own college/branch from the session; client-supplied values are ignored. */
    private String college() { return CurrentUser.get().collegeName(); }
    private String branch() { return CurrentUser.get().branchName(); }

    private static String errorJson(String message) {
        String safe = message == null ? "Unknown error" : message
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
        return "{\"error\": \"" + safe + "\"}";
    }

    // ==========================================
    // 1. AI QUESTION GENERATION PIPELINE
    // ==========================================
    @PostMapping("/upload-notes")
    public ResponseEntity<String> uploadNotesAndGenerateExam(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "num", defaultValue = "5") int num,
            @RequestParam(value = "difficulty", defaultValue = "medium") String difficulty) {
        String level = difficulty == null ? "medium" : difficulty.trim().toLowerCase();
        if (!level.equals("easy") && !level.equals("medium") && !level.equals("hard")) {
            return ResponseEntity.badRequest().body(errorJson("Difficulty must be easy, medium or hard."));
        }
        String uploadProblem = UploadValidator.firstProblem(file, num);
        if (uploadProblem != null) {
            return ResponseEntity.badRequest().body(errorJson(uploadProblem));
        }
        try {
            String savedFilePath = fileStorageService.saveTeacherNotes(file);
            String text = pdfProcessingService.extractTextFromPdf(savedFilePath);

            if (text == null || text.trim().length() < 100) {
                return ResponseEntity.badRequest().body("{\"error\": \"The uploaded PDF does not contain enough readable digital text.\"}");
            }

            String aiResponse = mlIntegrationService.generateQuestionsFromText(text, num, level);

            // Clean up Markdown formatting from AI response string block
            if (aiResponse != null) {
                aiResponse = aiResponse.trim();
                if (aiResponse.startsWith("```json")) {
                    aiResponse = aiResponse.substring(7);
                }
                if (aiResponse.endsWith("```")) {
                    aiResponse = aiResponse.substring(0, aiResponse.length() - 3);
                }
                aiResponse = aiResponse.trim();
            }

            return ResponseEntity.ok().header("Content-Type", "application/json").body(aiResponse);
        } catch (AiServiceException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(errorJson(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(errorJson("Question generation failed: " + SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 2. DELEGATED TERM ADVANCEMENT ENGINE
    // ==========================================
    @PostMapping("/increment-semester")
    public ResponseEntity<?> runBranchSemesterIncrement(
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName,
            @RequestParam(required = false) String ignoredBranchName) {
        try {
            promotionService.incrementSemesterOnly(college(), branch());
            return ResponseEntity.ok(Map.of("success", true, "message", "Semesters updated smoothly for " + branch()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 3. ISOLATED DEPARTMENT STAFF MANAGEMENT
    // ==========================================
    @GetMapping("/staff/pending")
    public ResponseEntity<?> getPendingBranchStaff(
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName,
            @RequestParam(required = false) String ignoredBranchName) {
        try {
            List<Teacher> pendingStaff = teacherRepository.findByCollegeNameAndBranchNameAndIsPrincipalFalseAndIsApprovedFalse(college(), branch());
            return ResponseEntity.ok(pendingStaff);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @GetMapping("/staff/active")
    public ResponseEntity<?> getActiveBranchStaff(
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName,
            @RequestParam(required = false) String ignoredBranchName) {
        try {
            List<Teacher> activeStaff = teacherRepository.findByCollegeNameAndBranchName(college(), branch());
            return ResponseEntity.ok(activeStaff);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @PutMapping("/staff/approve/{id}")
    public ResponseEntity<?> approveBranchStaff(
            @PathVariable Long id,
            @RequestParam(required = false) String ignoredAuthorizedBranch) {
        try {
            Teacher teacher = teacherRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Teacher record missing"));

            if (teacher.isPrincipal() || !CurrentUser.sameCollege(teacher.getCollegeName()) || !CurrentUser.sameBranch(teacher.getBranchName())) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized access. Department boundary breach."));
            }
            if (teacher.isApproved()) {
                // Prevents re-activating an account an administrator suspended
                return ResponseEntity.status(409).body(Map.of("error", "This staff profile is already approved."));
            }

            teacher.setApproved(true);
            teacher.setActive(true);
            teacherRepository.save(teacher);
            return ResponseEntity.ok(Map.of("success", true, "message", "Staff profile approved for " + teacher.getName()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 4. DEPARTMENTAL EXAM MANAGEMENT
    // ==========================================
    @GetMapping("/exams")
    public ResponseEntity<?> getBranchExams(
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName,
            @RequestParam(required = false) String ignoredBranchName) {
        try {
            List<Exam> exams = examRepository.findByCollegeNameAndDepartmentName(college(), branch()).stream()
                    .map(Exam::summaryCopy).collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(exams);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @PostMapping("/exams/save")
    public ResponseEntity<?> saveBranchExam(
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName,
            @RequestParam(required = false) String ignoredBranchName,
            @RequestParam(value = "draft", defaultValue = "false") boolean draft,
            @RequestBody Exam exam) {
        try {
            String problem = ExamValidator.firstProblem(exam);
            if (problem != null) {
                return ResponseEntity.badRequest().body(Map.of("error", problem));
            }

            ExamValidator.prepareForSave(exam, draft, "Exam Question");
            exam.setCollegeName(college());
            exam.setDepartmentName(branch());
            exam.setCreatedByTeacherUsername(CurrentUser.get().username());

            examRepository.save(exam);
            if (!draft) {
                notificationService.examPublished(exam);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", (draft ? "Draft saved. Review and publish it from your exam list." : "Exam created successfully inside your department dashboard registry.")));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }
}