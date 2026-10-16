package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.sun.net.httpserver.HttpServer;
import com.zone.lesoongen.auth.CurrentUserService;
import com.zone.lesoongen.auth.PlatformUser;
import com.zone.lesoongen.auth.PlatformUserRepository;
import com.zone.lesoongen.domain.shared.Ulids;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Actual service transactions, independent MySQL connections and a local HTTP F4 contract. */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/vendor/mysql")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PlatformNavigationMySqlAdmissionTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.44")
            .withDatabaseName("navigation_admission").withUsername("acceptance").withPassword("test-password");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<String, Map<String, Object>> REMOTE = new ConcurrentHashMap<>();
    static final AtomicInteger POSTS = new AtomicInteger();
    static final HttpServer SERVER;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/api/sessions", exchange -> {
                Object response;
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestMethod().equals("POST")) {
                    POSTS.incrementAndGet();
                    Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
                    String key = request.get("request_key").toString();
                    response = REMOTE.computeIfAbsent(key, ignored -> session(UUID.randomUUID().toString(),
                            key, request.get("content"), request.get("metadata")));
                } else if (path.equals("/api/sessions")) {
                    response = REMOTE.values().stream().map(value -> value.get("session")).toList();
                } else {
                    String id = path.split("/")[3];
                    response = REMOTE.values().stream()
                            .filter(value -> id.equals(((Map<?, ?>) value.get("session")).get("id")))
                            .findFirst().orElse(Map.of());
                }
                byte[] bytes = JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            SERVER.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static Map<String, Object> session(String id, String key, Object content, Object metadata) {
        return Map.of("session", Map.of("id", id, "request_key", key, "lesson_metadata", metadata),
                "lesson_plan", Map.of("original_content", content));
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.f4.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("platform.admission-file", () -> "");
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @BeforeEach void resetRemote() { REMOTE.clear(); POSTS.set(0); }
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformUserRepository users;
    @MockitoSpyBean CurrentUserService currentUser;
    @MockitoSpyBean JdbcTemplate db;

    @Test void differentOwnersCanCreateDistinctKeysWithoutGapLockDeadlock() throws Exception {
        concurrentFirstSubmissions(false);
    }

    @Test void differentOwnersCanRegisterDistinctLegacyKeysWithoutGapLockDeadlock() throws Exception {
        concurrentFirstSubmissions(true);
    }

    private void concurrentFirstSubmissions(boolean legacy) throws Exception {
        ThreadLocal<PlatformUser> actor = new ThreadLocal<>();
        ThreadLocal<Boolean> participating = ThreadLocal.withInitial(() -> false);
        doAnswer(call -> actor.get() == null ? call.callRealMethod() : actor.get()).when(currentUser).require();
        var owners = new ArrayList<PlatformUser>();
        var sources = new ArrayList<PlatformLessonController.LessonDetail>();
        var keys = List.of(new UUID(0, legacy ? 3 : 1), new UUID(0, legacy ? 4 : 2));
        for (int i = 0; i < 2; i++) {
            String owner = Ulids.next(Clock.systemUTC());
            db.update("INSERT INTO platform_user (id, username, password_hash, display_name, role, status, created_at, updated_at) "
                            + "VALUES (?, ?, 'test-hash', 'independent owner', 'TEACHER', 'ACTIVE', NOW(6), NOW(6))",
                    owner, "navigation_" + owner);
            PlatformUser user = users.findById(owner).orElseThrow();
            owners.add(user); actor.set(user);
            var source = lessons.create(new PlatformLessonController.CreateLesson(
                    "并发导航", "数学", "五年级", "面积", 40, "各自拥有的原稿。"));
            sources.add(source); actor.remove();
            if (legacy) {
                String id = UUID.randomUUID().toString(), key = keys.get(i).toString();
                REMOTE.put(key, session(id, key, source.versions().get(0).content(),
                        Map.of("subject", "数学", "grade", "五年级", "topic", "面积")));
                db.update("INSERT INTO platform_navigation_link (session_id, lesson_id, source_version_id, created_at) VALUES (?, ?, ?, NOW(6))",
                        id, source.lesson().id(), source.lesson().currentVersionId());
            }
        }
        var bothAbsentReadsFinished = new CyclicBarrier(2);
        var isolationLevels = new CopyOnWriteArrayList<String>();
        doAnswer(call -> {
            Object rows = call.callRealMethod();
            String sql = call.getArgument(0);
            if (participating.get() && sql.contains("FROM platform_navigation_submission WHERE request_key = ?")
                    && sql.endsWith(" FOR UPDATE") && rows instanceof List<?> values && values.isEmpty()) {
                isolationLevels.add(db.queryForObject("SELECT @@transaction_isolation", String.class));
                // At RR these two distinct absent keys share an InnoDB gap. Both real reads
                // complete before either service can INSERT; owner locks do not serialize them.
                bothAbsentReadsFinished.await(10, TimeUnit.SECONDS);
            }
            return rows;
        }).when(db).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<PlatformNavigationBridgeService.Started>>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    actor.set(owners.get(index)); participating.set(true);
                    try {
                        var source = sources.get(index);
                        return navigation.start(source.lesson().id(), source.lesson().currentVersionId(),
                                keys.get(index), null, null, null);
                    } finally { actor.remove(); participating.remove(); }
                }));
            }
            var first = futures.get(0).get(25, TimeUnit.SECONDS);
            var second = futures.get(1).get(25, TimeUnit.SECONDS);
            assertNotEquals(first.sessionId(), second.sessionId());
            assertEquals(List.of("READ-COMMITTED", "READ-COMMITTED"), isolationLevels);
            for (int i = 0; i < 2; i++) {
                assertEquals(owners.get(i).id(), db.queryForObject(
                        "SELECT owner_user_id FROM platform_navigation_submission WHERE request_key = ?", String.class, keys.get(i).toString()));
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_link WHERE lesson_id = ?",
                        Integer.class, sources.get(i).lesson().id()));
            }
            assertEquals(legacy ? 0 : 2, POSTS.get());
        } finally {
            actor.remove(); participating.remove(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
