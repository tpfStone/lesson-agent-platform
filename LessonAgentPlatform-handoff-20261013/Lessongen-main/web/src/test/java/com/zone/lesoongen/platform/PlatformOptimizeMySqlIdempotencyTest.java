package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import com.zone.lesoongen.auth.CurrentUserService;
import com.zone.lesoongen.auth.PlatformUser;
import com.zone.lesoongen.auth.PlatformUserRepository;
import com.zone.lesoongen.domain.shared.Ulids;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** The same retries and six-way first-submission race under actual MySQL REPEATABLE READ. */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/vendor/mysql")
@Testcontainers(disabledWithoutDocker = true)
class PlatformOptimizeMySqlIdempotencyTest extends PlatformOptimizeIdempotencyTest {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.44")
            .withDatabaseName("optimize_idempotency")
            .withUsername("optimize_test").withPassword("test-password");

    @MockitoSpyBean CurrentUserService currentUser;
    @Autowired PlatformUserRepository users;

    @Test void differentOwnersCanFirstSubmitTheSameKeyConcurrently() throws Exception {
        ThreadLocal<PlatformUser> actor = new ThreadLocal<>();
        doAnswer(call -> actor.get() == null ? call.callRealMethod() : actor.get()).when(currentUser).require();
        var owners = new java.util.ArrayList<PlatformUser>();
        var sources = new java.util.ArrayList<PlatformLessonController.LessonDetail>();
        for (int i = 0; i < 2; i++) {
            String id = Ulids.next(Clock.systemUTC());
            db.update("INSERT INTO platform_user (id, username, password_hash, display_name, role, status, created_at, updated_at) VALUES (?, ?, 'test-hash', 'concurrent owner', 'TEACHER', 'ACTIVE', NOW(6), NOW(6))",
                    id, "optimize_" + id);
            var user = users.findById(id).orElseThrow();
            owners.add(user); actor.set(user);
            sources.add(lessons.create(new PlatformLessonController.CreateLesson("并发归属", "数学", "五年级", "面积", 40, "同一来源内容")));
            actor.remove();
        }
        // Both calls have already checked their absent key before either can insert.
        var prepared = new CyclicBarrier(2);
        when(issues.optimizationFocus(anyString(), anyString())).thenAnswer(call -> {
            prepared.await(10, TimeUnit.SECONDS); return List.of("共享焦点");
        });
        UUID key = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    actor.set(owners.get(index));
                    try {
                        var source = sources.get(index);
                        var input = mapper.convertValue(java.util.Map.of("versionId", source.lesson().currentVersionId(),
                                "requestKey", key.toString()), PlatformOptimizeController.Start.class);
                        return service.start(source.lesson().id(), input).jobId();
                    } finally { actor.remove(); }
                }));
            }
            String first = futures.get(0).get(30, TimeUnit.SECONDS), second = futures.get(1).get(30, TimeUnit.SECONDS);
            assertNotEquals(first, second);
            assertEquals(owners.get(0).id(), db.queryForObject("SELECT owner_id FROM lesson_job WHERE id = ?", String.class, first));
            assertEquals(owners.get(1).id(), db.queryForObject("SELECT owner_id FROM lesson_job WHERE id = ?", String.class, second));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM lesson_job WHERE idempotency_key = ?", Integer.class, key.toString()));
        } finally { pool.shutdownNow(); actor.remove(); }
    }
}
