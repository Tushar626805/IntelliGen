package com.example.intern.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security loads the CSRF token lazily. Touching it here makes sure the XSRF-TOKEN cookie is
 * written on every response (including the login page itself) so api.js can echo it in X-XSRF-TOKEN.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
