package com.flowforge.core.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage port for the idempotency guard. The default implementation is Redis-backed
 * ({@link RedisIdempotencyStore}); the abstraction keeps {@link IdempotencyAspect} unit-testable.
 */
public interface IdempotencyStore {

    /** Atomically acquires an execution lock (SETNX semantics). Returns {@code false} if already held. */
    boolean tryLock(String key, String token, Duration ttl);

    /** Releases the lock only if it is still owned by {@code token} (compare-and-delete). */
    void unlock(String key, String token);

    Optional<IdempotencyRecord> find(String key);

    void save(String key, IdempotencyRecord record, Duration ttl);
}
