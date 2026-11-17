package jakarta.servlet.http;

public interface HttpServletRequest { String getRemoteAddr(); HttpSession getSession(boolean c); String changeSessionId(); String getRequestURI(); Object getAttribute(String n); }
