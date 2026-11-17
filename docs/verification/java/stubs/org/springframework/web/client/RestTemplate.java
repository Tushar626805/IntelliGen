package org.springframework.web.client;
import org.springframework.http.*;
public class RestTemplate { public RestTemplate(org.springframework.http.client.SimpleClientHttpRequestFactory f){}
  public <T> ResponseEntity<T> exchange(String url, HttpMethod m, HttpEntity<?> e, Class<T> c){ return null; } }
