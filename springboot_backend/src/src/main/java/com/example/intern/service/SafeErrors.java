package com.example.intern.service;

import java.util.Locale;

/**
 * Turns an exception into text that is safe to show to a user.
 *
 * <p>Controllers used to return {@code e.getMessage()} straight to the browser. For unexpected failures that text can be a
 * Hibernate/MySQL message naming tables, columns and constraints (or be {@code null}, which makes {@code Map.of} throw).
 * Deliberate, user-facing messages (thrown as a plain RuntimeException / IllegalArgumentException / IllegalStateException,
 * for example "Teacher profile not found") are kept; anything else becomes a generic message and the real error goes to the log.
 * Free of Spring/JPA types so it can be unit-tested on its own.
 */
public final class SafeErrors {
    public static final String GENERIC = "Something went wrong on the server. Please try again, or contact your administrator if it keeps happening.";
    private static final int MAX_LENGTH = 200;
    private static final String[] TECHNICAL = {
            "sql", "jdbc", "hibernate", "constraint", "exception", "could not execute", "nested", "violat",
            "duplicate entry", "org.", "java.", "javax.", "jakarta.", "com.example", "stacktrace", "\tat ", "\n"
    };

    private SafeErrors() { }

    public static String message(Throwable e) {
        String m = e == null ? null : e.getMessage();
        boolean deliberate = e != null && (e.getClass() == RuntimeException.class
                || e instanceof IllegalArgumentException || e instanceof IllegalStateException);
        if (deliberate && m != null && !m.isBlank() && m.length() <= MAX_LENGTH && !looksTechnical(m)) {
            return m;
        }
        if (e != null) System.err.println("Unhandled error (hidden from the user): " + e);   // full details stay in the server log
        return GENERIC;
    }

    static boolean looksTechnical(String m) {
        String low = m.toLowerCase(Locale.ROOT);
        for (String t : TECHNICAL) if (low.contains(t)) return true;
        return false;
    }
}
