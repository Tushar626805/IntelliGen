package org.springframework.web.client;
public abstract class HttpStatusCodeException extends RuntimeException { public abstract org.springframework.http.HttpStatusCode getStatusCode(); public abstract String getResponseBodyAsString(); }
