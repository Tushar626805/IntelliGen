package com.example.intern.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * On every /api request, re-checks the session user against the database.
 * - Suspended / deleted / unapproved accounts lose their session immediately.
 * - Role changes (e.g. teacher promoted to HOD) take effect without re-login.
 * Registered manually in SecurityConfig (NOT a Spring bean) so it only runs inside the security chain.
 */
public class SessionRefreshFilter extends OncePerRequestFilter {

    private final UserDirectory directory;

    public SessionRefreshFilter(UserDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUser current) {
            Optional<SessionUser> fresh = directory.refresh(current);
            if (fresh.isPresent()) {
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(directory.toAuthentication(fresh.get()));
                SecurityContextHolder.setContext(ctx);
            } else {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
