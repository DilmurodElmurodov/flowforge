package com.flowforge.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.modules.execution.repository.ExecutionStepRecordRepository;
import com.flowforge.modules.outbox.OutboxEvent;
import com.flowforge.modules.outbox.OutboxRepository;
import com.flowforge.modules.outbox.OutboxStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end: REST -> engine -> outbox -> Kafka -> notification consumer. */
class WorkflowExecutionFlowIT extends AbstractIntegrationTest {

    @Autowired OutboxRepository outbox;
    @Autowired ExecutionStepRecordRepository stepRecords;

    @Test
    void executesLinearWorkflowPublishesOutboxEventsAndNotifies() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = createWorkflow(token, "order-flow-" + UUID.randomUUID());
        createAndPublishVersion(token, workflowId, definition("order-flow", List.of(
                step("notify", "EMAIL", Map.of("to", "${input.customer.email}", "subject", "Order ${input.orderId}"), null),
                step("erp", "HTTP_WEBHOOK", Map.of("url", healthUrl(), "method", "GET"), null))));

        ResponseEntity<JsonNode> started = startExecution(token, "order-" + UUID.randomUUID(), workflowId,
                Map.of("orderId", 42, "customer", Map.of("email", "alice@acme.test")));
        assertThat(started.getStatusCode()).as(String.valueOf(started.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getHeaders().getLocation()).isNotNull();
        UUID executionId = UUID.fromString(started.getBody().get("id").asText());

        JsonNode completed = awaitExecutionStatus(token, executionId, "COMPLETED");
        assertThat(completed.path("completedAt").isMissingNode()).isFalse();
        assertThat(completed.path("context").path("steps").path("notify").path("output").path("to").asText())
                .isEqualTo("alice@acme.test");
        assertThat(completed.path("context").path("steps").path("erp").path("output").path("statusCode").asInt())
                .isEqualTo(200);

        // step audit trail
        JsonNode steps = get("/api/v1/executions/" + executionId + "/steps", bearer(token)).getBody();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).get("stepId").asText()).isEqualTo("notify");
        assertThat(steps.get(1).get("stepId").asText()).isEqualTo("erp");
        assertThat(steps.get(1).get("outcome").asText()).isEqualTo("SUCCESS");

        // transactional outbox: every state change produced an event and the publisher delivered all of them
        Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            List<OutboxEvent> events = outbox.findByAggregateIdOrderByCreatedAtAsc(executionId);
            assertThat(events).extracting(OutboxEvent::getEventType).containsExactly(
                    "EXECUTION_REQUESTED", "EXECUTION_STARTED", "EXECUTION_STEP_COMPLETED", "EXECUTION_COMPLETED");
            assertThat(events).allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED && e.getPublishedAt() != null);
            assertThat(events).allMatch(e -> TENANT_ID.equals(e.getTenantId()));
        });

        // Kafka consumer side-effect
        Awaitility.await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(notifications.forExecution(executionId))
                        .anyMatch(n -> n.subject().equals("Execution " + executionId + " completed")
                                && TENANT_ID.equals(n.tenantId())));
    }

    @Test
    void approvalStepParksExecutionUntilDecisionArrives() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = createWorkflow(token, "approval-flow-" + UUID.randomUUID());
        createAndPublishVersion(token, workflowId, definition("approval-flow", List.of(
                step("review", "APPROVAL", Map.of("approverRole", "ADMIN", "prompt", "Approve ${input.amount}?"),
                        Map.of("APPROVED", "notify", "REJECTED", "END")),
                step("notify", "EMAIL", Map.of("to", "requester@acme.test", "subject", "Approved"), null))));

        UUID executionId = UUID.fromString(startExecution(token, "appr-" + UUID.randomUUID(), workflowId,
                Map.of("amount", 1200)).getBody().get("id").asText());

        JsonNode waiting = awaitExecutionStatus(token, executionId, "WAITING");
        assertThat(waiting.get("currentStepId").asText()).isEqualTo("review");
        Awaitility.await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(notifications.forExecution(executionId))
                        .anyMatch(n -> n.recipient().equals("role:ADMIN") && n.subject().startsWith("Approval required")));

        ResponseEntity<JsonNode> decision = post("/api/v1/executions/" + executionId + "/approval",
                Map.of("decision", "APPROVED", "comment", "looks good"), bearer(token));
        assertThat(decision.getStatusCode()).as(String.valueOf(decision.getBody())).isEqualTo(HttpStatus.ACCEPTED);

        JsonNode completed = awaitExecutionStatus(token, executionId, "COMPLETED");
        assertThat(completed.path("context").path("variables").path("approval.review").path("decision").asText())
                .isEqualTo("APPROVED");
        JsonNode steps = get("/api/v1/executions/" + executionId + "/steps", bearer(token)).getBody();
        assertThat(steps).extracting(n -> n.get("stepId").asText() + ":" + n.get("status").asText())
                .containsExactly("review:WAITING", "review:COMPLETED", "notify:COMPLETED");

        // a second decision on a finished execution is rejected
        ResponseEntity<JsonNode> again = post("/api/v1/executions/" + executionId + "/approval",
                Map.of("decision", "REJECTED"), bearer(token));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectedApprovalFollowsEndTransition() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = createWorkflow(token, "reject-flow-" + UUID.randomUUID());
        createAndPublishVersion(token, workflowId, definition("reject-flow", List.of(
                step("review", "APPROVAL", Map.of("approverRole", "ADMIN"), Map.of("APPROVED", "notify", "REJECTED", "END")),
                step("notify", "EMAIL", Map.of("to", "x@acme.test", "subject", "Approved"), null))));
        UUID executionId = UUID.fromString(startExecution(token, "rej-" + UUID.randomUUID(), workflowId, Map.of())
                .getBody().get("id").asText());
        awaitExecutionStatus(token, executionId, "WAITING");

        post("/api/v1/executions/" + executionId + "/approval", Map.of("decision", "REJECTED"), bearer(token));

        awaitExecutionStatus(token, executionId, "COMPLETED");
        JsonNode steps = get("/api/v1/executions/" + executionId + "/steps", bearer(token)).getBody();
        assertThat(steps).extracting(n -> n.get("stepId").asText()).containsExactly("review", "review");
        assertThat(steps.get(1).get("outcome").asText()).isEqualTo("REJECTED");
    }

    @Test
    void failingWebhookIsRetriedThenExecutionFails() {
        String token = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowId = createWorkflow(token, "failing-flow-" + UUID.randomUUID());
        createAndPublishVersion(token, workflowId, definition("failing-flow", List.of(
                Map.of("id", "hook", "type", "HTTP_WEBHOOK",
                        "config", Map.of("url", "http://localhost:" + port + "/api/v1/does-not-exist", "method", "POST"),
                        "retry", Map.of("maxAttempts", 2, "backoffMillis", 50)))));
        UUID executionId = UUID.fromString(startExecution(token, "fail-" + UUID.randomUUID(), workflowId, Map.of())
                .getBody().get("id").asText());

        JsonNode failed = awaitExecutionStatus(token, executionId, "FAILED");
        assertThat(failed.get("failureReason").asText()).contains("HTTP 401");
        JsonNode steps = get("/api/v1/executions/" + executionId + "/steps", bearer(token)).getBody();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("attempts").asInt()).isEqualTo(2);
        assertThat(steps.get(0).get("status").asText()).isEqualTo("FAILED");

        Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(executionId))
                    .extracting(OutboxEvent::getEventType).contains("EXECUTION_FAILED");
            assertThat(notifications.forExecution(executionId))
                    .anyMatch(n -> n.subject().contains("FAILED"));
        });
    }
}
