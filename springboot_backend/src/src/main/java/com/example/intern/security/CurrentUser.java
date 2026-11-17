package com.example.intern.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience accessors for the logged-in user of the current request. */
public final class CurrentUser {

    private CurrentUser() { }

    /** The authenticated user. Only call from endpoints that require authentication. */
    public static SessionUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
            return user;
        }
        throw new IllegalStateException("No authenticated user on this request.");
    }

    /** True when the given college equals the logged-in user's college (case-insensitive, null-safe). */
    public static boolean sameCollege(String college) {
        String mine = get().collegeName();
        return mine != null && college != null && mine.equalsIgnoreCase(college.trim());
    }

    /** True when the given branch/department equals the logged-in user's branch (case-insensitive, null-safe). */
    public static boolean sameBranch(String branch) {
        String mine = get().branchName();
        return mine != null && branch != null && mine.equalsIgnoreCase(branch.trim());
    }

    public static boolean hasRole(String role) {
        return role.equalsIgnoreCase(get().role());
    }
}
