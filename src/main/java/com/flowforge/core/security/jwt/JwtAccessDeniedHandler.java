package com.flowforge.core.security.jwt;

import com.flowforge.core.common.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Produces a problem+json 403 when an authenticated user lacks the required authority. */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problems;

    public JwtAccessDeniedHandler(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        problems.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this operation", request.getRequestURI());
    }
}
