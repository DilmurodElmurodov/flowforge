package com.flowforge.core.common;

import com.flowforge.core.common.exception.FlowForgeException;
import com.flowforge.core.common.exception.ValidationException;
import com.flowforge.core.tenant.TenantIsolationViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Translates exceptions into RFC 9457 problem responses. Domain exceptions carry their own status & code;
 * infrastructure exceptions are mapped conservatively so that internals never leak to API consumers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemResponseWriter problems;

    public GlobalExceptionHandler(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidation(ValidationException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request.getRequestURI());
        problem.setProperty("violations", ex.getViolations());
        return problem;
    }

    @ExceptionHandler(FlowForgeException.class)
    public ProblemDetail handleDomain(FlowForgeException ex, HttpServletRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("Domain failure [{}]: {}", ex.getCode(), ex.getMessage(), ex);
        } else {
            log.debug("Domain rejection [{}]: {}", ex.getCode(), ex.getMessage());
        }
        return problems.problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request.getRequestURI());
    }

    /**
     * A cross-tenant access attempt is reported as 404 on purpose: revealing that the resource exists in
     * another tenant would itself be an information leak.
     */
    @ExceptionHandler(TenantIsolationViolationException.class)
    public ProblemDetail handleTenantIsolation(TenantIsolationViolationException ex, HttpServletRequest request) {
        log.warn("Tenant isolation violation blocked: {}", ex.getMessage());
        return problems.problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found",
                request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        ProblemDetail problem = problems.problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", request.getRequestURI());
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<String> violations = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        ProblemDetail problem = problems.problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", request.getRequestURI());
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    public ProblemDetail handleMalformedRequest(Exception ex, HttpServletRequest request) {
        return problems.problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request could not be read: " + rootMessage(ex), request.getRequestURI());
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class, LockedException.class})
    public ProblemDetail handleBadCredentials(AuthenticationException ex, HttpServletRequest request) {
        return problems.problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED",
                "Invalid credentials or account not usable", request.getRequestURI());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problems.problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", ex.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problems.problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this operation", request.getRequestURI());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problems.problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently, please retry", request.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), rootMessage(ex));
        return problems.problem(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request conflicts with existing data", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problems.problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request.getRequestURI());
    }

    private static String rootMessage(Throwable t) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(t);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
