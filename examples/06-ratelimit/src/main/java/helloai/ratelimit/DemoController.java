package helloai.ratelimit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final LocalTokenBucket local;
    private final NaiveTokenBucket naive;
    private final RedisTokenBucket redis;
    private final LimiterProperties props;
    private final CircuitBreaker breaker;
    private final FakeUpstream upstream;
    private final Timeline timeline;

    public DemoController(LocalTokenBucket local, NaiveTokenBucket naive, RedisTokenBucket redis,
                          LimiterProperties props, CircuitBreaker breaker, FakeUpstream upstream,
                          Timeline timeline) {
        this.local = local;
        this.naive = naive;
        this.redis = redis;
        this.props = props;
        this.breaker = breaker;
        this.upstream = upstream;
        this.timeline = timeline;
    }

    /**
     * 并发打桶，看放过去多少个。
     *
     * <p>{@code impl} 取 {@code atomic}（正确）、{@code naive}（故意写错）、{@code redis}（Lua）。
     */
    @GetMapping("/api/burn")
    public Map<String, Object> burn(
        @RequestParam(defaultValue = "atomic") String impl,
        @RequestParam(defaultValue = "40") int n,
        @RequestParam(defaultValue = "true") boolean reset
    ) throws InterruptedException {
        if (reset) {
            local.reset();
            naive.reset();
            if ("redis".equals(impl)) {
                redis.reset("demo");
            }
        }

        TokenBucket bucket = switch (impl) {
            case "naive" -> naive;
            case "redis" -> redis;
            default -> local;
        };

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 64));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger broken = new AtomicInteger();

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (bucket.tryAcquire("demo", 1)) {
                        passed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    broken.incrementAndGet();
                    log.warn("请求异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("impl", impl);
        result.put("concurrent", n);
        result.put("capacity", props.capacity());
        result.put("refillPerSec", props.refillPerSec());
        result.put("passed", passed.get());
        result.put("rejected", rejected.get());
        result.put("errors", broken.get());
        result.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000L);
        result.put("oversold", Math.max(0, passed.get() - props.capacity()));
        if (broken.get() > 0 && !"redis".equals(impl)) {
            result.put("note", "有异常，看日志");
        }
        return result;
    }

    /** 熔断器自检：走一遍 闭 → 开 → 半开 → 闭。 */
    @GetMapping("/api/cb/selftest")
    public CbReport cbSelftest() throws InterruptedException {
        breaker.reset();
        upstream.reset();
        timeline.clear();
        List<String> steps = new ArrayList<>();

        upstream.setHealthy(false);
        for (int i = 0; i < breaker.failureThreshold() + 3; i++) {
            steps.add(attempt("上游持续失败期"));
        }

        int callsBeforeRejectPhase = upstream.calls();
        long rejectStart = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            steps.add(attempt("熔断期内"));
        }
        long rejectAvgMicros = (System.nanoTime() - rejectStart) / 5 / 1_000;
        int callsAfterRejectPhase = upstream.calls();

        Thread.sleep(breaker.openMillis() + 100);
        steps.add(attempt("冷却后探测（上游仍坏）"));

        upstream.setHealthy(true);
        Thread.sleep(breaker.openMillis() + 100);
        steps.add(attempt("上游恢复后探测"));

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("upstreamCallsTotal", upstream.calls());
        counters.put("upstreamCallsDuringOpen", callsAfterRejectPhase - callsBeforeRejectPhase);
        counters.put("rejectAvgMicros", rejectAvgMicros);

        return new CbReport(breaker.state().name(), breaker.failureThreshold(), breaker.openMillis(),
            steps, counters, timeline.snapshot());
    }

    private String attempt(String phase) {
        long t0 = System.nanoTime();
        boolean allowed = breaker.tryAcquire();
        long micros = (System.nanoTime() - t0) / 1_000;

        if (!allowed) {
            return phase + "：拒绝（" + micros + "µs，未打上游）";
        }
        boolean ok = upstream.call();
        if (ok) {
            breaker.onSuccess();
        } else {
            breaker.onFailure();
        }
        return phase + "：放行，上游" + (ok ? "成功" : "失败") + "（" + micros + "µs）→ "
            + breaker.state();
    }

    @GetMapping("/api/cb/state")
    public Map<String, Object> cbState() {
        return Map.of(
            "state", breaker.state().name(),
            "consecutiveFailures", breaker.consecutiveFailures(),
            "upstreamCalls", upstream.calls()
        );
    }

    @GetMapping("/api/config")
    public LimiterProperties config() {
        return props;
    }

    public record CbReport(String finalState, int failureThreshold, long openMillis,
                           List<String> steps, Map<String, Object> counters, List<String> timeline) {}
}