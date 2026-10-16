package com.zone.lesoongen.platform;

import com.zone.lesoongen.application.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real local processes, including a child renderer; no model or network calls. */
class LessonVersionPdfServiceTest {
    @TempDir Path control;

    @Test void unavailableRendererIsExplicit() {
        assertEquals("PDF_RENDERER_UNAVAILABLE", assertThrows(AppException.class,
                () -> new LessonVersionPdfService("").convert(new byte[0])).code());
    }

    @Test void abnormalExitCleansDescendantsAndDirectory() throws Exception {
        var service = renderer("fail");
        try {
            assertEquals("PDF_RENDER_FAILED", assertThrows(AppException.class,
                    () -> service.convert(new byte[0])).code());
            assertClean();
        } finally { cleanupChildren(); }
    }

    @Test void fastExitCleansUnobservedChildAndDirectory() throws Exception {
        var service = renderer("fast");
        try {
            for (int i = 0; i < 8; i++) {
                assertEquals("PDF_RENDER_FAILED", assertThrows(AppException.class,
                        () -> service.convert(new byte[0])).code());
                assertClean();
            }
        } finally { cleanupChildren(); }
    }

    @Test void formalRenderWritesIndependentOdfInsteadOfDocx() throws Exception {
        var service = renderer("success");
        service.render(LessonDocumentSupport.fromPlainText("教学目标\n当前第七版正文"), "当前标题", 7, false);
        String input = Files.readString(control.resolve("print-input"));
        var xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("office:document", xml.getDocumentElement().getTagName());
        assertTrue(xml.getDocumentElement().getTextContent().contains("教案版本V7"));
        assertTrue(xml.getDocumentElement().getTextContent().contains("当前第七版正文"));
    }

    @Test void timeoutCapacityInterruptionAndRecoveryReleaseResources() throws Exception {
        var service = renderer("block");
        var firstError = new AtomicReference<Throwable>();
        var secondError = new AtomicReference<Throwable>();
        Thread first = worker(service, firstError), second = worker(service, secondError);
        try {
            first.start(); second.start();
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (records().size() < 2 && System.nanoTime() < until) Thread.sleep(50);
            assertEquals(2, records().size(), "both renderers started");
            assertEquals("PDF_BUSY", assertThrows(AppException.class,
                    () -> service.convert(new byte[0])).code());
            second.interrupt(); second.join(10_000);
            assertFalse(second.isAlive());
            assertEquals("PDF_INTERRUPTED", ((AppException) secondError.get()).code());
            first.join(70_000);
            assertFalse(first.isAlive());
            assertEquals("PDF_RENDER_TIMEOUT", ((AppException) firstError.get()).code());
            assertClean();
            Files.writeString(control.resolve("mode"), "success");
            assertTrue(new String(service.convert(new byte[0])).startsWith("%PDF-"));
        } finally {
            first.interrupt(); second.interrupt();
            first.join(10_000); second.join(10_000);
            cleanupChildren();
        }
    }

    private Thread worker(LessonVersionPdfService service, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try { service.convert(new byte[0]); }
            catch (Throwable error) { failure.set(error); }
        });
    }

    private LessonVersionPdfService renderer(String mode) throws Exception {
        Files.writeString(control.resolve("mode"), mode);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path script = control.resolve(windows ? "renderer.cmd" : "renderer.sh");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        String classes = Path.of(Renderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Files.writeString(script, (windows ? "@echo off\r\n" : "#!/bin/sh\nexec ")
                + '"' + java + "\" -cp \"" + classes + "\" \"" + Renderer.class.getName() + "\" \"" + control
                + (windows ? "\" %*\r\n" : "\" \"$@\"\n"));
        if (!windows) assertTrue(script.toFile().setExecutable(true));
        return new LessonVersionPdfService(script.toString());
    }

    private List<Path> records() throws Exception {
        try (var files = Files.list(control)) { return files.filter(p -> p.toString().endsWith(".started")).toList(); }
    }

    private void assertClean() throws Exception {
        for (Path record : records()) {
            var data = Files.readAllLines(record);
            assertFalse(ProcessHandle.of(Long.parseLong(data.get(1))).map(ProcessHandle::isAlive).orElse(false), "renderer child survived");
            assertFalse(ProcessHandle.of(Long.parseLong(data.get(2))).map(ProcessHandle::isAlive).orElse(false), "renderer survived");
            assertFalse(Files.exists(Path.of(data.get(0))), "conversion directory survived");
        }
    }

    private void cleanupChildren() throws Exception {
        for (Path record : records()) {
            var data = Files.readAllLines(record);
            ProcessHandle.of(Long.parseLong(data.get(1))).ifPresent(ProcessHandle::destroyForcibly);
            ProcessHandle.of(Long.parseLong(data.get(2))).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    public static class Renderer {
        public static void main(String[] args) throws Exception {
            if ("child".equals(args[0])) { Thread.sleep(180_000); return; }
            Path control = Path.of(args[0]), temp = Path.of(args[args.length - 1]).getParent();
            String mode = Files.readString(control.resolve("mode"));
            if ("success".equals(mode)) {
                Files.copy(Path.of(args[args.length - 1]), control.resolve("print-input"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(temp.resolve("lesson.pdf"), "%PDF-1.7\nfixture"); return;
            }
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), Renderer.class.getName(), "child").start();
            Files.writeString(control.resolve(ProcessHandle.current().pid() + ".started"), temp + "\n" + child.pid() + "\n" + ProcessHandle.current().pid());
            if ("fast".equals(mode)) System.exit(7);
            if ("fail".equals(mode)) { Thread.sleep(1000); System.exit(7); }
            Thread.sleep(180_000);
        }
    }
}
