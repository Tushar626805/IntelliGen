package org.springframework.web.filter;

import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException;
public abstract class OncePerRequestFilter { protected boolean shouldNotFilter(HttpServletRequest r) throws ServletException {return false;} protected abstract void doFilterInternal(HttpServletRequest rq,HttpServletResponse rs,FilterChain c) throws ServletException,IOException; }
