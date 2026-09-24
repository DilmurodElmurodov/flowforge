package com.flowforge.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.modules.execution.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Redis-backed {@code @Idempotent} guard against the real HTTP stack. */
class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired WorkflowExecutionRepository executions;

    private UUID publishedWorkflow(String token) {
        UUID workflowId = createWorkflow(token, "idem-" + UUID.randomUUID());
        createAndPublishVersion(token, workflowId, definition("idem", List.of(
                step("notify", "EMAIL", Map.of("to", "x@acme.test", "subject", "s"), null))));
        return workflowId;
    }

    @Test
    void sameKeyAndPayloadReplaysCachedResponseWithoutSecondExecution() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = publishedWorkflow(token);
        String key = "idem-" + UUID.randomUUID();
        Map<String, Object> input = Map.of("orderId", 7);

        ResponseEntity<JsonNode> first = startExecution(token, key, workflowId, input);
        ResponseEntity<JsonNode> second = startExecution(token, key, workflowId, input);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(second.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(executions.findByTenantIdAndIdempotencyKey(TENANT_ID, key)).isPresent();
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = publishedWorkflow(token);
        String key = "idem-" + UUID.randomUUID();

        assertThat(startExecution(token, key, workflowId, Map.of("a", 1)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> mismatch = startExecution(token, key, workflowId, Map.of("a", 2));

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatch.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void missingKeyIsRejectedBeforeBusinessLogicRuns() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = publishedWorkflow(token);

        ResponseEntity<JsonNode> response = post("/api/v1/executions",
                Map.of("workflowId", workflowId, "input", Map.of()), bearer(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(executions.countByTenantIdAndWorkflowId(TENANT_ID, workflowId)).isZero();
    }

    @Test
    void concurrentRequestsWithSameKeyProduceExactlyOneExecution() throws Exception {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = publishedWorkflow(token);
        String key = "race-" + UUID.randomUUID();
        Map<String, Object> input = Map.of("orderId", 99);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> startExecution(token, key, workflowId, input));
            }
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : pool.invokeAll(calls)) {
                responses.add(future.get());
            }
            // Every caller either got the (possibly replayed) 202 or a 409 because the first call was still running.
            assertThat(responses).allMatch(r -> r.getStatusCode() == HttpStatus.ACCEPTED || r.getStatusCode() == HttpStatus.CONFLICT);
            Set<String> ids = responses.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED)
                    .map(r -> r.getBody().get("id").asText())
                    .collect(Collectors.toSet());
            assertThat(ids).hasSize(1);
            assertThat(executions.countByTenantIdAndWorkflowId(TENANT_ID, workflowId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
