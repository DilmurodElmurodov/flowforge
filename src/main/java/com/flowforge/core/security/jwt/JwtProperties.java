package com.flowforge.core.security.jwt;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT settings ({@code flowforge.security.jwt.*}). The secret must be at least 256 bits (32 bytes) for HS256.
 */
@Validated
@ConfigurationProperties(prefix = "flowforge.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("flowforge") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl) {
}
