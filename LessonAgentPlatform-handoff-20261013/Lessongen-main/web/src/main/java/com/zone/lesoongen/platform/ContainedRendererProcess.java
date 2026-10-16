package com.zone.lesoongen.platform;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Owns the renderer lifecycle independently of parent/child enumeration timing. */
abstract class ContainedRendererProcess implements AutoCloseable {
    abstract boolean waitFor(int milliseconds) throws IOException, InterruptedException;
    abstract int exitCode() throws IOException;
    @Override public abstract void close() throws IOException;

    static ContainedRendererProcess start(List<String> command, Path log) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new Windows(command, log);
        if (os.contains("linux") && Files.isExecutable(Path.of("/usr/bin/setsid"))) return new Posix(command, log);
        throw new IOException("No supported renderer process containment on this operating system");
    }

    /** A new session/process group exists before exec; a rapidly exiting root cannot hide its children. */
    private static final class Posix extends ContainedRendererProcess {
        private final Process process;
        private final int group;
        private final LibC libc = Native.load("c", LibC.class);
        Posix(List<String> command, Path log) throws IOException {
            // ProcessBuilder inherits our group. Its new pid cannot equal that existing pgid,
            // so setsid is not a group leader and does not take its optional fork branch.
            var args = new ArrayList<>(List.of("/usr/bin/setsid", "--wait")); args.addAll(command);
            process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            group = Math.toIntExact(process.pid());
        }
        @Override boolean waitFor(int milliseconds) throws InterruptedException { return process.waitFor(milliseconds, TimeUnit.MILLISECONDS); }
        @Override int exitCode() { return process.exitValue(); }
        @Override public void close() throws IOException {
            boolean interrupted = Thread.interrupted();
            try {
                // No shell and no descendant snapshot: negative pid addresses the entire process group.
                killGroup();
                if (process.isAlive()) process.destroyForcibly();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try { process.waitFor(20, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { interrupted = true; }
                }
                if (process.isAlive()) throw new IOException("Renderer process group did not terminate");
                // Cover interruption immediately after ProcessBuilder.start, before setsid ran.
                // Once the root has exited, no new descendants can be created outside this final group kill.
                killGroup();
                while (libc.kill(-group, 0) == 0) {
                    if (System.nanoTime() >= deadline) throw new IOException("Renderer children did not terminate");
                    try { Thread.sleep(20); } catch (InterruptedException e) { interrupted = true; }
                }
                if (Native.getLastError() != 3) throw new IOException("Cannot verify renderer group termination");
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        private void killGroup() throws IOException {
            if (libc.kill(-group, 9) != 0 && Native.getLastError() != 3) throw new IOException("Cannot stop renderer process group");
        }
    }

    public interface LibC extends Library { int kill(int pid, int signal); }

    /** The root is suspended until job membership succeeds. Children inherit the non-breakaway job. */
    private static final class Windows extends ContainedRendererProcess {
        private final JobApi api = Native.load("kernel32", JobApi.class, W32APIOptions.DEFAULT_OPTIONS);
        private WinNT.HANDLE job;
        private WinNT.HANDLE process;
        Windows(List<String> command, Path log) throws IOException {
            WinNT.HANDLE output = null, input = null, thread = null;
            Memory attributes = null, inheritedHandles = null;
            try {
                job = api.CreateJobObject(null, null);
                check(job != null, "CreateJobObject");
                var limits = new ExtendedLimits();
                limits.basic.limitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE; breakaway is not enabled.
                limits.write();
                check(api.SetInformationJobObject(job, 9, limits.getPointer(), limits.size()), "SetInformationJobObject");
                var security = new WinBase.SECURITY_ATTRIBUTES(); security.bInheritHandle = true;
                output = api.CreateFile(log.toAbsolutePath().toString(), WinNT.GENERIC_WRITE, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE,
                        security, WinNT.CREATE_ALWAYS, WinNT.FILE_ATTRIBUTE_NORMAL, null);
                check(output != null && !WinBase.INVALID_HANDLE_VALUE.equals(output), "CreateFile(log)");
                input = api.CreateFile("NUL", WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE,
                        security, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null);
                check(input != null && !WinBase.INVALID_HANDLE_VALUE.equals(input), "CreateFile(NUL)");
                var extended = new ExtendedStartup();
                var startup = extended.startup; startup.cb = new WinDef.DWORD(extended.size());
                startup.dwFlags = WinBase.STARTF_USESTDHANDLES;
                startup.hStdOutput = output; startup.hStdError = output; startup.hStdInput = input;
                // Inherit only this conversion's handles. Concurrent jobs must not hold one another's logs open.
                try (var attributeBytes = new Memory(Native.SIZE_T_SIZE)) {
                    api.InitializeProcThreadAttributeList(null, 1, 0, attributeBytes);
                    long bytes = Native.SIZE_T_SIZE == 8 ? attributeBytes.getLong(0) : attributeBytes.getInt(0);
                    check(bytes > 0, "InitializeProcThreadAttributeList(size)");
                    attributes = new Memory(bytes);
                    check(api.InitializeProcThreadAttributeList(attributes, 1, 0, attributeBytes), "InitializeProcThreadAttributeList");
                }
                inheritedHandles = new Memory(2L * Native.POINTER_SIZE);
                inheritedHandles.setPointer(0, output.getPointer()); inheritedHandles.setPointer(Native.POINTER_SIZE, input.getPointer());
                check(api.UpdateProcThreadAttribute(attributes, 0, new BaseTSD.ULONG_PTR(0x20002), inheritedHandles,
                        new BaseTSD.SIZE_T(inheritedHandles.size()), null, null), "UpdateProcThreadAttribute(handle list)");
                extended.attributes = attributes; extended.write();
                var info = new WinBase.PROCESS_INFORMATION();
                String line = command.stream().map(Windows::quote).collect(java.util.stream.Collectors.joining(" "));
                String first = command.get(0).toLowerCase(Locale.ROOT);
                if (first.endsWith(".cmd") || first.endsWith(".bat")) {
                    line = quote(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString()) + " /d /s /c \"" + line + "\"";
                }
                check(api.CreateProcessW(null, (line + '\0').toCharArray(), null, null, true,
                        new WinDef.DWORD(WinBase.CREATE_SUSPENDED | WinBase.CREATE_NO_WINDOW | WinBase.EXTENDED_STARTUPINFO_PRESENT),
                        null, null, extended.getPointer(), info), "CreateProcess");
                process = info.hProcess; thread = info.hThread;
                check(api.AssignProcessToJobObject(job, process), "AssignProcessToJobObject");
                check(api.ResumeThread(thread) != -1, "ResumeThread");
            } catch (IOException | RuntimeException e) {
                if (process != null) api.TerminateProcess(process, 1);
                try { close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            } finally {
                if (attributes != null) { api.DeleteProcThreadAttributeList(attributes); attributes.close(); }
                if (inheritedHandles != null) inheritedHandles.close();
                if (thread != null) api.CloseHandle(thread);
                if (output != null && !WinBase.INVALID_HANDLE_VALUE.equals(output)) api.CloseHandle(output);
                if (input != null && !WinBase.INVALID_HANDLE_VALUE.equals(input)) api.CloseHandle(input);
            }
        }
        @Override boolean waitFor(int milliseconds) throws IOException, InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException();
            int status = api.WaitForSingleObject(process, milliseconds);
            check(status != WinBase.WAIT_FAILED, "WaitForSingleObject");
            if (Thread.interrupted()) throw new InterruptedException();
            return status == WinBase.WAIT_OBJECT_0;
        }
        @Override int exitCode() throws IOException {
            var code = new IntByReference(); check(api.GetExitCodeProcess(process, code), "GetExitCodeProcess"); return code.getValue();
        }
        @Override public void close() throws IOException {
            boolean interrupted = Thread.interrupted();
            try {
                if (job != null) {
                    check(api.TerminateJobObject(job, 1), "TerminateJobObject");
                    try (var accounting = new Memory(48)) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (true) {
                            check(api.QueryInformationJobObject(job, 1, accounting, 48, null), "QueryInformationJobObject");
                            if (accounting.getInt(40) == 0) break; // ActiveProcesses in JOBOBJECT_BASIC_ACCOUNTING_INFORMATION.
                            if (System.nanoTime() >= deadline) throw new IOException("Renderer job did not terminate");
                            try { Thread.sleep(20); } catch (InterruptedException e) { interrupted = true; }
                        }
                    }
                }
            } finally {
                // KILL_ON_JOB_CLOSE also protects an exceptional cleanup path and JVM handle closure.
                if (job != null) { api.CloseHandle(job); job = null; }
                if (process != null) { api.CloseHandle(process); process = null; }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        private void check(boolean ok, String operation) throws IOException {
            if (!ok) throw new IOException(operation + " failed, Windows error " + api.GetLastError());
        }
        private static String quote(String argument) {
            return "\"" + argument.replaceAll("(\\\\*)\"", "$1$1\\\\\"").replaceAll("(\\\\+)$", "$1$1") + "\"";
        }
    }

    public interface JobApi extends Kernel32 {
        WinNT.HANDLE CreateJobObject(WinBase.SECURITY_ATTRIBUTES attributes, String name);
        boolean SetInformationJobObject(WinNT.HANDLE job, int infoClass, Pointer information, int length);
        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);
        boolean TerminateJobObject(WinNT.HANDLE job, int code);
        boolean QueryInformationJobObject(WinNT.HANDLE job, int infoClass, Pointer information, int length, IntByReference returned);
        int ResumeThread(WinNT.HANDLE thread);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, Pointer bytes);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, BaseTSD.ULONG_PTR attribute, Pointer value,
                BaseTSD.SIZE_T bytes, Pointer previous, Pointer returned);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean CreateProcessW(String application, char[] command, WinBase.SECURITY_ATTRIBUTES processSecurity,
                WinBase.SECURITY_ATTRIBUTES threadSecurity, boolean inherit, WinDef.DWORD flags, Pointer environment,
                String directory, Pointer startup, WinBase.PROCESS_INFORMATION info);
    }
    @Structure.FieldOrder({"startup", "attributes"})
    public static class ExtendedStartup extends Structure {
        public WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
        public Pointer attributes;
    }
    @Structure.FieldOrder({"perProcessTime", "perJobTime", "limitFlags", "minimumWorkingSet", "maximumWorkingSet", "activeLimit", "affinity", "priority", "scheduling"})
    public static class BasicLimits extends Structure {
        public long perProcessTime, perJobTime;
        public int limitFlags;
        public BaseTSD.SIZE_T minimumWorkingSet = new BaseTSD.SIZE_T(), maximumWorkingSet = new BaseTSD.SIZE_T();
        public int activeLimit;
        public BaseTSD.ULONG_PTR affinity = new BaseTSD.ULONG_PTR();
        public int priority, scheduling;
    }
    @Structure.FieldOrder({"basic", "ioCounters", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
    public static class ExtendedLimits extends Structure {
        public BasicLimits basic = new BasicLimits();
        public long[] ioCounters = new long[6];
        public BaseTSD.SIZE_T processMemory = new BaseTSD.SIZE_T(), jobMemory = new BaseTSD.SIZE_T(),
                peakProcessMemory = new BaseTSD.SIZE_T(), peakJobMemory = new BaseTSD.SIZE_T();
    }
}
