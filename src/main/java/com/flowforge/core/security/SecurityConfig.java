package com.flowforge.core.security;

import com.flowforge.core.common.ProblemResponseWriter;
import com.flowforge.core.security.jwt.JwtAccessDeniedHandler;
import com.flowforge.core.security.jwt.JwtAuthenticationEntryPoint;
import com.flowforge.core.security.jwt.JwtAuthenticationFilter;
import com.flowforge.core.security.jwt.JwtTokenProvider;
import com.flowforge.core.tenant.TenantFilter;
import com.flowforge.core.tenant.TenantResolver;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless, JWT-based security chain.
 *
 * <pre>
 *   request -> TenantFilter -> JwtAuthenticationFilter -> (authorisation) -> controller
 * </pre>
 * Filters are created here (not as {@code @Component}) and explicitly excluded from the servlet container so
 * they run exactly once, inside the security chain, where the tenant context is guaranteed to be cleared.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Open for developer access; restrict actuator and API docs (or move them to a management port) in production. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/", "/error",
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
            "/actuator", "/actuator/**",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TenantFilter tenantFilter,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   JwtAuthenticationEntryPoint entryPoint,
                                                   JwtAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(cto -> { }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public TenantFilter tenantFilter(TenantResolver resolver, ProblemResponseWriter problems) {
        return new TenantFilter(resolver, problems);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider provider, ProblemResponseWriter problems) {
        return new JwtAuthenticationFilter(provider, problems);
    }

    /** Prevent Boot from registering the filters a second time on the servlet container. */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter filter) {
        FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Delegating encoder: bcrypt by default, but hashes are self-describing so algorithms can be upgraded.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
