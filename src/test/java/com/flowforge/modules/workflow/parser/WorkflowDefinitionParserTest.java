package com.flowforge.modules.workflow.parser;

import com.flowforge.core.common.exception.ValidationException;
import com.flowforge.modules.workflow.model.Transition;
import com.flowforge.modules.workflow.model.StepType;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionParserTest {

    private final WorkflowDefinitionParser parser = new WorkflowDefinitionParser();

    @Test
    void parsesValidDefinitionAndResolvesTransitions() {
        WorkflowDefinition definition = parser.parse("""
                {
                  "name": "purchase",
                  "steps": [
                    {"id": "review", "type": "APPROVAL", "config": {"approverRole": "MANAGER"},
                     "transitions": {"APPROVED": "notify", "REJECTED": "END"}},
                    {"id": "notify", "type": "EMAIL", "config": {"to": "${input.email}", "subject": "Hi"}},
                    {"id": "hook", "type": "HTTP_WEBHOOK", "config": {"url": "http://x"}, "retry": {"maxAttempts": 3}}
                  ]
                }
                """);

        assertThat(definition.steps()).hasSize(3);
        assertThat(definition.step(0).type()).isEqualTo(StepType.APPROVAL);
        assertThat(definition.step(2).retry().maxAttempts()).isEqualTo(3);
        assertThat(definition.resolveTransition(0, "APPROVED")).isEqualTo(Transition.advance(1));
        assertThat(definition.resolveTransition(0, "REJECTED")).isEqualTo(Transition.COMPLETE);
        assertThat(definition.resolveTransition(1, "SUCCESS")).isEqualTo(Transition.advance(2));
        assertThat(definition.resolveTransition(2, "SUCCESS")).isEqualTo(Transition.COMPLETE);
    }

    @Test
    void rejectsEmptySteps() {
        assertThatThrownBy(() -> parser.parse("{\"name\":\"x\",\"steps\":[]}"))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .containsExactly("steps: at least one step is required"));
    }

    @Test
    void rejectsDuplicateIdsAndMissingConfig() {
        assertThatThrownBy(() -> parser.parse("""
                {"steps": [
                  {"id": "a", "type": "EMAIL", "config": {"to": "x"}},
                  {"id": "a", "type": "HTTP_WEBHOOK"}
                ]}
                """))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations()).containsExactlyInAnyOrder(
                        "steps[0].config.subject: required for EMAIL steps",
                        "steps[1].id: duplicate step id 'a'",
                        "steps[1].config.url: required for HTTP_WEBHOOK steps"));
    }

    @Test
    void rejectsUnknownTransitionTarget() {
        assertThatThrownBy(() -> parser.parse("""
                {"steps": [
                  {"id": "a", "type": "APPROVAL", "config": {"approverRole": "R"}, "transitions": {"APPROVED": "ghost"}}
                ]}
                """))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .containsExactly("steps[a].transitions.APPROVED: unknown target step 'ghost'"));
    }

    @Test
    void rejectsUnreachableSteps() {
        assertThatThrownBy(() -> parser.parse("""
                {"steps": [
                  {"id": "a", "type": "EMAIL", "config": {"to": "x", "subject": "s"}, "transitions": {"SUCCESS": "END"}},
                  {"id": "orphan", "type": "EMAIL", "config": {"to": "x", "subject": "s"}}
                ]}
                """))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .containsExactly("steps[orphan]: unreachable from the first step"));
    }

    @Test
    void rejectsUnknownStepType() {
        assertThatThrownBy(() -> parser.parse("{\"steps\": [{\"id\": \"a\", \"type\": \"TELEPORT\"}]}"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("malformed");
    }
}
