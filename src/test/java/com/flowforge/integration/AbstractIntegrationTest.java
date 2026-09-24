package com.flowforge.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application against real PostgreSQL, Redis and Kafka instances (Testcontainers).
 * Containers are started once per JVM and shared by every integration test class; the Spring context is
 * cached by the test framework.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final String ADMIN_USER = "admin";
    protected static final String ADMIN_PASSWORD = "Sup3r-Secret-Pass!";
    protected static final Duration TIMEOUT = Duration.ofSeconds(60);

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    static {
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected RecordingNotificationSender notifications;

    // ------------------------------------------------------------------ HTTP helpers

    protected String login(UUID tenantId, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-ID", tenantId.toString());
        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", password), headers), JsonNode.class);
        assertThat(response.getStatusCode()).as("login response: " + response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    protected HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected HttpHeaders bearerWithIdempotencyKey(String token, String key) {
        HttpHeaders headers = bearer(token);
        headers.set("X-Idempotency-Key", key);
        return headers;
    }

    protected ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), JsonNode.class);
    }

    // ------------------------------------------------------------------ domain helpers

    protected UUID createWorkflow(String token, String name) {
        ResponseEntity<JsonNode> response = post("/api/v1/workflows",
                Map.of("name", name, "description", "integration test"),
                bearerWithIdempotencyKey(token, "create-" + name));
        assertThat(response.getStatusCode()).as("create workflow: " + response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    protected int createAndPublishVersion(String token, UUID workflowId, Map<String, Object> definition) {
        ResponseEntity<JsonNode> created = post("/api/v1/workflows/" + workflowId + "/versions",
                Map.of("definition", definition), bearer(token));
        assertThat(created.getStatusCode()).as("create version: " + created.getBody()).isEqualTo(HttpStatus.CREATED);
        int number = created.getBody().get("versionNumber").asInt();
        ResponseEntity<JsonNode> published = post("/api/v1/workflows/" + workflowId + "/versions/" + number + "/publish",
                null, bearer(token));
        assertThat(published.getStatusCode()).as("publish: " + published.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().get("status").asText()).isEqualTo("PUBLISHED");
        return number;
    }

    protected ResponseEntity<JsonNode> startExecution(String token, String idempotencyKey, UUID workflowId,
                                                      Map<String, Object> input) {
        return post("/api/v1/executions", Map.of("workflowId", workflowId, "input", input),
                bearerWithIdempotencyKey(token, idempotencyKey));
    }

    protected JsonNode awaitExecutionStatus(String token, UUID executionId, String expected) {
        return Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).until(
                () -> get("/api/v1/executions/" + executionId, bearer(token)).getBody(),
                body -> body != null && expected.equals(body.path("status").asText()));
    }

    protected static Map<String, Object> step(String id, String type, Map<String, Object> config,
                                              Map<String, String> transitions) {
        return transitions == null
                ? Map.of("id", id, "type", type, "config", config)
                : Map.of("id", id, "type", type, "config", config, "transitions", transitions);
    }

    protected static Map<String, Object> definition(String name, List<Map<String, Object>> steps) {
        return Map.of("name", name, "steps", steps);
    }

    protected String healthUrl() {
        return "http://localhost:" + port + "/actuator/health";
    }
}
