package org.springframework.http;
public class ResponseEntity<T> {
  public final int status; public final T body;
  public ResponseEntity(int s, T b){ this.status=s; this.body=b; }
  public T getBody(){ return body; }
  public interface BodyBuilder { <T> ResponseEntity<T> body(T b); <T> ResponseEntity<T> build(); BodyBuilder header(String n,String... v); BodyBuilder contentType(MediaType m); }
  static class Builder implements BodyBuilder { final int s; Builder(int s){this.s=s;}
    public <T> ResponseEntity<T> body(T b){ return new ResponseEntity<>(s,b);} public <T> ResponseEntity<T> build(){ return new ResponseEntity<>(s,null);}
    public BodyBuilder header(String n,String... v){return this;} public BodyBuilder contentType(MediaType m){return this;} }
  public static <T> ResponseEntity<T> ok(T b){ return new ResponseEntity<>(200,b);} public static BodyBuilder ok(){ return new Builder(200);}
  public static BodyBuilder accepted(){ return new Builder(202);} public static BodyBuilder badRequest(){ return new Builder(400);}
  public static BodyBuilder status(int s){ return new Builder(s);} public static BodyBuilder status(HttpStatus s){ return new Builder(s.value());}
}
