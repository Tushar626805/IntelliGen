package com.example.intern.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shared checks for PDF uploads used by question generation. */
public final class UploadValidator {

    public static final int MAX_QUESTIONS = 30;

    private UploadValidator() { }

    /** @return a user-facing problem description, or null if the upload is acceptable. */
    public static String firstProblem(MultipartFile file, int numQuestions) {
        if (file == null || file.isEmpty()) {
            return "Please choose a PDF file to upload.";
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            return "Only PDF files are supported.";
        }
        if (numQuestions < 1 || numQuestions > MAX_QUESTIONS) {
            return "Number of questions must be between 1 and " + MAX_QUESTIONS + ".";
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(5);
            if (!"%PDF-".equals(new String(head, StandardCharsets.ISO_8859_1))) {
                return "This file is not a valid PDF.";
            }
        } catch (Exception e) {
            return "The uploaded file could not be read.";
        }
        return null;
    }
}
