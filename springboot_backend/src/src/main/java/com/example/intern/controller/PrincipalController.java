package com.example.intern.controller;

import com.example.intern.security.CurrentUser;
import com.example.intern.model.Student;
import com.example.intern.model.Teacher;
import com.example.intern.model.Result;
import com.example.intern.model.Exam;
import com.example.intern.model.UserRole;
import com.example.intern.repository.StudentRepository;
import com.example.intern.repository.TeacherRepository;
import com.example.intern.repository.ResultRepository;
import com.example.intern.repository.ExamRepository;
import com.example.intern.service.PromotionService;
import com.example.intern.service.SafeErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.*;

@RestController
@RequestMapping("/api/principal")
public class PrincipalController {

    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ResultRepository resultRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private PromotionService promotionService;

    /** The principal's own college from the session; client-supplied college values are ignored. */
    private String college() { return CurrentUser.get().collegeName(); }

    // ==========================================
    // 1. OPERATION METRICS: FETCH ALL EXAMS (ISOLATED)
    // ==========================================
    @GetMapping("/exams/all")
    public ResponseEntity<?> getAllCollegeExams(@RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName) {
        try {
            // 🚨 FIXED: Now queries using index boundary restriction tags rather than global scans
            List<Exam> exams = examRepository.findByCollegeName(college()).stream()
                    .map(Exam::summaryCopy).collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(exams);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 2. PRINCIPAL'S STUDENT PROMOTION RULE ENGINE (ISOLATED)
    // ==========================================
    @PostMapping("/promote-year")
    public ResponseEntity<?> runPromotionEngine(
            @RequestParam String departmentName,
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName) {
        try {
            // 🚨 FIXED: Passes tenant parameters straight down to core business rules
            promotionService.promoteDepartmentStudents(college(), departmentName);
            return ResponseEntity.ok(Map.of("success", true, "message", "Batch promoted successfully."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @PostMapping("/increment-semester")
    public ResponseEntity<?> runSemesterIncrement(
            @RequestParam String departmentName,
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName) {
        try {
            // 🚨 FIXED: Bound scope locally to the request framework context
            promotionService.incrementSemesterOnly(college(), departmentName);
            return ResponseEntity.ok(Map.of("success", true, "message", "Semesters updated smoothly."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 3. PRINCIPAL'S HOD ROLE & STAFF MANAGEMENT (ISOLATED)
    // ==========================================
    @GetMapping("/teachers")
    public ResponseEntity<?> getAllTeachers(@RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName) {
        try {
            // 🚨 FIXED: Excludes the principal from displaying in the management grid
            List<Teacher> teachers = teacherRepository.findByCollegeNameAndIsPrincipalFalse(college());
            return ResponseEntity.ok(teachers);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @PutMapping("/teacher/{id}/assign-hod")
    public ResponseEntity<?> assignHodRole(@PathVariable Long id) {
        try {
            Teacher teacher = teacherRepository.findById(id).orElseThrow(() -> new RuntimeException("Teacher profile not found"));
            if (teacher.isPrincipal() || !CurrentUser.sameCollege(teacher.getCollegeName())) {
                return ResponseEntity.status(403).body(Map.of("error", "This staff member is not in your college."));
            }
            teacher.setRole(UserRole.HOD);
            teacherRepository.save(teacher);
            return ResponseEntity.ok(Map.of("success", true, "message", teacher.getName() + " appointed as HOD of " + teacher.getBranchName()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @PutMapping("/teacher/{id}/revoke-hod")
    public ResponseEntity<?> revokeHodRole(@PathVariable Long id) {
        try {
            Teacher teacher = teacherRepository.findById(id).orElseThrow(() -> new RuntimeException("Teacher profile not found"));
            if (teacher.isPrincipal() || !CurrentUser.sameCollege(teacher.getCollegeName())) {
                return ResponseEntity.status(403).body(Map.of("error", "This staff member is not in your college."));
            }
            teacher.setRole(UserRole.TEACHER);
            teacherRepository.save(teacher);
            return ResponseEntity.ok(Map.of("success", true, "message", "Revoked HOD authority from " + teacher.getName()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 4. DELETE OPERATIONS
    // ==========================================
    @DeleteMapping("/students/{id}")
    public ResponseEntity<?> deleteStudent(@PathVariable Long id, @RequestParam(required = false) String ignoredAuthorizedDepartment) {
        try {
            Student student = studentRepository.findById(id).orElseThrow(() -> new RuntimeException("Student not found"));
            if (!CurrentUser.sameCollege(student.getCollegeName())) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized access."));
            }
            studentRepository.delete(student);
            return ResponseEntity.ok(Map.of("success", true, "message", "Student permanently deleted."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    @DeleteMapping("/teachers/{id}")
    public ResponseEntity<?> deleteTeacher(@PathVariable Long id, @RequestParam(required = false) String ignoredAuthorizedDepartment) {
        try {
            Teacher teacher = teacherRepository.findById(id).orElseThrow(() -> new RuntimeException("Teacher profile not found"));
            if (teacher.isPrincipal() || !CurrentUser.sameCollege(teacher.getCollegeName())) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized department boundary mismatch."));
            }
            teacherRepository.delete(teacher);
            return ResponseEntity.ok(Map.of("success", true, "message", "Teacher permanently removed."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }

    // ==========================================
    // 5. CRITICAL DATA REPORTS: FAILS & ABSENTEES (ISOLATED)
    // ==========================================
    @GetMapping("/department-exceptions")
    public ResponseEntity<?> getDeptExceptions(
            @RequestParam String department,
            @RequestHeader(value = "X-College-Name", required = false) String ignoredCollegeName) {
        try {
            
            List<Map<String, Object>> exceptions = new ArrayList<>();
            // 🚨 FIXED: Applied multi-tenant filtering directly to cross-joins
            List<Student> students = studentRepository.findByCollegeNameAndDepartmentNameAndIsActiveTrue(college(), department);
            List<Exam> exams = examRepository.findByCollegeNameAndDepartmentName(college(), department);

            for (Exam exam : exams) {
                List<Result> results = resultRepository.findByExamId(exam.getId());
                Set<String> submittedRollNos = new HashSet<>();

                for (Result r : results) {
                    Student linkedStudent = r.getStudent();
                    String currentRollNo = (linkedStudent != null && linkedStudent.getRollNo() != null) ? linkedStudent.getRollNo() : "Unknown";
                    String currentName = (linkedStudent != null) ? linkedStudent.getName() : "Unknown";

                    submittedRollNos.add(currentRollNo);
                    double pct = (r.getTotalMarks() > 0) ? ((double) r.getMarksObtained() / r.getTotalMarks()) * 100 : 0;

                    if (pct < 40.0) {
                        Map<String, Object> failObj = new HashMap<>();
                        failObj.put("rollNo", currentRollNo);
                        failObj.put("name", currentName);
                        failObj.put("examTitle", exam.getTitle());
                        failObj.put("score", r.getMarksObtained() + "/" + r.getTotalMarks());
                        failObj.put("percentage", Math.round(pct) + "%");
                        failObj.put("status", "FAILED");
                        exceptions.add(failObj);
                    }
                }

                for (Student s : students) {
                    if (Objects.equals(s.getCurrentSemester(), exam.getTargetSemester())) {
                        String rollToCheck = s.getRollNo() != null ? s.getRollNo() : s.getPrnNo();
                        if (rollToCheck != null && !submittedRollNos.contains(rollToCheck)) {
                            Map<String, Object> absentObj = new HashMap<>();
                            absentObj.put("rollNo", rollToCheck);
                            absentObj.put("name", s.getName());
                            absentObj.put("examTitle", exam.getTitle());
                            absentObj.put("score", "-");
                            absentObj.put("percentage", "0%");
                            absentObj.put("status", "ABSENT");
                            exceptions.add(absentObj);
                        }
                    }
                }
            }
            return ResponseEntity.ok(exceptions);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", SafeErrors.message(e)));
        }
    }
}