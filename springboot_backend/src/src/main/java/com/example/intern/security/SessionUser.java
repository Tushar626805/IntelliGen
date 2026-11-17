package com.example.intern.security;

import java.io.Serializable;

/**
 * The authenticated identity stored in the server-side HTTP session.
 * The browser never supplies this - it is built from the database at login and
 * re-validated against the database on every /api request (see SessionRefreshFilter).
 *
 * @param branchName teacher/HOD branch, or the student's department
 */
public record SessionUser(Long id, String name, String username, String role,
                          String collegeName, String branchName) implements Serializable {
}
