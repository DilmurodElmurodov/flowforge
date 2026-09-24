package com.flowforge.core.security.jwt;

import com.flowforge.core.common.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Produces a problem+json 401 for unauthenticated access to protected resources. */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problems;

    public JwtAuthenticationEntryPoint(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"flowforge\"");
        problems.write(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "A valid bearer token is required", request.getRequestURI());
    }
}
