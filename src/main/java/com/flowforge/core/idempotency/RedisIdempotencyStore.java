package com.flowforge.core.idempotency;

import com.flowforge.core.common.JsonUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis implementation of {@link IdempotencyStore}.
 * <ul>
 *   <li>Lock: {@code SET idempotency:{key}:lock <token> NX PX <ttl>}</li>
 *   <li>Release: Lua compare-and-delete so a lock that expired and was re-acquired by another request is
 *       never deleted by the original owner.</li>
 *   <li>Response cache: {@code SET idempotency:{key} <json> PX <ttl>}</li>
 * </ul>
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    static final String KEY_PREFIX = "idempotency:";
    private static final String LOCK_SUFFIX = ":lock";

    private static final RedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryLock(String key, String token, Duration ttl) {
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(key), token, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void unlock(String key, String token) {
        redis.execute(RELEASE_LOCK, List.of(lockKey(key)), token);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        String json = redis.opsForValue().get(recordKey(key));
        return json == null ? Optional.empty() : Optional.of(JsonUtils.fromJson(json, IdempotencyRecord.class));
    }

    @Override
    public void save(String key, IdempotencyRecord record, Duration ttl) {
        redis.opsForValue().set(recordKey(key), JsonUtils.toJson(record), ttl);
    }

    static String recordKey(String key) {
        return KEY_PREFIX + key;
    }

    static String lockKey(String key) {
        return KEY_PREFIX + key + LOCK_SUFFIX;
    }
}
