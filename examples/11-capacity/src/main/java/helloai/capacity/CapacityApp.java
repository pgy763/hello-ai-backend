package helloai.capacity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class CapacityApp {

    public static void main(String[] args) {
        SpringApplication.run(CapacityApp.class, args);
    }
}

/** 被压的那个接口：一次调用 = 一次「调模型」。 */
@RestController
class ChatController {

    private final MockUpstream upstream;
    private final LoadTester loadTester;
    private final AtomicInteger appConcurrency = new AtomicInteger();
    private final AtomicInteger appPeak = new AtomicInteger();

    ChatController(MockUpstream upstream, LoadTester loadTester) {
        this.upstream = upstream;
        this.loadTester = loadTester;
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat() {
        int now = appConcurrency.incrementAndGet();
        appPeak.accumulateAndGet(now, Math::max);
        try {
            MockUpstream.Reply reply;
            try {
                reply = upstream.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("请求被中断", e);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("queuedMs", reply.waitedMs());
            m.put("appInFlight", now);
            m.put("upstreamInFlight", upstream.inFlight());
            return m;
        } finally {
            appConcurrency.decrementAndGet();
        }
    }

    /** 调整上游参数。压测前先设好，保证每轮条件一致。 */
    @PostMapping("/api/config")
    public Map<String, Object> config(
        @RequestParam(defaultValue = "8") int slots,
        @RequestParam(defaultValue = "400") int latencyMs,
        @RequestParam(defaultValue = "100000") int rpm
    ) {
        upstream.configure(slots, latencyMs, rpm);
        appPeak.set(0);
        return Map.of("slots", slots, "latencyMs", latencyMs, "rpm", rpm);
    }

    /** 跑一轮压测：并发数 × 每线程请求数。 */
    @PostMapping("/api/load")
    public LoadTester.Report load(
        @RequestParam(defaultValue = "16") int concurrency,
        @RequestParam(defaultValue = "10") int perWorker
    ) {
        return loadTester.run(concurrency, perWorker);
    }

    /** 限流必须是 429，过载必须是 503 —— 返回 500 会被调用方当成故障去重试，越重试越崩。 */
    @ExceptionHandler(MockUpstream.RateLimited.class)
    public ResponseEntity<Map<String, Object>> rateLimited(MockUpstream.RateLimited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "60")
            .body(Map.of("error", "rate_limited", "message", e.getMessage()));
    }

    @ExceptionHandler(MockUpstream.Overloaded.class)
    public ResponseEntity<Map<String, Object>> overloaded(MockUpstream.Overloaded e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "5")
            .body(Map.of("error", "upstream_overloaded", "message", e.getMessage()));
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of(
            "upstreamSlots", upstream.slots(),
            "upstreamLatencyMs", upstream.latencyMs(),
            "upstreamInFlight", upstream.inFlight(),
            "appInFlight", appConcurrency.get(),
            "appPeak", appPeak.get());
    }

    /** 正向估算：从业务量算出要多少并发、多少配额、多少钱。 */
    @PostMapping("/api/model")
    public Map<String, Object> model(
        @RequestParam(defaultValue = "50000") int dau,
        @RequestParam(defaultValue = "6") double callsPerUser,
        @RequestParam(defaultValue = "5") double peakFactor,
        @RequestParam(defaultValue = "2500") double avgLatencyMs,
        @RequestParam(defaultValue = "400") double ttftMs,
        @RequestParam(defaultValue = "1500") int promptTokens,
        @RequestParam(defaultValue = "300") int completionTokens,
        @RequestParam(defaultValue = "2") double inPrice,
        @RequestParam(defaultValue = "8") double outPrice
    ) {
        return CapacityModel.compute(new CapacityModel.Input(dau, callsPerUser, peakFactor,
            avgLatencyMs, ttftMs, promptTokens, completionTokens, inPrice, outPrice, 1.5));
    }

    /** 反向估算：套餐是定死的，算它够不够。 */
    @GetMapping("/api/model/from-upstream")
    public Map<String, Object> fromUpstream(
        @RequestParam(defaultValue = "50") int concurrency,
        @RequestParam(defaultValue = "2500") double latencyMs,
        @RequestParam(defaultValue = "20") double targetQps
    ) {
        return CapacityModel.fromUpstream(concurrency, latencyMs, targetQps, 1500, 300, 2, 8);
    }
}