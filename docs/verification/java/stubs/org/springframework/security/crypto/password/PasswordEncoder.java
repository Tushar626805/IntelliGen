package org.springframework.security.crypto.password;

public interface PasswordEncoder { String encode(CharSequence r); boolean matches(CharSequence r,String e); }
