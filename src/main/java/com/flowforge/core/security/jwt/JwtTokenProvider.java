package com.flowforge.core.security.jwt;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and validates stateless HS256 access tokens.
 *
 * <p>Tokens embed the tenant ({@value #CLAIM_TENANT}) and the effective authorities ({@value #CLAIM_AUTHORITIES})
 * so request authorisation does not require a database round-trip. Authorities are re-evaluated when a new
 * token is minted (login / refresh), which bounds the staleness window to the access-token TTL.
 */
@Component
public class JwtTokenProvider {

    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_USERNAME = "uname";
    public static final String CLAIM_AUTHORITIES = "auth";
    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";

    private final JwtProperties properties;
    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public JwtTokenProvider(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String createAccessToken(UserPrincipal principal) {
        Instant now = clock.instant();
        Instant expiry = now.plus(properties.accessTokenTtl());
        List<String> authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(principal.getId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_TENANT, principal.getTenantId().toString())
                .claim(CLAIM_USERNAME, principal.getUsername())
                .claim(CLAIM_AUTHORITIES, authorities)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    /**
     * Validates signature, issuer, expiry and token type and rebuilds the principal from the claims.
     * Returns empty for any invalid token; callers must treat empty as "unauthenticated", never as an error.
     */
    public Optional<UserPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<String> authorities = claims.get(CLAIM_AUTHORITIES, List.class);
            return Optional.of(new UserPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_TENANT, String.class)),
                    claims.get(CLAIM_USERNAME, String.class),
                    null,
                    true,
                    authorities == null ? List.of()
                            : authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet())));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
