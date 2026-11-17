package com.example.intern.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    public String saveTeacherNotes(MultipartFile file) throws IOException {
        File directory = new File(uploadDir);
        if (!directory.exists()) directory.mkdirs();

        // Never trust the client's filename: drop any path parts and unsafe characters.
        String original = file.getOriginalFilename() == null ? "notes.pdf" : file.getOriginalFilename();
        original = original.replace('\\', '/');
        original = original.substring(original.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (original.isBlank()) original = "notes.pdf";
        String fileName = UUID.randomUUID() + "_" + original;
        Path filePath = Paths.get(uploadDir, fileName);
        Files.write(filePath, file.getBytes());

        return filePath.toString();
    }
}