package com.example.intern.controller;

import com.example.intern.model.Student;
import com.example.intern.model.Teacher;
import com.example.intern.repository.StudentRepository;
import com.example.intern.repository.TeacherRepository;
import com.example.intern.security.CurrentUser;
import com.example.intern.security.RateLimiter;
import com.example.intern.security.SessionUser;
import com.example.intern.security.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class UserController {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final long TEN_MINUTES = 10 * 60 * 1000L;

    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private UserDirectory userDirectory;
    @Autowired private RateLimiter rateLimiter;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------
    // REGISTRATION (behaviour unchanged; input is now validated)
    // ------------------------------------------------------------------
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        if (!rateLimiter.allow("register:" + request.getRemoteAddr(), 20, 60 * 60 * 1000L)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many registration attempts. Please try again later."));
        }

        String role = payload.get("role");
        String username = payload.get("username") == null ? null : payload.get("username").trim();
        String rawPassword = payload.get("password");
        String name = payload.get("name") == null ? null : payload.get("name").trim();
        String collegeName = payload.get("collegeName") == null ? null : payload.get("collegeName").trim();

        if ("ADMIN".equalsIgnoreCase(role)) return ResponseEntity.status(403).body(Map.of("error", "Admin accounts cannot be created via API."));

        if (blank(role) || blank(username) || blank(rawPassword) || blank(name) || blank(collegeName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name, username, password and college are required."));
        }
        if (rawPassword.length() < MIN_PASSWORD_LENGTH || rawPassword.length() > 72) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must contain between " + MIN_PASSWORD_LENGTH + " and 72 characters."));
        }

        if (studentRepository.findByUsername(username).isPresent() || teacherRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists!"));
        }

        if ("STUDENT".equalsIgnoreCase(role)) {
            String rollNo = payload.get("rollNo") == null ? null : payload.get("rollNo").trim();
            String department = payload.get("departmentName");
            if (blank(rollNo) || blank(department)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Roll number and department are required for students."));
            }
            if (studentRepository.existsByRollNoAndCollegeName(rollNo, collegeName)) {
                return ResponseEntity.badRequest().body(Map.of("error", "This roll number is already registered for your college."));
            }

            Student student = new Student();
            student.setName(name);
            student.setUsername(username);
            student.setPassword(passwordEncoder.encode(rawPassword));
            student.setCollegeName(collegeName);
            student.setDepartmentName(department);
            student.setRollNo(rollNo);
            if (!blank(payload.get("currentSemester"))) {
                try {
                    student.setCurrentSemester(Integer.parseInt(payload.get("currentSemester").trim()));
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Semester/year must be a number."));
                }
            }
            student.setApproved(true);
            studentRepository.save(student);
            return ResponseEntity.ok(Map.of("message", "Student registration successful."));
        }
        else if ("TEACHER".equalsIgnoreCase(role) || "PRINCIPAL".equalsIgnoreCase(role)) {
            Teacher staff = new Teacher();
            staff.setName(name);
            staff.setUsername(username);
            staff.setPassword(passwordEncoder.encode(rawPassword));
            staff.setCollegeName(collegeName);
            staff.setPrincipal("PRINCIPAL".equalsIgnoreCase(role));
            staff.setApproved(false);

            if ("TEACHER".equalsIgnoreCase(role)) {
                staff.setBranchName(payload.get("departmentName"));
                staff.setEmployeeId(payload.get("employeeId"));

                // Parse comma-separated subjects into a List
                if (payload.get("subjects") != null && !payload.get("subjects").isBlank()) {
                    List<String> subjectList = Arrays.stream(payload.get("subjects").split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                    staff.setSubjects(subjectList);
                }
            } else {
                staff.setBranchName("ADMINISTRATION");
                staff.setEmployeeId("PRINCIPAL-" + System.currentTimeMillis());
            }

            teacherRepository.save(staff);
            String msg = staff.isPrincipal() ? "Principal registration successful. Waiting for Admin approval." : "Teacher registration successful. Waiting for Principal approval.";
            return ResponseEntity.ok(Map.of("message", msg));
        }

        return ResponseEntity.badRequest().body(Map.of("error", "Invalid role specified."));
    }

    // ------------------------------------------------------------------
    // LOGIN / SESSION
    // ------------------------------------------------------------------
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials,
                                   HttpServletRequest request, HttpServletResponse httpResponse) {
        String username = credentials.get("username");
        String rawPassword = credentials.get("password");

        if (blank(username) || blank(rawPassword)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required."));
        }
        username = username.trim();

        String ip = request.getRemoteAddr();
        String userKey = "login:" + ip + ":" + username.toLowerCase();
        String ipKey = "login-ip:" + ip;
        if (!rateLimiter.allow(ipKey, 60, TEN_MINUTES) || !rateLimiter.allow(userKey, 8, TEN_MINUTES)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many login attempts. Please wait a few minutes and try again."));
        }

        // --- Check Student Table ---
        Optional<Student> studentOpt = studentRepository.findByUsername(username);
        if (studentOpt.isPresent()) {
            Student s = studentOpt.get();
            if (passwordEncoder.matches(rawPassword, s.getPassword())) {
                if (!s.isActive()) return ResponseEntity.status(403).body(Map.of("error", "Your account has been suspended by the Administrator."));
                if (!s.isApproved()) return ResponseEntity.status(403).body(Map.of("error", "Account pending approval."));

                startSession(userDirectory.fromStudent(s), request, httpResponse);
                rateLimiter.reset(userKey);

                Map<String, Object> response = new HashMap<>();
                response.put("id", s.getId());
                response.put("username", s.getName());
                response.put("email", s.getUsername());
                response.put("role", "STUDENT");
                response.put("rollNo", s.getRollNo());
                response.put("collegeName", s.getCollegeName());
                response.put("departmentName", s.getDepartmentName());
                return ResponseEntity.ok(response);
            }
        }

        // --- Check Teacher/Admin/HOD Table ---
        Optional<Teacher> teacherOpt = teacherRepository.findByUsername(username);
        if (teacherOpt.isPresent()) {
            Teacher t = teacherOpt.get();
            if (passwordEncoder.matches(rawPassword, t.getPassword())) {
                if (!t.isActive()) return ResponseEntity.status(403).body(Map.of("error", "Your account has been suspended by the Administrator."));
                if (!t.isApproved()) return ResponseEntity.status(403).body(Map.of("error", "Account pending hierarchy approval."));

                SessionUser sessionUser = userDirectory.fromTeacher(t);
                startSession(sessionUser, request, httpResponse);
                rateLimiter.reset(userKey);

                Map<String, Object> response = new HashMap<>();
                response.put("id", t.getId());
                response.put("username", t.getName());
                response.put("email", t.getUsername());
                response.put("role", sessionUser.role());
                response.put("collegeName", t.getCollegeName());
                response.put("branchName", t.getBranchName());
                response.put("subjects", t.getSubjects() != null ? t.getSubjects() : new ArrayList<>());
                return ResponseEntity.ok(response);
            }
        }

        return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials."));
    }

    /** Creates the server-side session (new session id on login to prevent session fixation). */
    private void startSession(SessionUser user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(userDirectory.toAuthentication(user));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /** Lets the frontend verify that its session is still valid. */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        SessionUser u = CurrentUser.get();
        Map<String, Object> body = new HashMap<>();
        body.put("id", u.id());
        body.put("username", u.name());
        body.put("email", u.username());
        body.put("role", u.role());
        body.put("collegeName", u.collegeName());
        body.put("branchName", u.branchName());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "Logged out."));
    }

    // ------------------------------------------------------------------
    // APPROVAL WORKFLOWS (role-restricted in SecurityConfig; college-scoped here)
    // ------------------------------------------------------------------
    @GetMapping("/admin/pending-principals")
    public ResponseEntity<List<Teacher>> getPendingPrincipals() {
        return ResponseEntity.ok(teacherRepository.findByIsPrincipalTrueAndIsApprovedFalse());
    }

    @PutMapping("/admin/approve-principal/{id}")
    public ResponseEntity<?> approvePrincipal(@PathVariable Long id) {
        Optional<Teacher> opt = teacherRepository.findById(id);
        if (opt.isEmpty() || !opt.get().isPrincipal()) {
            return ResponseEntity.status(404).body(Map.of("error", "Principal account not found."));
        }
        Teacher principal = opt.get();
        principal.setApproved(true);
        teacherRepository.save(principal);
        return ResponseEntity.ok(Map.of("message", "Principal Approved."));
    }

    /** The college in the URL is ignored: a principal only ever sees their own college. */
    @GetMapping("/principal/pending-teachers/{collegeName}")
    public ResponseEntity<List<Teacher>> getPendingTeachers(@PathVariable String collegeName) {
        return ResponseEntity.ok(teacherRepository.findByIsPrincipalFalseAndCollegeNameAndIsApprovedFalse(CurrentUser.get().collegeName()));
    }

    @PutMapping("/principal/approve-teacher/{id}")
    public ResponseEntity<?> approveTeacher(@PathVariable Long id) {
        Optional<Teacher> opt = teacherRepository.findById(id);
        if (opt.isEmpty() || opt.get().isPrincipal() || !CurrentUser.sameCollege(opt.get().getCollegeName())) {
            return ResponseEntity.status(404).body(Map.of("error", "Teacher not found in your college."));
        }
        Teacher teacher = opt.get();
        teacher.setApproved(true);
        teacherRepository.save(teacher);
        return ResponseEntity.ok(Map.of("message", "Teacher Approved."));
    }
}
