package org.springframework.security.web.context;

import jakarta.servlet.http.*; import org.springframework.security.core.context.SecurityContext;
public interface SecurityContextRepository { void saveContext(SecurityContext c,HttpServletRequest rq,HttpServletResponse rs); }
