package com.flowforge.modules.execution.engine.executor;

import com.flowforge.modules.execution.engine.ExecutionContext;
import com.flowforge.modules.execution.engine.ExecutionResult;
import com.flowforge.modules.execution.engine.StepExecutor;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;
import org.springframework.stereotype.Component;

/**
 * Human-in-the-loop step. On first execution it parks the run ({@code WAITING}); the decision is later injected by
 * {@code ExecutionService.approve} which resumes the engine with the outcome {@code APPROVED} or {@code REJECTED}.
 *
 * <p>Config: {@code approverRole} (required) - role name whose members may decide; {@code prompt} (optional).
 */
@Component
public class ApprovalStepExecutor implements StepExecutor {

    public static final String CONFIG_APPROVER_ROLE = "approverRole";
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_REJECTED = "REJECTED";

    @Override
    public StepType type() {
        return StepType.APPROVAL;
    }

    @Override
    public ExecutionResult execute(StepDefinition step, ExecutionContext context) {
        String role = step.requiredConfig(CONFIG_APPROVER_ROLE);
        String prompt = TemplateResolver.render(step.configValue("prompt", "Approval required"), context);
        return ExecutionResult.waiting("Awaiting decision from role '" + role + "': " + prompt);
    }
}
