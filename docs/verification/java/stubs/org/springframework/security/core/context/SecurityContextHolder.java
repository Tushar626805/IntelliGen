package org.springframework.security.core.context;
import org.springframework.security.core.Authentication;
public class SecurityContextHolder {
  static class Impl implements SecurityContext { Authentication a; public Authentication getAuthentication(){return a;} public void setAuthentication(Authentication x){a=x;} }
  private static SecurityContext ctx = new Impl();
  public static SecurityContext getContext(){ return ctx; } public static SecurityContext createEmptyContext(){ return new Impl(); }
  public static void setContext(SecurityContext c){ ctx=c; } public static void clearContext(){ ctx=new Impl(); } }
