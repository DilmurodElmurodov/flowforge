package com.flowforge.modules.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Immutable, validated in-memory representation of a workflow version's JSON {@code definition}.
 * Construction is done by {@link com.flowforge.modules.workflow.parser.WorkflowDefinitionParser}, which is the
 * only place that accepts untrusted input.
 */
public record WorkflowDefinition(String name, String description, List<StepDefinition> steps) {

    @JsonCreator
    public WorkflowDefinition(@JsonProperty("name") String name,
                              @JsonProperty("description") String description,
                              @JsonProperty("steps") List<StepDefinition> steps) {
        this.name = name;
        this.description = description;
        this.steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public StepDefinition step(int index) {
        if (index < 0 || index >= steps.size()) {
            throw new WorkflowDefinitionException("Step index " + index + " is out of range (0.." + (steps.size() - 1) + ")");
        }
        return steps.get(index);
    }

    public int indexOf(String stepId) {
        return IntStream.range(0, steps.size())
                .filter(i -> steps.get(i).id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new WorkflowDefinitionException("Unknown step id '" + stepId + "'"));
    }

    public Map<String, StepDefinition> stepsById() {
        return steps.stream().collect(Collectors.toMap(StepDefinition::id, s -> s, (a, b) -> a));
    }

    /**
     * An explicit {@code transitions[outcome]} mapping wins ({@code END} completes the run); an unmapped
     * {@code SUCCESS} falls through to the next step in declaration order; any other unmapped outcome is a
     * definition error.
     */
    public Transition resolveTransition(int currentIndex, String outcome) {
        StepDefinition current = step(currentIndex);
        String target = current.transitionFor(outcome).orElse(null);
        if (target != null) {
            return StepDefinition.END.equals(target) ? Transition.COMPLETE : Transition.advance(indexOf(target));
        }
        if (StepDefinition.OUTCOME_SUCCESS.equals(outcome)) {
            return currentIndex + 1 < steps.size() ? Transition.advance(currentIndex + 1) : Transition.COMPLETE;
        }
        throw new WorkflowDefinitionException("Step '" + current.id() + "' has no transition for outcome '" + outcome + "'");
    }
}
