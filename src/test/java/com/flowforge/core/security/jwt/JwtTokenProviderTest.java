package com.flowforge.core.security.jwt;

import com.flowforge.core.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final JwtProperties PROPS = new JwtProperties("unit-test-secret-unit-test-secret-0123456789",
            "flowforge", Duration.ofMinutes(15), Duration.ofDays(7));
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), "alice", null, true,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("WORKFLOW_EXECUTE")));

    @Test
    void roundTripsTenantUserAndAuthorities() {
        JwtTokenProvider provider = new JwtTokenProvider(PROPS, Clock.fixed(NOW, ZoneOffset.UTC));

        Optional<UserPrincipal> parsed = provider.parse(provider.createAccessToken(principal));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().getId()).isEqualTo(principal.getId());
        assertThat(parsed.get().getTenantId()).isEqualTo(principal.getTenantId());
        assertThat(parsed.get().getUsername()).isEqualTo("alice");
        assertThat(parsed.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "WORKFLOW_EXECUTE");
        assertThat(parsed.get().getPassword()).isNull();
    }

    @Test
    void rejectsExpiredToken() {
        String token = new JwtTokenProvider(PROPS, Clock.fixed(NOW, ZoneOffset.UTC)).createAccessToken(principal);
        JwtTokenProvider later = new JwtTokenProvider(PROPS, Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));

        assertThat(later.parse(token)).isEmpty();
    }

    @Test
    void rejectsTamperedOrForeignTokens() {
        JwtTokenProvider provider = new JwtTokenProvider(PROPS, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = provider.createAccessToken(principal);
        JwtTokenProvider otherKey = new JwtTokenProvider(new JwtProperties("another-secret-another-secret-0123456789",
                "flowforge", Duration.ofMinutes(15), Duration.ofDays(7)), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(provider.parse(token.substring(0, token.length() - 3) + "abc")).isEmpty();
        assertThat(otherKey.parse(token)).isEmpty();
        assertThat(provider.parse("not-a-jwt")).isEmpty();
    }
}
