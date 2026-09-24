package com.flowforge.core.security.token;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.security.jwt.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh-token lifecycle with rotation and reuse detection.
 * <ol>
 *   <li>{@link #issue} creates a new token family (login).</li>
 *   <li>{@link #rotate} exchanges a valid token for a fresh one inside the same family and revokes the old one.</li>
 *   <li>Presenting a token that was already rotated is treated as theft: the whole family is revoked.</li>
 * </ol>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of issuing / rotating: the raw token (returned to the client once) and its persisted record. */
    public record IssuedToken(String rawToken, RefreshToken record) {
    }

    @Transactional
    public IssuedToken issue(UUID tenantId, UUID userId) {
        return persist(tenantId, userId, UUID.randomUUID());
    }

    /**
     * Rotates a refresh token. Throws {@link BadCredentialsException} for unknown, expired or reused tokens.
     * Callers must verify that {@code record.userId} maps to an active account before minting an access token.
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public IssuedToken rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Unknown refresh token"));
        if (current.isRevoked()) {
            // Replay of a rotated token => the family is compromised; revoke everything.
            int revoked = repository.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {} (family {}), revoked {} token(s)",
                    current.getUserId(), current.getFamilyId(), revoked);
            throw new BadCredentialsException("Refresh token reuse detected");
        }
        if (current.isExpired(now)) {
            throw new BadCredentialsException("Refresh token expired");
        }
        IssuedToken next = persist(current.getTenantId(), current.getUserId(), current.getFamilyId());
        current.revoke(now, next.record().getId());
        repository.save(current);
        return next;
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** Housekeeping: purge tokens that expired more than a day ago. */
    @Scheduled(cron = "${flowforge.security.jwt.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }

    private IssuedToken persist(UUID tenantId, UUID userId, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken record = new RefreshToken(tenantId, userId, hash(raw), familyId,
                clock.instant().plus(properties.refreshTokenTtl()));
        return new IssuedToken(raw, repository.save(record));
    }

    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
