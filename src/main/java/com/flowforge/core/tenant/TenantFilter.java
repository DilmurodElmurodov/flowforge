package com.flowforge.core.tenant;

import com.flowforge.core.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the tenant resolved from the request (header, ...) to {@link TenantContext} for the lifetime of the
 * request and guarantees it is cleared afterwards, even if downstream code fails.
 *
 * <p>Registered inside the Spring Security chain right before {@code JwtAuthenticationFilter}, which may
 * additionally bind the tenant from the JWT when no header is present and verifies that header and token
 * agree.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantResolver resolver;
    private final ProblemResponseWriter problems;

    public TenantFilter(TenantResolver resolver, ProblemResponseWriter problems) {
        this.resolver = resolver;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolver.resolve(request).ifPresent(TenantContext::set);
            chain.doFilter(request, response);
        } catch (TenantResolutionException ex) {
            problems.write(response, HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), request.getRequestURI());
        } finally {
            TenantContext.clear();
        }
    }
}
