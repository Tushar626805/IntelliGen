// Run: java docs/verification/SafeErrorsCheck.java   (compiles SafeErrors.java in memory; no Maven needed)
import java.nio.file.*; import javax.tools.*; import java.net.*;

public class SafeErrorsCheck {
    static int n = 0, bad = 0;
    static void ok(String name, boolean c) { n++; if (!c) bad++; System.out.println((c ? "  PASS  " : "  FAIL  ") + name); }
    static class DataAccessLike extends RuntimeException { DataAccessLike(String m) { super(m); } }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath(), src = null;
        for (String c : new String[]{"springboot_backend/src/src/main/java", "src/src/main/java", "src/main/java"}) {
            Path p = root.resolve(c).resolve("com/example/intern/service/SafeErrors.java"); if (Files.exists(p)) { src = p; break; }
        }
        if (src == null) { System.out.println("SafeErrors.java not found"); System.exit(2); }
        Path out = Files.createTempDirectory("se");
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString()) != 0) System.exit(2);
        try (URLClassLoader cl = new URLClassLoader(new URL[]{out.toUri().toURL()})) {
            var m = cl.loadClass("com.example.intern.service.SafeErrors").getMethod("message", Throwable.class);
            String G = (String) cl.loadClass("com.example.intern.service.SafeErrors").getField("GENERIC").get(null);
            java.util.function.Function<Throwable, String> f = t -> { try { return (String) m.invoke(null, t); } catch (Exception e) { throw new RuntimeException(e); } };
            var err = System.err; System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));   // keep the log lines out of the report

            ok("deliberate message is kept (plain RuntimeException)", f.apply(new RuntimeException("Teacher profile not found")).equals("Teacher profile not found"));
            ok("deliberate message is kept (IllegalArgumentException)", f.apply(new IllegalArgumentException("Semester must be between 1 and 8.")).equals("Semester must be between 1 and 8."));
            ok("deliberate message is kept (IllegalStateException)", f.apply(new IllegalStateException("Exam is not published yet.")).equals("Exam is not published yet."));
            ok("a database error message is hidden", f.apply(new RuntimeException("could not execute statement [Duplicate entry 'x' for key 'exams.UK_1'] [insert into exams ...]")).equals(G));
            ok("a constraint violation is hidden", f.apply(new RuntimeException("Column 'title' cannot be null; constraint violation")).equals(G));
            ok("an exception subclass is hidden even with a harmless-looking message", f.apply(new DataAccessLike("Teacher not found")).equals(G));
            ok("a NullPointerException is hidden", f.apply(new NullPointerException("Cannot invoke \"String.trim()\" because x is null")).equals(G));
            ok("a checked exception is hidden", f.apply(new Exception("boom")).equals(G));
            ok("a null message gives the generic text, never null", f.apply(new RuntimeException((String) null)).equals(G));
            ok("a null throwable gives the generic text, never null", f.apply(null).equals(G));
            ok("a multi-line (stack-trace-like) message is hidden", f.apply(new RuntimeException("failed\n\tat com.example.Foo.bar(Foo.java:10)")).equals(G));
            ok("an overlong message is hidden", f.apply(new RuntimeException("x".repeat(201))).equals(G));
            ok("a package name in the message is hidden", f.apply(new RuntimeException("org.hibernate.exception.GenericJDBCException")).equals(G));
            ok("the result is never empty", !f.apply(new RuntimeException("   ")).isBlank());
            System.setErr(err);
        }
        System.out.println(n + " checks run - " + (bad == 0 ? "all passed" : bad + " FAILED"));
        if (bad > 0) System.exit(1);
    }
}
