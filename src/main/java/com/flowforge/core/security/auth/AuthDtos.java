package com.flowforge.core.security.auth;

import jakarta.validation.constraints.NotBlank;

/** Request / response contracts of the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn);
        }
    }
}
