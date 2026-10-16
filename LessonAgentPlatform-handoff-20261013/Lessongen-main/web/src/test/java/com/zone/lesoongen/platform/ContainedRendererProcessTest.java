package com.zone.lesoongen.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContainedRendererProcessTest {
    @TempDir Path control;

    @Test void childRemainsContainedEvenWhenLauncherExitedBeforeFirstObservation() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!System.getProperty("os.name").toLowerCase().contains("win")) javaExecutable = javaExecutable.substring(0, javaExecutable.length() - 4);
        String classes = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            Path pidFile = control.resolve("child-" + attempt);
            try (var process = ContainedRendererProcess.start(List.of(javaExecutable, "-cp", classes,
                    Launcher.class.getName(), pidFile.toString()), control.resolve("renderer.log"))) {
                // Do not enumerate children. Force the root to exit before the caller observes its state.
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(Files.exists(pidFile));
                assertTrue(process.waitFor(10_000));
                assertEquals(7, process.exitCode());
                assertTrue(ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow().isAlive());
            } finally {
                if (Files.exists(pidFile)) {
                    var child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
                    boolean alive = child.map(ProcessHandle::isAlive).orElse(false);
                    child.filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
                    assertFalse(alive, "a child survived OS containment cleanup");
                }
            }
        }
    }

    public static class Launcher {
        public static void main(String[] args) throws Exception {
            if ("child".equals(args[0])) { Thread.sleep(180_000); return; }
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), Launcher.class.getName(), "child").start();
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
            System.exit(7);
        }
    }
}
