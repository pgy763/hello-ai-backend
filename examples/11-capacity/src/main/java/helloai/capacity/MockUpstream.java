package helloai.capacity;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 假上游：只有 8 个「槽位」，每个请求占 400ms。
 *
 * <p>这就是真实的 LLM 服务：**并发能力由厂商那边决定，不由你的机器决定。**
 * 你堆再多 Tomcat 线程，也只能排队。
 */
@Component
class MockUpstream {

    private volatile Semaphore slots = new Semaphore(8);
    private volatile int latencyMs = 400;
    private volatile int rpmLimit = 100_000;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowCount = new AtomicInteger();

    record Reply(String text, long waitedMs) {}

    /** 并发槽位满了就排队；排太久（6 秒）就当超时。 */
    Reply call() throws InterruptedException {
        long t0 = System.nanoTime();
        if (!slots.tryAcquire(6, TimeUnit.SECONDS)) {
            throw new Overloaded("上游排队超过 6 秒");
        }
        try {
            checkRpm();
            inFlight.incrementAndGet();
            Thread.sleep(latencyMs);
            return new Reply("ok", (System.nanoTime() - t0) / 1_000_000L);
        } finally {
            inFlight.decrementAndGet();
            slots.release();
        }
    }

    /** 固定窗口 RPM：超出就 429，和真实厂商一样。 */
    private void checkRpm() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= 60_000) {
            windowStart.compareAndSet(start, now);
            windowCount.set(0);
        }
        if (windowCount.incrementAndGet() > rpmLimit) {
            throw new RateLimited("超过 " + rpmLimit + " RPM");
        }
    }

    void configure(int slots, int latencyMs, int rpmLimit) {
        this.slots = new Semaphore(slots);
        this.latencyMs = latencyMs;
        this.rpmLimit = rpmLimit;
        this.windowStart.set(System.currentTimeMillis());
        this.windowCount.set(0);
    }

    int inFlight() {
        return inFlight.get();
    }

    int slots() {
        return slots.availablePermits() + inFlight.get();
    }

    int latencyMs() {
        return latencyMs;
    }

    static class Overloaded extends RuntimeException {

        Overloaded(String msg) {
            super(msg);
        }
    }

    static class RateLimited extends RuntimeException {

        RateLimited(String msg) {
            super(msg);
        }
    }
}