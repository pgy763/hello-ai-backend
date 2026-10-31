package helloai.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** 被保护的假上游。它只做两件事：记账、按开关决定成功还是失败。 */
@Component
class FakeUpstream {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean healthy = true;

    void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    boolean call() {
        calls.incrementAndGet();
        return healthy;
    }

    int calls() {
        return calls.get();
    }

    void reset() {
        calls.set(0);
        healthy = true;
    }
}