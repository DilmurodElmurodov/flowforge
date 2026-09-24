package com.flowforge.modules.workflow.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flowforge.core.common.JsonUtils;
import com.flowforge.core.common.exception.ValidationException;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import com.flowforge.modules.workflow.model.WorkflowDefinitionException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates the declarative JSON workflow schema into a {@link WorkflowDefinition}.
 *
 * <p>Structural validation performed:
 * <ul>
 *   <li>at least one step, every step has an id and a known type;</li>
 *   <li>step ids are unique;</li>
 *   <li>every transition targets an existing step id or {@code END};</li>
 *   <li>every step is reachable from the first step (dead branches are rejected);</li>
 *   <li>type-specific required configuration (e.g. {@code url} for webhooks) is present.</li>
 * </ul>
 */
@Component
public class WorkflowDefinitionParser {

    public WorkflowDefinition parse(Map<String, Object> raw) {
        WorkflowDefinition definition;
        try {
            definition = JsonUtils.convert(raw, WorkflowDefinition.class);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Workflow definition is malformed: "
                    + NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        }
        List<String> violations = validate(definition);
        if (!violations.isEmpty()) {
            throw new ValidationException("Workflow definition is invalid", violations);
        }
        return definition;
    }

    public WorkflowDefinition parse(String json) {
        return parse(JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() { }));
    }

    /** Lenient variant used at execution time for definitions that were validated when they were stored. */
    public WorkflowDefinition parseTrusted(Map<String, Object> raw) {
        try {
            return JsonUtils.convert(raw, WorkflowDefinition.class);
        } catch (IllegalArgumentException e) {
            throw new WorkflowDefinitionException("Stored workflow definition can no longer be parsed", e);
        }
    }

    List<String> validate(WorkflowDefinition definition) {
        List<String> violations = new ArrayList<>();
        if (definition.steps().isEmpty()) {
            violations.add("steps: at least one step is required");
            return violations;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            StepDefinition step = definition.steps().get(i);
            String prefix = "steps[" + i + "]";
            if (step.id() == null || step.id().isBlank()) {
                violations.add(prefix + ".id: must not be blank");
                continue;
            }
            if (!ids.add(step.id())) {
                violations.add(prefix + ".id: duplicate step id '" + step.id() + "'");
            }
            if (step.type() == null) {
                violations.add(prefix + ".type: must be one of APPROVAL, EMAIL, HTTP_WEBHOOK");
            }
            validateConfig(step, prefix, violations);
        }
        if (!violations.isEmpty()) {
            return violations;
        }
        for (int i = 0; i < definition.steps().size(); i++) {
            StepDefinition step = definition.steps().get(i);
            step.transitions().forEach((outcome, target) -> {
                if (!StepDefinition.END.equals(target) && !ids.contains(target)) {
                    violations.add("steps[" + step.id() + "].transitions." + outcome
                            + ": unknown target step '" + target + "'");
                }
            });
        }
        if (violations.isEmpty()) {
            Set<String> unreachable = new HashSet<>(ids);
            unreachable.removeAll(reachableFrom(definition));
            unreachable.stream().sorted().forEach(id -> violations.add("steps[" + id + "]: unreachable from the first step"));
        }
        return violations;
    }

    private static void validateConfig(StepDefinition step, String prefix, List<String> violations) {
        if (step.type() == null) {
            return;
        }
        switch (step.type()) {
            case HTTP_WEBHOOK -> {
                if (missing(step, "url")) {
                    violations.add(prefix + ".config.url: required for HTTP_WEBHOOK steps");
                }
            }
            case EMAIL -> {
                if (missing(step, "to")) {
                    violations.add(prefix + ".config.to: required for EMAIL steps");
                }
                if (missing(step, "subject")) {
                    violations.add(prefix + ".config.subject: required for EMAIL steps");
                }
            }
            case APPROVAL -> {
                if (missing(step, "approverRole")) {
                    violations.add(prefix + ".config.approverRole: required for APPROVAL steps");
                }
            }
        }
    }

    private static boolean missing(StepDefinition step, String key) {
        Object value = step.config().get(key);
        return value == null || value.toString().isBlank();
    }

    /** Breadth-first traversal following explicit transitions and the implicit SUCCESS fall-through. */
    private static Set<String> reachableFrom(WorkflowDefinition definition) {
        Set<String> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int index = queue.poll();
            StepDefinition step = definition.steps().get(index);
            if (!visited.add(step.id())) {
                continue;
            }
            for (String target : step.transitions().values()) {
                if (!StepDefinition.END.equals(target)) {
                    queue.add(definition.indexOf(target));
                }
            }
            if (!step.transitions().containsKey(StepDefinition.OUTCOME_SUCCESS) && index + 1 < definition.steps().size()) {
                queue.add(index + 1);
            }
        }
        return visited;
    }
}
