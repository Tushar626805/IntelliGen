package org.springframework.security.authentication;
import java.util.*; import org.springframework.security.core.*; import org.springframework.security.core.authority.SimpleGrantedAuthority;
public class UsernamePasswordAuthenticationToken implements Authentication { final Object p; UsernamePasswordAuthenticationToken(Object p){this.p=p;} public Object getPrincipal(){return p;}
  public static UsernamePasswordAuthenticationToken authenticated(Object p,Object c,Collection<SimpleGrantedAuthority> a){ return new UsernamePasswordAuthenticationToken(p);} }
