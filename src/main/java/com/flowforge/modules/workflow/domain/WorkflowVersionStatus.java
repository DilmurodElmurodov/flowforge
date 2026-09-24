package com.flowforge.modules.workflow.domain;

/** Lifecycle of a version: DRAFT (mutable) -> PUBLISHED (executable, immutable) -> DEPRECATED (superseded). */
public enum WorkflowVersionStatus {
    DRAFT, PUBLISHED, DEPRECATED
}
