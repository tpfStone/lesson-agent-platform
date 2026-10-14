package com.zone.lesoongen.platform;

import com.zone.lesoongen.application.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.concurrent.*;

/** Converts only the canonical version DOCX using an explicitly configured local renderer. */
@Service
public class LessonVersionPdfService {
    private final String executable;
    private final Semaphore slots = new Semaphore(2);
    public LessonVersionPdfService(@Value("${platform.pdf.soffice:}") String executable) { this.executable = executable; }
    public boolean available() { return !executable.isBlank() && Files.isRegularFile(Path.of(executable)); }
    public byte[] convert(byte[] docx) {
        if (!available())
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "PDF_RENDERER_UNAVAILABLE", "正式 PDF 转换器尚未配置");
        if (!slots.tryAcquire()) throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "PDF_BUSY", "PDF 转换繁忙，请稍后重试");
        Path temporary = null;
        Process process = null;
        try {
            temporary = Files.createTempDirectory("lesson-pdf-");
            Path input = temporary.resolve("lesson.docx");
            Files.write(input, docx);
            process = new ProcessBuilder(executable, "-env:UserInstallation=" + temporary.resolve("profile").toUri(),
                    "--headless", "--convert-to", "pdf", "--outdir", temporary.toString(), input.toString())
                    .redirectErrorStream(true).redirectOutput(temporary.resolve("renderer.log").toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new AppException(HttpStatus.GATEWAY_TIMEOUT, "PDF_RENDER_TIMEOUT", "PDF 转换超时");
            }
            Path output = temporary.resolve("lesson.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) throw new java.io.IOException("PDF not produced");
            byte[] result = Files.readAllBytes(output);
            if (result.length < 5 || !new String(result, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new java.io.IOException("Invalid PDF output");
            return result;
        } catch (AppException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "PDF_INTERRUPTED", "PDF 转换已中断"); }
        catch (java.io.IOException error) { throw new AppException(HttpStatus.BAD_GATEWAY, "PDF_RENDER_FAILED", "PDF 转换失败"); }
        finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (temporary != null) try (var paths = Files.walk(temporary)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (java.io.IOException ignored) { }
            slots.release();
        }
    }
}
