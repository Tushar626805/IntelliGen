package org.springframework.security.web.context;

import jakarta.servlet.http.*; import org.springframework.security.core.context.SecurityContext;
public class HttpSessionSecurityContextRepository implements SecurityContextRepository { public void saveContext(SecurityContext c,HttpServletRequest rq,HttpServletResponse rs){} }
