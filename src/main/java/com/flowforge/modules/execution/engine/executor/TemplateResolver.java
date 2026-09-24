package com.flowforge.modules.execution.engine.executor;

import com.flowforge.modules.execution.engine.ExecutionContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code ${path}} interpolation against the execution context, e.g.
 * {@code "Hello ${input.customer.name}"}. Unresolved placeholders are rendered as empty strings so a missing
 * optional field never breaks a notification.
 */
public final class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.\\-]+)}");

    private TemplateResolver() {
    }

    public static String render(String template, ExecutionContext context) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = context.lookup(matcher.group(1)).map(String::valueOf).orElse("");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
