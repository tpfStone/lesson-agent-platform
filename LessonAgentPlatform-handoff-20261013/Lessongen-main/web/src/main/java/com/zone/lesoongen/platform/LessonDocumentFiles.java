package com.zone.lesoongen.platform;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.Files;
public final class LessonDocumentFiles {
    private LessonDocumentFiles() {}
    public static MultipartFile upload(byte[] bytes) {
        return new MultipartFile() {
            public String getName() { return "document"; }
            public String getOriginalFilename() { return "lesson-version.docx"; }
            public String getContentType() { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
            public boolean isEmpty() { return bytes.length == 0; }
            public long getSize() { return bytes.length; }
            public byte[] getBytes() { return bytes.clone(); }
            public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
        };
    }
}
