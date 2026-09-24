package com.flowforge.core.security.jwt;

import com.flowforge.core.common.ProblemResponseWriter;
import com.flowforge.core.security.UserPrincipal;
import com.flowforge.core.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless bearer-token authentication. On a valid token the {@link UserPrincipal} is placed into the
 * {@link SecurityContextHolder}. The filter also reconciles tenancy:
 * <ul>
 *   <li>if no tenant header was supplied, the tenant from the token is bound;</li>
 *   <li>if a header was supplied and disagrees with the token, the request is rejected with 403.</li>
 * </ul>
 * Invalid or absent tokens simply leave the request unauthenticated; the entry point produces the 401.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MDC_USER = "userId";

    private final JwtTokenProvider tokenProvider;
    private final ProblemResponseWriter problems;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, ProblemResponseWriter problems) {
        this.tokenProvider = tokenProvider;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UserPrincipal> principal = extractToken(request).flatMap(tokenProvider::parse);
        if (principal.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        UserPrincipal user = principal.get();
        Optional<UUID> headerTenant = TenantContext.get();
        if (headerTenant.isPresent() && !headerTenant.get().equals(user.getTenantId())) {
            log.warn("Tenant mismatch: header={} token={} user={}", headerTenant.get(), user.getTenantId(), user.getId());
            problems.write(response, HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                    "The tenant header does not match the authenticated tenant", request.getRequestURI());
            return;
        }
        TenantContext.set(user.getTenantId());
        MDC.put(MDC_USER, user.getId().toString());
        try {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER);
        }
    }

    private static Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX) && header.length() > BEARER_PREFIX.length()) {
            return Optional.of(header.substring(BEARER_PREFIX.length()).trim());
        }
        return Optional.empty();
    }
}
