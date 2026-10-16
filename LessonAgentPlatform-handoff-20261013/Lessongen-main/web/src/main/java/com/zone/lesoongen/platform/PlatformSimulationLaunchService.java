package com.zone.lesoongen.platform;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.sql.Timestamp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.platform.PlatformSimulationLaunchController.LaunchRequest;
import com.zone.lesoongen.config.AppProperties;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlatformSimulationLaunchService {
    public record Launch(UUID launchId, UUID requestKey, String lessonId, String versionId, String state,
            String simulationRunId, String f3SessionId, String f4SessionId, List<String> activeRoles,
            int timeoutSeconds, String message, Instant createdAt) {}
    private final JdbcTemplate db;
    private final PlatformLessonService lessons;
    private final PlatformLessonDocumentService documents;
    private final PlatformSimulationService simulations;
    private final CapabilityAdmissionPolicy admission;
    private final com.zone.lesoongen.auth.PlatformAccessService access;
    @Value("${app.scheduling-enabled:true}") private boolean schedulingEnabled;
    private final PlatformNavigationBridgeService navigation;
    private final ObjectMapper mapper;
    private final String python, script, f2Url, f3Url, f4Url;
    private final String internalToken;
    private final RestClient f3;
    private static final List<String> ROLES = List.of("teacher", "assistant", "class_clown",
            "deep_thinker", "note_taker", "inquisitive_mind");

    public PlatformSimulationLaunchService(JdbcTemplate db, PlatformLessonService lessons,
            PlatformNavigationBridgeService navigation, ObjectMapper mapper, AppProperties properties,
            PlatformLessonDocumentService documents, PlatformSimulationService simulations,
            @Value("${platform.f3.python:${F3_PYTHON:python}}") String python,
            @Value("${platform.f3.runner-script:scripts/f3-runner/run_f3_demo.py}") String script,
            @Value("${platform.f3.base-url:http://127.0.0.1:8003}") String f3Url,
            @Value("${platform.f4.base-url:http://127.0.0.1:8000}") String f4Url,
            @Value("${platform.f2.self-url:http://127.0.0.1:8080}") String f2Url,
            CapabilityAdmissionPolicy admission, com.zone.lesoongen.auth.PlatformAccessService access) {
        this.db = db; this.lessons = lessons; this.navigation = navigation; this.mapper = mapper;
        this.documents = documents;
        this.simulations = simulations;
        this.admission = admission;
        this.access = access;
        this.python = python; this.script = script; this.f3Url = f3Url; this.f4Url = f4Url; this.f2Url = f2Url;
        this.internalToken = properties.engineInternalToken();
        // HttpURLConnection cannot send PATCH; keep HTTP/1.1 for the local F3 server.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.f3 = RestClient.builder().baseUrl(f3Url).requestFactory(factory).build();
    }

    public synchronized Launch start(LaunchRequest request) {
        access.requireOwnedLesson(request.lessonId());
        List<String> roles = request.activeRoles();
        if (roles != null && (roles.isEmpty() || !roles.contains("teacher")
                || roles.stream().anyMatch(role -> role == null || !ROLES.contains(role))
                || roles.stream().distinct().count() != roles.size())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "F3_ROLES_INVALID", "请选择有效的课堂角色，教师必须参与");
        }
        if (roles != null) roles = List.copyOf(roles);
        int timeout = request.timeoutSeconds() == null ? 3 : request.timeoutSeconds();
        if (timeout < 1 || timeout > 300) {
            throw new AppException(HttpStatus.BAD_REQUEST, "F3_TIMEOUT_INVALID", "自动继续等待时间须为 1 至 300 秒");
        }
        Launch existing = findByRequest(request.requestKey());
        if (existing != null) {
            if (!existing.lessonId().equals(request.lessonId()) || !existing.versionId().equals(request.versionId())
                    || !java.util.Objects.equals(existing.activeRoles(), roles) || existing.timeoutSeconds() != timeout)
                throw new AppException(HttpStatus.CONFLICT, "F3_REQUEST_REUSED", "此请求编号已用于其他教案版本");
            return existing;
        }
        var lesson = lessons.detail(request.lessonId());
        if (lesson.versions().stream().noneMatch(v -> v.id().equals(request.versionId())))
            throw AppException.notFound("教案版本");
        admission.requireNew(CapabilityAdmissionPolicy.Capability.F3);
        if (db.queryForObject("SELECT COUNT(*) FROM platform_simulation_launch WHERE state IN ('QUEUED','RUNNING','RECONCILING','UNKNOWN','STOPPING')",
                Integer.class) >= 4) throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "F3_LAUNCH_CAPACITY", "课堂任务已达并发上限，请等待已有任务完成");
        if (db.queryForObject("SELECT COUNT(*) FROM platform_simulation_launch WHERE lesson_id = ? AND version_id = ? AND state IN ('RECONCILING','UNKNOWN','STOPPING')",
                Integer.class, request.lessonId(), request.versionId()) > 0) throw new AppException(HttpStatus.CONFLICT,
                        "F3_RECONCILIATION_PENDING", "此版本的已有课堂正在核对远端状态，请从任务历史继续查看");
        UUID id = UUID.randomUUID();
        Launch queued = new Launch(id, request.requestKey(), request.lessonId(), request.versionId(), "QUEUED", null,
                null, null, roles, timeout, "正在准备课堂模拟", Instant.now());
        insert(queued);
        Thread worker = new Thread(() -> run(queued), "f3-launch-" + id);
        worker.setDaemon(true);
        worker.start();
        return queued;
    }

    public Launch status(UUID id) {
        Launch value = find(id);
        if (value == null) throw AppException.notFound("课堂模拟启动任务");
        return value;
    }

    public List<Launch> list(String ownerUserId) {
        return db.query("""
                SELECT s.launch_id, s.request_key, s.lesson_id, s.version_id, s.state,
                       s.simulation_run_id, s.f3_session_id, s.f4_session_id,
                       s.active_roles_json, s.timeout_seconds, s.message, s.created_at
                FROM platform_simulation_launch s
                JOIN platform_lesson l ON l.id = s.lesson_id
                WHERE l.owner_user_id = ?
                ORDER BY s.created_at DESC, s.launch_id DESC LIMIT 100
                """, (rs, row) -> launch(rs), ownerUserId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAfterRestart() {
        if (!schedulingEnabled) return;
        db.update("""
                UPDATE platform_simulation_launch
                SET state = 'RECONCILING', message = ?, updated_at = ?
                WHERE state IN ('QUEUED', 'RUNNING')
                """, "平台已重启，正在核对远端课堂状态；尚未确认停止",
                Timestamp.from(Instant.now()));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void reconcileRemoteSessions() {
        if (!schedulingEnabled) return;
        for (String id : db.query("SELECT launch_id FROM platform_simulation_launch WHERE state IN ('RECONCILING','UNKNOWN','STOPPING') ORDER BY updated_at LIMIT 10",
                (rs, row) -> rs.getString(1))) {
            Launch launch = find(UUID.fromString(id));
            try {
                Map<?, ?> remote;
                if ("STOPPING".equals(launch.state())) {
                    // Preserve cancellation across outages/restarts. A fresh end response,
                    // not a delayed lookup made before end, is authoritative here.
                    remote = stopRemote(launch);
                    if ("NOT_CREATED".equals(remote.get("status"))) continue;
                } else try { remote = f3.get().uri("/api/classroom/requests/{key}", launch.launchId().toString()).retrieve().body(Map.class); }
                catch (RestClientResponseException missing) {
                    if (missing.getStatusCode().value() != 404) throw missing;
                    // The remote service fences late runner submissions before capacity is released.
                    remote = request(() -> f3.post().uri("/api/classroom/requests/{key}/cancel-if-absent", launch.launchId().toString()).retrieve().body(Map.class));
                }
                if (remote != null && "NOT_CREATED".equals(remote.get("status"))) {
                    update(launch, "FAILED", null, "远端已确认未创建课堂并取消此提交编号，可重新发起");
                    continue;
                }
                if (remote == null) throw new IllegalStateException("Empty F3 lookup response");
                String remoteId = bindRemoteSession(launch, remote);
                String remoteState = String.valueOf(remote.get("status"));
                if (!List.of("COMPLETED", "INTERRUPTED", "FAILED").contains(remoteState)) {
                    if ("STOPPING".equals(launch.state()))
                        stoppingMessage(launch, "已请求结束课堂，正在等待远端停止并保存已有记录");
                    else update(launch, "RECONCILING", null, "已找到原课堂，远端仍在运行；可继续查看或结束课堂");
                    continue;
                }
                // A concurrent end must fence this old lookup before result retrieval.
                if (!status(launch.launchId()).state().equals(launch.state())) continue;
                var saved = db.query("SELECT id FROM platform_simulation_run WHERE f3_session_id = ?", (rs, row) -> rs.getString(1), remoteId);
                String runId;
                if (!saved.isEmpty()) runId = saved.get(0);
                else {
                    @SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>) request(() -> f3.get()
                            .uri("/api/classroom/sessions/{id}/result", remoteId).retrieve().body(Map.class));
                    runId = simulations.save(launch.lessonId(), new PlatformSimulationController.SaveSimulationRequest(result,
                            "# 恢复归档\n\n课堂执行状态：" + remoteState + "\n\n完整课堂事件与分析状态见 JSON 记录。")).simulationRunId();
                }
                if ("STOPPING".equals(launch.state()))
                    finishStopping(launch, remoteState, runId, "已核对远端终态并保存已有课堂记录");
                else update(launch, remoteState, runId, "已核对远端终态并恢复结果归档");
            } catch (RuntimeException error) {
                if ("STOPPING".equals(launch.state()))
                    stoppingMessage(launch, "结束请求尚未获远端确认，系统将继续重试；请勿重复启动同一版本");
                else update(launch, "UNKNOWN", launch.simulationRunId(), "尚未确认远端课堂状态，系统将继续核对；请勿重复启动同一版本");
            }
        }
    }

    private void insert(Launch launch) {
        String roles;
        try {
            roles = launch.activeRoles() == null ? null : mapper.writeValueAsString(launch.activeRoles());
        } catch (Exception error) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "F3_LAUNCH_SERIALIZE_FAILED",
                    "课堂角色配置保存失败");
        }
        db.update("""
                INSERT INTO platform_simulation_launch
                (launch_id, request_key, lesson_id, version_id, state, simulation_run_id,
                 f3_session_id, f4_session_id, active_roles_json, timeout_seconds,
                 message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, launch.launchId().toString(), launch.requestKey().toString(), launch.lessonId(),
                launch.versionId(), launch.state(), launch.simulationRunId(), launch.f3SessionId(),
                launch.f4SessionId(), roles, launch.timeoutSeconds(), launch.message(),
                Timestamp.from(launch.createdAt()), Timestamp.from(launch.createdAt()));
    }

    private Launch find(UUID launchId) {
        List<Launch> rows = db.query("""
                SELECT launch_id, request_key, lesson_id, version_id, state, simulation_run_id,
                       f3_session_id, f4_session_id, active_roles_json, timeout_seconds, message, created_at
                FROM platform_simulation_launch WHERE launch_id = ?
                """, (rs, row) -> launch(rs), launchId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Launch findByRequest(UUID requestKey) {
        List<Launch> rows = db.query("""
                SELECT launch_id, request_key, lesson_id, version_id, state, simulation_run_id,
                       f3_session_id, f4_session_id, active_roles_json, timeout_seconds, message, created_at
                FROM platform_simulation_launch WHERE request_key = ?
                """, (rs, row) -> launch(rs), requestKey.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private Launch launch(ResultSet rs) throws SQLException {
        List<String> roles = null;
        String json = rs.getString("active_roles_json");
        if (json != null && !json.isBlank()) {
            try {
                roles = List.copyOf(mapper.readValue(json, List.class));
            } catch (Exception error) {
                throw new SQLException("Invalid active_roles_json", error);
            }
        }
        return new Launch(UUID.fromString(rs.getString("launch_id")),
                UUID.fromString(rs.getString("request_key")), rs.getString("lesson_id"),
                rs.getString("version_id"), rs.getString("state"), rs.getString("simulation_run_id"),
                rs.getString("f3_session_id"), rs.getString("f4_session_id"), roles,
                rs.getInt("timeout_seconds"), rs.getString("message"),
                rs.getTimestamp("created_at").toInstant());
    }

    private boolean update(Launch original, String state, String runId, String message) {
        return db.update("""
                UPDATE platform_simulation_launch
                SET state = ?, simulation_run_id = ?, message = ?, updated_at = ?
                WHERE launch_id = ?
                  AND state IN ('QUEUED','RUNNING','RECONCILING','UNKNOWN')
                """, state, runId, message, Timestamp.from(Instant.now()), original.launchId().toString()) > 0;
    }

    private void stoppingMessage(Launch launch, String message) {
        db.update("UPDATE platform_simulation_launch SET message = ?, updated_at = ? WHERE launch_id = ? AND state = 'STOPPING'",
                message, Timestamp.from(Instant.now()), launch.launchId().toString());
    }

    private void finishStopping(Launch launch, String state, String runId, String message) {
        db.update("UPDATE platform_simulation_launch SET state = ?, simulation_run_id = ?, message = ?, updated_at = ? WHERE launch_id = ? AND state = 'STOPPING'",
                state, runId, message, Timestamp.from(Instant.now()), launch.launchId().toString());
    }

    private String bindRemoteSession(Launch launch, Map<?, ?> remote) {
        if (!launch.lessonId().equals(remote.get("lessonId")) || !launch.versionId().equals(remote.get("versionId")))
            throw new IllegalStateException("F3 source binding mismatch");
        String remoteId = UUID.fromString(String.valueOf(remote.get("sessionId"))).toString();
        if (launch.f3SessionId() != null && !launch.f3SessionId().equals(remoteId))
            throw new IllegalStateException("F3 session binding mismatch");
        db.update("""
                UPDATE platform_simulation_launch SET f3_session_id = ?, updated_at = ?
                WHERE launch_id = ? AND state IN ('QUEUED','RUNNING','RECONCILING','UNKNOWN','STOPPING')
                  AND (f3_session_id IS NULL OR f3_session_id = ?)
                """, remoteId, Timestamp.from(Instant.now()), launch.launchId().toString(), remoteId);
        return remoteId;
    }

    private void bindF4Session(Launch original, String f4SessionId) {
        db.update("UPDATE platform_simulation_launch SET f4_session_id = ?, updated_at = ? WHERE launch_id = ?",
                f4SessionId, Timestamp.from(Instant.now()), original.launchId().toString());
    }

    private void observeSession(Launch launch, Path livePath) throws IOException {
        if (!Files.isRegularFile(livePath)) return;
        Map<?, ?> live = mapper.readValue(Files.readAllBytes(livePath), Map.class);
        if (!launch.lessonId().equals(live.get("lessonId")) || !launch.versionId().equals(live.get("versionId")))
            throw new IOException("F3 会话与教案版本不匹配");
        bindRemoteSession(status(launch.launchId()), live);
    }

    private String sessionId(UUID launchId) {
        Launch launch = status(launchId);
        if (launch.f3SessionId() == null)
            throw new AppException(HttpStatus.CONFLICT, "F3_SESSION_PREPARING", "课堂正在准备，请稍后重试");
        return launch.f3SessionId();
    }

    private Map<?, ?> request(java.util.function.Supplier<Map<?, ?>> action) {
        try {
            Map<?, ?> response = action.get();
            if (response == null) throw new AppException(HttpStatus.BAD_GATEWAY, "F3_EMPTY_RESPONSE", "课堂服务未返回结果");
            return response;
        } catch (RestClientResponseException error) {
            HttpStatus code = HttpStatus.resolve(error.getStatusCode().value());
            throw new AppException(code == null ? HttpStatus.BAD_GATEWAY : code, "F3_CLASSROOM_ERROR",
                    "课堂操作未完成：" + tail(error.getResponseBodyAsString()));
        } catch (RestClientException error) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "F3_UNAVAILABLE", "课堂服务暂时不可用，请检查 F3 服务");
        }
    }

    public Map<?, ?> classroom(UUID launchId) {
        String id = sessionId(launchId);
        return request(() -> f3.get().uri("/api/classroom/sessions/{id}", id).retrieve().body(Map.class));
    }

    public Map<?, ?> events(UUID launchId) {
        String id = sessionId(launchId);
        return request(() -> f3.get().uri("/api/classroom/sessions/{id}/events", id).retrieve().body(Map.class));
    }

    public Map<?, ?> message(UUID launchId, String content) {
        String id = sessionId(launchId);
        return request(() -> f3.post().uri("/api/classroom/sessions/{id}/messages", id)
                .body(Map.of("message", content)).retrieve().body(Map.class));
    }

    public Map<?, ?> control(UUID launchId, String action) {
        if (!List.of("pause", "resume", "end").contains(action))
            throw new AppException(HttpStatus.BAD_REQUEST, "F3_CONTROL_INVALID", "无效的课堂操作");
        if ("end".equals(action)) return end(launchId);
        if ("STOPPING".equals(status(launchId).state()))
            throw new AppException(HttpStatus.CONFLICT, "F3_SESSION_STOPPING", "课堂正在结束，不能继续或恢复");
        String id = sessionId(launchId);
        return request(() -> f3.post().uri("/api/classroom/sessions/{id}/" + action, id)
                .retrieve().body(Map.class));
    }

    private Map<?, ?> end(UUID launchId) {
        status(launchId); // Keep not-found behavior before attempting a state transition.
        db.update("""
                UPDATE platform_simulation_launch SET state = 'STOPPING', message = ?, updated_at = ?
                WHERE launch_id = ? AND state IN ('QUEUED','RUNNING','RECONCILING','UNKNOWN')
                """, "已收到结束请求，正在确认远端课堂状态", Timestamp.from(Instant.now()), launchId.toString());
        Launch current = status(launchId);
        if (List.of("COMPLETED", "INTERRUPTED", "FAILED").contains(current.state()))
            return terminalControlResponse(current);
        try {
            Map<?, ?> remote = stopRemote(current);
            return "NOT_CREATED".equals(remote.get("status")) ? terminalControlResponse(status(launchId)) : remote;
        } catch (RuntimeException error) {
            stoppingMessage(current, "结束请求尚未获远端确认，系统将继续重试；请勿重复启动同一版本");
            throw error;
        }
    }

    private Map<?, ?> terminalControlResponse(Launch launch) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("launchId", launch.launchId().toString());
        response.put("status", launch.state());
        response.put("message", launch.message());
        if (launch.f3SessionId() != null) response.put("sessionId", launch.f3SessionId());
        return response;
    }

    private Map<?, ?> stopRemote(Launch launch) {
        String remoteId = launch.f3SessionId();
        if (remoteId == null) {
            Map<?, ?> remote = request(() -> f3.post()
                    .uri("/api/classroom/requests/{key}/cancel-if-absent", launch.launchId().toString())
                    .retrieve().body(Map.class));
            if ("NOT_CREATED".equals(remote.get("status"))) {
                // The durable remote tombstone rejects even a runner already in transit.
                finishStopping(launch, "INTERRUPTED", null, "已取消启动，远端已确认未创建课堂");
                return remote;
            }
            remoteId = bindRemoteSession(launch, remote);
            if (List.of("COMPLETED", "INTERRUPTED", "FAILED").contains(String.valueOf(remote.get("status"))))
                return remote; // Already archived sessions need no second end request.
        }
        String session = remoteId;
        try {
            return request(() -> f3.post().uri("/api/classroom/sessions/{id}/end", session)
                    .retrieve().body(Map.class));
        } catch (AppException missingRuntime) {
            if (missingRuntime.status() != HttpStatus.NOT_FOUND) throw missingRuntime;
            Map<?, ?> archived = request(() -> f3.get().uri("/api/classroom/sessions/{id}", session)
                    .retrieve().body(Map.class));
            bindRemoteSession(launch, archived);
            if (!List.of("COMPLETED", "INTERRUPTED", "FAILED").contains(String.valueOf(archived.get("status"))))
                throw missingRuntime;
            return archived;
        }
    }

    public Map<?, ?> settings(UUID launchId, int timeoutSeconds) {
        String id = sessionId(launchId);
        return request(() -> f3.patch().uri("/api/classroom/sessions/{id}/settings", id)
                .body(Map.of("timeoutSeconds", timeoutSeconds)).retrieve().body(Map.class));
    }

    public PlatformSimulationController.SimulationDetail retryAnalysis(String lessonId, String runId) {
        var stored = simulations.detail(lessonId, runId);
        Map<?, ?> state = request(() -> f3.get().uri("/api/classroom/sessions/{id}", stored.f3SessionId()).retrieve().body(Map.class));
        Object configured = state.get("analysisTimeoutSeconds");
        double seconds = configured instanceof Number number ? number.doubleValue() : 300;
        if (!Double.isFinite(seconds) || seconds <= 0) seconds = 300;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofMillis((long) Math.ceil((seconds + 30) * 1000)));
        var analysisClient = RestClient.builder().baseUrl(f3Url).requestFactory(factory).build();
        // One explicit request only; its wait covers F3's whole analysis deadline.
        request(() -> analysisClient.post().uri("/api/classroom/sessions/{id}/analyze", stored.f3SessionId()).retrieve().body(Map.class));
        @SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>) request(() -> f3.get()
                .uri("/api/classroom/sessions/{id}/result", stored.f3SessionId()).retrieve().body(Map.class));
        boolean completed = "COMPLETED".equals(result.get("analysisStatus"));
        if (completed || "FAILED".equals(result.get("analysisStatus"))) {
            // Commit a terminal analysis snapshot before reporting failure to the caller.
            // The execution/events remain immutable; a later archive read must see this attempt.
            String heading = completed ? "# 补充分析" : "# 补充分析失败";
            simulations.save(lessonId, new PlatformSimulationController.SaveSimulationRequest(result,
                    heading + "\n\n课堂执行记录保持不变；最新分析状态、警告及证据见 JSON 记录。"));
        }
        if (!completed) throw new AppException(HttpStatus.BAD_GATEWAY,
                "F3_ANALYSIS_INCOMPLETE", "分析尚未成功，可稍后重试；已有课堂事件保持不变");
        return simulations.detail(lessonId, runId);
    }

    private void run(Launch launch) {
        Path directory = null;
        boolean runnerStarted = false;
        try {
            if (!update(launch, "RUNNING", null, "正在建立教案会话并执行动态课堂模拟")) return;
            List<Map<String, Object>> materials;
            String fallbackReason = null;
            try {
                var snapshot = documents.get(launch.lessonId(), launch.versionId());
                materials = LessonDocumentMaterialAdapter.materials(snapshot);
                if (materials.isEmpty()) fallbackReason = snapshot.bindingStatus() + ": no verified teaching rows";
            } catch (AppException missing) {
                if (!"LESSON_DOCUMENT_NOT_FOUND".equals(missing.code())) throw missing;
                materials = List.of(); fallbackReason = missing.code();
            }
            String f4SessionId = null;
            if (!"RUNNING".equals(status(launch.launchId()).state())) return;
            if (materials.isEmpty()) {
                var started = navigation.startForAdmittedSimulation(launch.launchId());
                f4SessionId = started.sessionId(); bindF4Session(launch, f4SessionId);
            }
            if (!"RUNNING".equals(status(launch.launchId()).state())) return;
            directory = Files.createTempDirectory("f3-launch-");
            Path config = directory.resolve("config.json");
            Path log = directory.resolve("runner.log");
            Path live = directory.resolve("f3-session.json");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("f4Url", f4Url);
            input.put("f3Url", f3Url);
            input.put("f2Url", f2Url);
            input.put("writebackToF2", true);
            input.put("internalToken", internalToken);
            input.put("lessonId", launch.lessonId());
            input.put("versionId", launch.versionId());
            input.put("f4SessionId", f4SessionId);
            input.put("materials", materials);
            input.put("fallbackReason", fallbackReason);
            input.put("requestKey", launch.launchId().toString());
            input.put("modelMode", "REAL");
            input.put("timeoutSeconds", launch.timeoutSeconds());
            if (launch.activeRoles() != null) input.put("activeRoles", launch.activeRoles());
            input.put("liveStatusPath", live.toString());
            input.put("maxConsecutiveTimeouts", 50);
            input.put("maxEvents", 40);
            // No default whole-class deadline. F3 owns activity time and pauses.
            input.put("maxWallClockSeconds", null);
            input.put("outputDir", directory.resolve("outputs").toString());
            Files.writeString(config, mapper.writeValueAsString(input), StandardCharsets.UTF_8);
            Path runner = Path.of(script).toAbsolutePath();
            if (!Files.isRegularFile(runner)) runner = Path.of("web").resolve(script).toAbsolutePath();
            if (!Files.isRegularFile(runner)) throw new IOException("F3 Runner 文件不存在：" + runner);
            ProcessBuilder builder = new ProcessBuilder(python, runner.toString(), "--config", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            // Windows PowerShell commonly gives Python a GBK console encoding. The runner log is
            // consumed as UTF-8 below, so force a stable encoding across Windows installations.
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            if (!"RUNNING".equals(status(launch.launchId()).state())) return;
            Process process = builder.start();
            runnerStarted = true;
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                observeSession(launch, live);
                // The runner bounds transport outages; model/analysis deadlines
                // are enforced inside F3's process containment. A responsive
                // paused classroom must not be killed by a second wall clock.
            }
            observeSession(launch, live);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("F3 Runner 失败：" + tail(output));
            String marker = "F2 simulationRunId:";
            int index = output.lastIndexOf(marker);
            if (index < 0) throw new IOException("模拟结束但没有 F2 回写凭据：" + tail(output));
            String runId = output.substring(index + marker.length()).stripLeading().split("\\R", 2)[0].trim();
            if (runId.isBlank()) throw new IOException("F2 回写编号为空");
            if (output.contains("最终状态: INTERRUPTED")) {
                if (!update(launch, "INTERRUPTED", runId, "课堂已提前结束，已保存现有记录"))
                    finishStopping(launch, "INTERRUPTED", runId, "课堂已提前结束，已保存现有记录");
            } else update(launch, "COMPLETED", runId, "动态课堂模拟已完成，结果已保存到 F2");
        } catch (Exception error) {
            update(launch, runnerStarted ? "RECONCILING" : "FAILED", null,
                    tail(error.getMessage() == null ? error.toString() : error.getMessage())
                    + (runnerStarted ? "；正在核对远端状态" : ""));
        } finally {
            if (directory != null) try {
                // Successful artifacts are durable in F2; keep failed runner files for local diagnosis.
                if ("COMPLETED".equals(status(launch.launchId()).state())
                        || "INTERRUPTED".equals(status(launch.launchId()).state())) {
                    try (var paths = Files.walk(directory)) {
                        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    private static String tail(String text) {
        String clean = text == null ? "未知错误" : text.trim();
        return clean.substring(Math.max(0, clean.length() - 700));
    }
}
