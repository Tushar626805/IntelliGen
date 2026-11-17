package org.springframework.http;
public enum HttpStatus { OK(200), UNAUTHORIZED(401), FORBIDDEN(403); private final int v; HttpStatus(int v){this.v=v;} public int value(){return v;} }
