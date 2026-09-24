package com.flowforge.modules.execution.engine.executor;

import com.flowforge.modules.execution.engine.ExecutionContext;
import com.flowforge.modules.execution.engine.ExecutionResult;
import com.flowforge.modules.execution.engine.StepExecutor;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sends an e-mail. Config: {@code to}, {@code subject} (required), {@code body} (optional); all support
 * {@code ${path}} placeholders resolved against the execution context.
 */
@Component
public class EmailStepExecutor implements StepExecutor {

    private final EmailGateway gateway;

    public EmailStepExecutor(EmailGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public StepType type() {
        return StepType.EMAIL;
    }

    @Override
    public ExecutionResult execute(StepDefinition step, ExecutionContext context) {
        String to = TemplateResolver.render(step.requiredConfig("to"), context);
        String subject = TemplateResolver.render(step.requiredConfig("subject"), context);
        String body = TemplateResolver.render(step.configValue("body", ""), context);
        if (to.isBlank()) {
            return ExecutionResult.failure("Recipient resolved to an empty address");
        }
        String messageId = gateway.send(to, subject, body);
        return ExecutionResult.success(Map.of("messageId", messageId, "to", to));
    }
}
