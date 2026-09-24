package com.flowforge.modules.execution.engine.executor;

import com.flowforge.core.common.JsonUtils;
import com.flowforge.modules.execution.ExecutionProperties;
import com.flowforge.modules.execution.engine.ExecutionContext;
import com.flowforge.modules.execution.engine.ExecutionResult;
import com.flowforge.modules.execution.engine.StepExecutor;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls an external HTTP endpoint. Config:
 * <ul>
 *   <li>{@code url} (required), {@code method} (default POST)</li>
 *   <li>{@code headers} - map of static headers</li>
 *   <li>{@code body} - JSON object template; when absent the whole execution context is posted</li>
 * </ul>
 * A 2xx response yields {@code SUCCESS} with {@code statusCode} and (truncated) {@code body} in the output.
 * Any other status or a transport error yields a failure, which the engine retries per the step's policy.
 * Trace context is propagated automatically through the observation-instrumented {@link RestClient}.
 */
@Component
public class HttpWebhookStepExecutor implements StepExecutor {

    private final RestClient restClient;
    private final int maxResponseBytes;

    public HttpWebhookStepExecutor(RestClient.Builder builder, ExecutionProperties properties) {
        ExecutionProperties.Webhook webhook = properties.webhook();
        this.restClient = builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(webhook.connectTimeout())
                        .withReadTimeout(webhook.readTimeout())))
                .build();
        this.maxResponseBytes = webhook.maxResponseBytes();
    }

    @Override
    public StepType type() {
        return StepType.HTTP_WEBHOOK;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutionResult execute(StepDefinition step, ExecutionContext context) {
        String url = TemplateResolver.render(step.requiredConfig("url"), context);
        HttpMethod method = HttpMethod.valueOf(step.configValue("method", "POST").toUpperCase());
        Map<String, Object> headers = step.configValue("headers", Map.of());
        Object bodyTemplate = step.config().get("body");
        Object body = bodyTemplate == null ? context.toPersistent() : renderBody(bodyTemplate, context);

        try {
            RestClient.RequestBodySpec request = restClient.method(method).uri(url);
            headers.forEach((name, value) -> request.header(name, TemplateResolver.render(String.valueOf(value), context)));
            if (method != HttpMethod.GET && method != HttpMethod.DELETE) {
                request.contentType(MediaType.APPLICATION_JSON).body(JsonUtils.toJson(body));
            }
            ResponseEntity<String> response = request.retrieve()
                    .onStatus(status -> true, (req, res) -> { })   // never throw; inspect the status below
                    .toEntity(String.class);
            int status = response.getStatusCode().value();
            String responseBody = truncate(response.getBody());
            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("statusCode", status);
                output.put("body", responseBody);
                return ExecutionResult.success(output);
            }
            return ExecutionResult.failure("Webhook " + method + " " + url + " responded with HTTP " + status);
        } catch (ResourceAccessException e) {
            return ExecutionResult.failure("Webhook " + method + " " + url + " unreachable: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object renderBody(Object template, ExecutionContext context) {
        if (template instanceof String s) {
            return TemplateResolver.render(s, context);
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> rendered.put(k, renderBody(v, context)));
            return rendered;
        }
        if (template instanceof List<?> list) {
            return list.stream().map(v -> renderBody(v, context)).toList();
        }
        return template;
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > maxResponseBytes ? body.substring(0, maxResponseBytes) : body;
    }
}
