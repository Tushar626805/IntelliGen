package com.example.intern.service;

/** A problem talking to the Python AI service, with a message that is safe to show to teachers. */
public class AiServiceException extends RuntimeException {
    private final int httpStatus;

    public AiServiceException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
