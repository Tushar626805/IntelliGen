package com.example.intern.service;

/** Thrown when an exam submission breaks a rule (not eligible, window closed, already submitted...). */
public class SubmissionRejectedException extends RuntimeException {
    private final int httpStatus;

    public SubmissionRejectedException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
