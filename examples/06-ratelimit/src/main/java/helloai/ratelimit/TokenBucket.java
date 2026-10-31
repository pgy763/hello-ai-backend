package helloai.ratelimit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 令牌桶接口。三种实现：正确的进程内版本、故意写错的版本、Redis 版本。 */
interface TokenBucket {
    boolean tryAcquire(String key, int cost);
}

/**
 * 进程内正确实现：判断和扣减在同一个临界区里完成。
 *
 * <p>这是一个单实例版本的「Lua 脚本」—— 进程内用锁保证原子，跨进程用 Redis 保证原子，
 * 本质是同一件事：<b>读、算、写不能被别人插进来</b>。
 */
@org.springframework.stereotype.Component
class LocalTokenBucket implements TokenBucket {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSec;

    LocalTokenBucket(LimiterProperties props) {
        this.capacity = props.capacity();
        this.refillPerSec = props.refillPerSec();
    }

    @Override
    public boolean tryAcquire(String key, int cost) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return bucket.tryTake(System.nanoTime(), refillPerSec, cost);
    }

    void reset() {
        buckets.clear();
    }

    static final class Bucket {

        private double tokens;
        private long lastRefillNanos;
        private final int capacity;

        Bucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        /**
         * 只留这一个入口：回填、判断、扣减全在锁里。
         *
         * <p>反例：把「判断」放在锁外、只给「写入」加锁 —— 那样仍然会超发，只是超得更少。
         */
        synchronized boolean tryTake(long now, double refillPerSec, int cost) {
            double filled = Math.min(capacity, tokens + (now - lastRefillNanos) / 1_000_000_000.0 * refillPerSec);
            tokens = filled;
            lastRefillNanos = now;

            if (filled < cost) {
                return false;
            }
            tokens = filled - cost;
            return true;
        }
    }
}

/**
 * 故意写错的版本：读 → 算 → 写三步分开。
 *
 * <p>留着它是为了让你亲眼看见「少一次原子操作会超发多少」，
 * 生产代码里绝对不要出现。
 */
@org.springframework.stereotype.Component
class NaiveTokenBucket implements TokenBucket {

    private final Map<String, Naive> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSec;

    NaiveTokenBucket(LimiterProperties props) {
        this.capacity = props.capacity();
        this.refillPerSec = props.refillPerSec();
    }

    @Override
    public boolean tryAcquire(String key, int cost) {
        Naive bucket = buckets.computeIfAbsent(key, k -> new Naive(capacity));

        long now = System.nanoTime();
        double tokens = bucket.tokens + (now - bucket.lastRefillNanos) / 1_000_000_000.0 * refillPerSec; // ① 读
        tokens = Math.min(capacity, tokens);
        if (tokens < cost) {
            return false;
        }

        // ② 算（真实系统里这个窗口由 GC、线程调度、网络抖动提供；这里人为放大到 0.2ms）
        try {
            Thread.sleep(0, 200_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        bucket.tokens = tokens - cost;   // ③ 写
        bucket.lastRefillNanos = now;
        return true;
    }

    void reset() {
        buckets.clear();
    }

    static final class Naive {

        volatile double tokens;
        volatile long lastRefillNanos;

        Naive(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }
    }
}

/** 观测用：把每一条时间线记下来，接口一次返回。 */
@org.springframework.stereotype.Component
class Timeline {

    private final List<String> events = new ArrayList<>();

    synchronized void add(String event) {
        events.add(event);
    }

    synchronized List<String> snapshot() {
        return List.copyOf(events);
    }

    synchronized void clear() {
        events.clear();
    }
}