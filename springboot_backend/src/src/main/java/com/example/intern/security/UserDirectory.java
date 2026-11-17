package com.example.intern.security;

import com.example.intern.model.Student;
import com.example.intern.model.Teacher;
import com.example.intern.repository.StudentRepository;
import com.example.intern.repository.TeacherRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Builds SessionUser objects from database rows and re-validates them. */
@Component
public class UserDirectory {

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;

    public UserDirectory(StudentRepository studentRepository, TeacherRepository teacherRepository) {
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
    }

    public SessionUser fromStudent(Student s) {
        return new SessionUser(s.getId(), s.getName(), s.getUsername(), "STUDENT",
                s.getCollegeName(), s.getDepartmentName());
    }

    public SessionUser fromTeacher(Teacher t) {
        return new SessionUser(t.getId(), t.getName(), t.getUsername(), resolveStaffRole(t),
                t.getCollegeName(), t.getBranchName());
    }

    /**
     * Same rules the original login used (kept identical on purpose):
     * principal flag first, then the stored role, and a legacy admin row (NULL role + branch "ADMIN").
     */
    public static String resolveStaffRole(Teacher t) {
        if (t.isPrincipal()) {
            return "PRINCIPAL";
        }
        if (t.getRole() != null) {
            String dbRole = String.valueOf(t.getRole()).trim();
            if (!dbRole.isEmpty()) {
                return dbRole.toUpperCase();
            }
        } else if ("ADMIN".equals(t.getBranchName())) {
            return "ADMIN";
        }
        return "TEACHER";
    }

    /** Reloads the user; empty if the account was deleted, suspended or is no longer approved. */
    public Optional<SessionUser> refresh(SessionUser current) {
        if ("STUDENT".equals(current.role())) {
            return studentRepository.findById(current.id())
                    .filter(s -> s.isActive() && s.isApproved())
                    .map(this::fromStudent);
        }
        return teacherRepository.findById(current.id())
                .filter(t -> t.isActive() && t.isApproved())
                .map(this::fromTeacher);
    }

    public Authentication toAuthentication(SessionUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
    }
}
