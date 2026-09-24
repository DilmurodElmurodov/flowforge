package com.flowforge.core.common;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Writes RFC 9457 {@link ProblemDetail} responses from places that run outside the MVC exception pipeline
 * (servlet filters, Spring Security entry points and access-denied handlers).
 */
@Component
public class ProblemResponseWriter {

    public static final String TYPE_PREFIX = "https://flowforge.io/problems/";

    public ProblemDetail problem(HttpStatus status, String code, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(TYPE_PREFIX + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        return problem;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String detail, String path)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonUtils.toJson(problem(status, code, detail, path)));
        response.getWriter().flush();
    }
}
