package org.springframework.web.multipart;

import java.io.*;
public interface MultipartFile { boolean isEmpty(); String getOriginalFilename(); InputStream getInputStream() throws IOException; byte[] getBytes() throws IOException; }
