package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real TCP, security filters, multipart parsing, storage and MySQL; no model calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.scheduling-enabled=false", "app.engine-internal-token=acceptance-only",
        "app.security.enabled=true", "app.storage-root=target/live-http-storage",
        "platform.pdf.soffice="
})
@Testcontainers(disabledWithoutDocker = true)
class PlatformLiveHttpAcceptanceTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.44")
            .withDatabaseName("live_http").withUsername("acceptance").withPassword("test-password");
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<byte[]> send(HttpClient client, String method, String path, byte[] body,
            String contentType, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (contentType != null) request.header("Content-Type", contentType);
        if (csrf) {
            var token = mapper.readTree(send(client, "GET", "/api/auth/csrf", null, null, false).body());
            request.header(token.get("headerName").asText(), token.get("token").asText());
        }
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> json(HttpClient client, String path, Map<String, Object> body, boolean csrf) throws Exception {
        return send(client, "POST", path, mapper.writeValueAsBytes(body), "application/json", csrf);
    }

    private void login(HttpClient client, String name) throws Exception {
        assertEquals(201, json(client, "/api/auth/register", Map.of("username", name,
                "password", "AcceptancePass123!", "displayName", "验收教师"), false).statusCode());
        assertEquals(200, json(client, "/api/auth/login", Map.of("username", name,
                "password", "AcceptancePass123!"), false).statusCode());
    }

    @Test
    void importedAndEditedVersionsDownloadCorrectlyAndStayPrivateOverRealHttp() throws Exception {
        var alice = client();
        var bob = client();
        assertEquals(401, send(alice, "GET", "/api/platform/lessons", null, null, false).statusCode());
        login(alice, "alice_" + UUID.randomUUID().toString().substring(0, 8));
        login(bob, "bob_" + UUID.randomUUID().toString().substring(0, 8));
        byte[] original;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("教学目标：理解面积。");
            var table = doc.createTable(2, 3);
            table.getRow(0).getCell(0).setText("教师活动");
            table.getRow(0).getCell(1).setText("学生活动");
            table.getRow(0).getCell(2).setText("评价");
            table.getRow(1).getCell(0).setText("提出面积问题");
            table.getRow(1).getCell(1).setText("合作测量");
            table.getRow(1).getCell(2).setText("观察解释");
            doc.createParagraph().createRun().setText("作业：测量课桌。");
            doc.write(out);
            original = out.toByteArray();
        }
        String boundary = "acceptance-" + UUID.randomUUID();
        var multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"acceptance.docx\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(original);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var created = send(alice, "POST", "/api/platform/lessons/imports/docx", multipart.toByteArray(),
                "multipart/form-data; boundary=" + boundary, true);
        assertEquals(201, created.statusCode(), new String(created.body(), StandardCharsets.UTF_8));
        var detail = mapper.readTree(created.body());
        String lesson = detail.get("lesson").get("id").asText();
        String version = detail.get("lesson").get("currentVersionId").asText();
        String base = "/api/platform/lessons/" + lesson;
        String oldVersion = base + "/versions/" + version;
        var structure = send(alice, "GET", oldVersion + "/document", null, null, false);
        assertEquals(200, structure.statusCode());
        assertEquals("VERIFIED", mapper.readTree(structure.body()).get("bindingStatus").asText());
        var exported = send(alice, "GET", oldVersion + "/export/docx", null, null, false);
        assertEquals(200, exported.statusCode());
        assertTrue(exported.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));
        try (var word = new XWPFDocument(new ByteArrayInputStream(exported.body()))) {
            assertEquals(1, word.getTables().size());
            assertEquals("合作测量", word.getTables().get(0).getRow(1).getCell(1).getText());
        }
        var edit = Map.<String, Object>of("expectedVersionId", version, "content", "手动修订后的唯一正文");
        assertEquals(403, json(alice, base + "/versions", edit, false).statusCode());
        var updated = json(alice, base + "/versions", edit, true);
        assertEquals(201, updated.statusCode(), new String(updated.body(), StandardCharsets.UTF_8));
        String next = mapper.readTree(updated.body()).get("lesson").get("currentVersionId").asText();
        assertNotEquals(version, next);
        assertEquals(409, json(alice, base + "/versions", edit, true).statusCode());
        var newer = send(alice, "GET", base + "/versions/" + next + "/export/docx", null, null, false);
        assertEquals(200, newer.statusCode());
        try (var word = new XWPFDocument(new ByteArrayInputStream(newer.body()))) {
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().contains("手动修订后的唯一正文")));
        }
        assertArrayEquals(original, send(alice, "GET", base + "/source-document/file", null, null, false).body());
        for (String path : new String[]{base, oldVersion + "/document", oldVersion + "/export/docx",
                base + "/source-document/file", oldVersion + "/export/capabilities"}) {
            assertEquals(404, send(bob, "GET", path, null, null, false).statusCode(), path);
        }
        assertEquals(404, json(bob, base + "/versions", edit, true).statusCode());
        var capabilities = send(alice, "GET", oldVersion + "/export/capabilities", null, null, false);
        assertFalse(mapper.readTree(capabilities.body()).get("pdf").asBoolean());
        assertEquals(503, send(alice, "GET", oldVersion + "/export/pdf", null, null, false).statusCode());
        assertEquals(204, send(alice, "POST", "/api/auth/logout", null, null, false).statusCode());
        assertEquals(401, send(alice, "GET", base, null, null, false).statusCode());
    }
}
