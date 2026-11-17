// Run (no Maven needed): java docs/verification/AnswerReviewPolicyCheck.java
// Compiles AnswerReviewPolicy together with this check in memory and verifies the release and grading rules.
import java.time.LocalDateTime;
import java.nio.file.*;
import javax.tools.*;
import java.net.*;
import java.util.*;

public class AnswerReviewPolicyCheck {
    static int n = 0, bad = 0;
    static void ok(String name, boolean c) { n++; if (!c) bad++; System.out.println((c ? "  PASS  " : "  FAIL  ") + name); }

    public static void main(String[] args) throws Exception {
        // Locate the policy source relative to the repository root, whichever layout is in use.
        Path root = Paths.get("").toAbsolutePath();
        Path src = null;
        for (String c : new String[]{"springboot_backend/src/src/main/java", "src/src/main/java", "src/main/java", "../springboot_backend/src/src/main/java"}) {
            Path p = root.resolve(c).resolve("com/example/intern/service/AnswerReviewPolicy.java");
            if (Files.exists(p)) { src = p; break; }
        }
        if (src == null) { System.out.println("AnswerReviewPolicy.java not found from " + root); System.exit(2); }

        Path out = Files.createTempDirectory("arp");
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        int rc = jc.run(null, null, null, "-d", out.toString(), src.toString());
        if (rc != 0) { System.out.println("compile failed"); System.exit(2); }
        try (URLClassLoader cl = new URLClassLoader(new URL[]{out.toUri().toURL()})) {
            Class<?> c = cl.loadClass("com.example.intern.service.AnswerReviewPolicy");
            var canRelease = c.getMethod("canRelease", LocalDateTime.class, LocalDateTime.class, long.class);
            var from = c.getMethod("releasableFrom", LocalDateTime.class, long.class);
            var released = c.getMethod("isReleased", LocalDateTime.class);
            var same = c.getMethod("sameAnswer", String.class, String.class);
            LocalDateTime now = LocalDateTime.of(2026, 10, 28, 12, 0);
            long grace = 120;   // ExamService.GRACE_SECONDS: students may still submit this long after their deadline

            // A teacher may release only after the exam window AND the submission grace period are over.
            ok("cannot release while the exam window is still open", !(boolean) canRelease.invoke(null, now.plusMinutes(1), now, grace));
            ok("cannot release exactly at the end time (grace period still running)", !(boolean) canRelease.invoke(null, now, now, grace));
            ok("cannot release 119 seconds after the end", !(boolean) canRelease.invoke(null, now.minusSeconds(119), now, grace));
            ok("can release exactly when the grace period ends", (boolean) canRelease.invoke(null, now.minusSeconds(120), now, grace));
            ok("can release long after the exam", (boolean) canRelease.invoke(null, now.minusDays(1), now, grace));
            ok("exam without an end time can never be released", !(boolean) canRelease.invoke(null, null, now, grace));
            ok("missing clock never allows release", !(boolean) canRelease.invoke(null, now, null, grace));
            ok("a negative grace value cannot make release earlier than the end", !(boolean) canRelease.invoke(null, now.plusSeconds(1), now, -500));
            ok("releasableFrom is end time plus grace", from.invoke(null, now, grace).equals(now.plusSeconds(120)));
            ok("releasableFrom is null without an end time", from.invoke(null, null, grace) == null);

            // Students see answers only after a teacher has released them (no clock involved).
            ok("never released (null) means hidden", !(boolean) released.invoke(null, (Object) null));
            ok("any release time means visible", (boolean) released.invoke(null, now.minusHours(1)));
            ok("a release time that the database rounded into the future still counts", (boolean) released.invoke(null, now.plusSeconds(1)));

            ok("exact match is correct", (boolean) same.invoke(null, "Paris", "Paris"));
            ok("case and surrounding spaces are ignored (same as grading)", (boolean) same.invoke(null, " Paris ", "paris"));
            ok("different option is incorrect", !(boolean) same.invoke(null, "Paris", "London"));
            ok("unanswered question is incorrect", !(boolean) same.invoke(null, "Paris", null));
            ok("missing answer key is never correct", !(boolean) same.invoke(null, null, "Paris"));
        }
        System.out.println(n + " checks run - " + (bad == 0 ? "all passed" : bad + " FAILED"));
        if (bad > 0) System.exit(1);
    }
}
