package com.zone.lesoongen.platform;

import com.zone.lesoongen.application.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.concurrent.*;

/** Renders the independent version print template using an explicitly configured local renderer.
 * The local renderer runs inside OS process containment before it can launch children.
 */
@Service
public class LessonVersionPdfService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LessonVersionPdfService.class);
    private final String executable;
    private final Semaphore slots = new Semaphore(2);
    public LessonVersionPdfService(@Value("${platform.pdf.soffice:}") String executable) { this.executable = executable; }
    public boolean available() { return !executable.isBlank() && Files.isRegularFile(Path.of(executable)); }
    public byte[] render(LessonDocument document, String title, int version, boolean degraded) {
        return convertSource(LessonDocumentPrintTemplate.render(document, title, version, degraded)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "fodt");
    }
    /** Compatibility conversion for callers with a canonical DOCX; the formal endpoint uses render. */
    public byte[] convert(byte[] docx) {
        return convertSource(docx, "docx");
    }
    private byte[] convertSource(byte[] source, String extension) {
        if (!available())
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "PDF_RENDERER_UNAVAILABLE", "正式 PDF 转换器尚未配置");
        if (!slots.tryAcquire()) throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "PDF_BUSY", "PDF 转换繁忙，请稍后重试");
        Path temporary = null;
        ContainedRendererProcess process = null;
        try {
            temporary = Files.createTempDirectory("lesson-pdf-");
            Path input = temporary.resolve("lesson." + extension);
            Files.write(input, source);
            process = ContainedRendererProcess.start(java.util.List.of(executable, "-env:UserInstallation=" + temporary.resolve("profile").toUri(),
                    "--headless", "--convert-to", "pdf:writer_pdf_Export", "--outdir", temporary.toString(), input.toString()),
                    temporary.resolve("renderer.log"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!process.waitFor(100)) {
                if (System.nanoTime() >= deadline)
                    throw new AppException(HttpStatus.GATEWAY_TIMEOUT, "PDF_RENDER_TIMEOUT", "PDF 转换超时");
            }
            Path output = temporary.resolve("lesson.pdf");
            if (process.exitCode() != 0 || !Files.isRegularFile(output)) throw new java.io.IOException("PDF not produced");
            byte[] result = Files.readAllBytes(output);
            if (result.length < 5 || !new String(result, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new java.io.IOException("Invalid PDF output");
            return result;
        } catch (AppException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "PDF_INTERRUPTED", "PDF 转换已中断"); }
        catch (java.io.IOException error) { throw new AppException(HttpStatus.BAD_GATEWAY, "PDF_RENDER_FAILED", "PDF 转换失败"); }
        finally {
            try {
                try {
                    if (process != null) process.close();
                } catch (java.io.IOException error) {
                    LOG.error("PDF renderer containment cleanup failed", error);
                    throw new AppException(HttpStatus.BAD_GATEWAY, "PDF_RENDER_FAILED", "PDF 转换进程清理失败");
                } finally { removeTemporary(temporary); }
            } finally { slots.release(); }
        }
    }

    private static void removeTemporary(Path temporary) {
        if (temporary == null) return;
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            while (Files.exists(temporary)) {
                try (var paths = Files.walk(temporary)) {
                    for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                    return;
                } catch (java.io.IOException error) {
                    // Windows can retain inherited log handles briefly after process exit.
                    if (System.nanoTime() >= deadline) {
                        LOG.warn("PDF temporary directory cleanup failed: {}", temporary, error);
                        return;
                    }
                    try { Thread.sleep(50); }
                    catch (InterruptedException interruptedError) { interrupted = true; }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
