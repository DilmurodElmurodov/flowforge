package com.flowforge.modules.workflow.model;

/** Supported step kinds. Each value is backed by exactly one {@code StepExecutor} strategy bean. */
public enum StepType {
    /** Pauses the execution until a human approves or rejects. Outcomes: {@code APPROVED}, {@code REJECTED}. */
    APPROVAL,
    /** Sends an e-mail through the configured gateway. Outcome: {@code SUCCESS}. */
    EMAIL,
    /** Calls an external HTTP endpoint. Outcome: {@code SUCCESS} on 2xx. */
    HTTP_WEBHOOK
}
