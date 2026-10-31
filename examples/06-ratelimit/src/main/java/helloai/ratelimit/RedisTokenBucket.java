package helloai.ratelimit;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 多实例部署时必须用这个版本：令牌的「取」和「补」在 Redis 里由 Lua 原子完成。
 *
 * <p>进程内实现只能保护单个实例 —— 三台机器各有一份桶，等于把额度放大了三倍。
 */
@Component
class RedisTokenBucket implements TokenBucket {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final int capacity;
    private final double refillPerSec;

    RedisTokenBucket(StringRedisTemplate redis, LimiterProperties props) {
        this.redis = redis;
        this.capacity = props.capacity();
        this.refillPerSec = props.refillPerSec();

        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("bucket.lua"));
        s.setResultType(Long.class);
        this.script = s;
    }

    @Override
    public boolean tryAcquire(String key, int cost) {
        Long allowed = redis.execute(
            script,
            List.of("rl:" + key),
            String.valueOf(capacity),
            String.valueOf(refillPerSec),
            String.valueOf(System.currentTimeMillis()),
            String.valueOf(cost)
        );
        return allowed != null && allowed == 1L;
    }

    void reset(String key) {
        redis.delete("rl:" + key);
    }

    /** 给接口层用：Redis 不通的时候要有话可说，而不是抛一堆堆栈。 */
    static boolean isConnectivityProblem(Throwable e) {
        return e instanceof org.springframework.data.redis.RedisConnectionFailureException
            || (e.getCause() != null && e.getCause() instanceof java.net.ConnectException);
    }

    @SuppressWarnings("unused")
    private static long nowMillis() {
        return TimeUnit.MILLISECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
    }
}