package helloai.capstone;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 模型网关：限流 → 主模型 → 降级 → 计量。
 *
 * <p>第 5、6、8 章的能力都收在这一个类里。真实项目里这里会换成真的 HTTP 调用，
 * 但**外面这层壳（限流、降级、记账）不需要改**。
 */
@Component
class LlmGateway {

    enum Mode { OK, PRIMARY_DOWN, ALL_DOWN }

    record Reply(String text, String provider, boolean fallback,
                 int promptTokens, int completionTokens, long costMicros, long latencyMs) {}

    private final JdbcTemplate jdbc;
    private final TokenBucket limiter;
    private final AtomicLong totalCalls = new AtomicLong();

    @Value("${capstone.pricing.big-in-per-million}") private double bigIn;
    @Value("${capstone.pricing.big-out-per-million}") private double bigOut;
    @Value("${capstone.pricing.small-in-per-million}") private double smallIn;
    @Value("${capstone.pricing.small-out-per-million}") private double smallOut;

    private volatile Mode mode = Mode.OK;
    private volatile int primaryLatencyMs = 120;
    private volatile int fallbackLatencyMs = 80;

    LlmGateway(JdbcTemplate jdbc,
               @Value("${capstone.limiter.capacity}") int capacity,
               @Value("${capstone.limiter.refill-per-second}") double refillPerSecond) {
        this.jdbc = jdbc;
        this.limiter = new TokenBucket(capacity, refillPerSecond);
    }

    /**
     * 一次调用。先过限流，再打主模型，失败降级到小模型，全程记账。
     */
    Reply complete(String ticketId, String purpose, String prompt) {
        if (!limiter.tryTake()) {
            throw new RateLimited("本地令牌桶已空，请稍后重试");
        }
        totalCalls.incrementAndGet();

        try {
            return call(ticketId, purpose, prompt, "big-model", primaryLatencyMs, bigIn, bigOut, false);
        } catch (UpstreamException e) {
            if (mode == Mode.ALL_DOWN) {
                throw e;
            }
            // 降级：小模型。慢一点、便宜一点，但能出结果
            return call(ticketId, purpose, prompt, "small-model", fallbackLatencyMs, smallIn, smallOut, true);
        }
    }

    private Reply call(String ticketId, String purpose, String prompt, String provider,
                       int latencyMs, double inPrice, double outPrice, boolean fallback) {
        long t0 = System.nanoTime();
        sleep(latencyMs);

        if ("big-model".equals(provider) && mode != Mode.OK) {
            throw new UpstreamException("big-model 返回 503（模拟上游不可用）");
        }

        String text = FakeModel.respond(prompt, purpose);
        int promptTokens = estimateTokens(prompt);
        int completionTokens = estimateTokens(text);
        long costMicros = Math.round(promptTokens * inPrice + completionTokens * outPrice);
        long latency = (System.nanoTime() - t0) / 1_000_000L;

        jdbc.update("""
            insert into llm_call (ticket_id, provider, purpose, prompt_tokens, completion_tokens,
                                  cost_micros, latency_ms, created_at)
            values (?, ?, ?, ?, ?, ?, ?, current_timestamp)
            """, ticketId, provider, purpose, promptTokens, completionTokens, costMicros, latency);
        jdbc.update("update ticket set cost_micros = cost_micros + ? where id = ?", costMicros, ticketId);

        return new Reply(text, provider, fallback, promptTokens, completionTokens, costMicros, latency);
    }

    /** 粗略估 token：中文按 1 字 ≈ 1 token 有点亏，这里按 1.75 字符/token 折算。 */
    static int estimateTokens(String text) {
        return Math.max(1, (int) Math.round(text.length() / 1.75));
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void setMode(Mode mode) {
        this.mode = mode;
    }

    Mode mode() {
        return mode;
    }

    long totalCalls() {
        return totalCalls.get();
    }

    /** 替身模型：不联网，但**行为形状**和真模型一致（按 prompt 里的资料作答）。 */
    static final class FakeModel {

        private FakeModel() {}

        static String respond(String prompt, String purpose) {
            if ("classify".equals(purpose)) {
                return classify(prompt);
            }
            return answer(prompt);
        }

        private static String classify(String prompt) {
            // 只看工单正文那一行，别把指令里的字也算进去
            String text = prompt.lines().reduce((a, b) -> b).orElse(prompt);
            if (text.contains("退款") || text.contains("退货") || text.contains("退钱")) {
                return "退款";
            }
            if (text.contains("发票") || text.contains("专票") || text.contains("开票")) {
                return "发票";
            }
            if (text.contains("物流") || text.contains("发货") || text.contains("快递")
                || text.contains("到货") || text.contains("包裹") || text.contains("签收")) {
                return "物流";
            }
            if (text.contains("积分") || text.contains("会员")) {
                return "会员";
            }
            return "其他";
        }

        /** 只从 prompt 里的 [1][2] 段落里摘句子 —— 资料里没有就说没有。 */
        private static String answer(String prompt) {
            List<String> blocks = prompt.lines()
                .filter(l -> l.startsWith("[") && l.contains("]"))
                .toList();
            if (blocks.isEmpty()) {
                return "资料中没有提到相关信息。";
            }
            StringBuilder sb = new StringBuilder("根据知识库：");
            for (int i = 0; i < blocks.size(); i++) {
                String block = blocks.get(i);
                int idx = block.indexOf(']');
                String cite = block.substring(0, idx + 1);
                String body = block.substring(idx + 1).strip();
                int cut = body.indexOf('。');
                String sentence = cut > 0 ? body.substring(0, cut + 1) : body;
                sb.append(' ').append(cite).append(' ').append(sentence);
            }
            return sb.toString();
        }
    }

    static class UpstreamException extends RuntimeException {

        UpstreamException(String msg) {
            super(msg);
        }
    }

    static class RateLimited extends RuntimeException {

        RateLimited(String msg) {
            super(msg);
        }
    }

    /** 令牌桶：容量 5、每秒补 2 个。第 6 章那个的简化版。 */
    static final class TokenBucket {

        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryTake() {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsed * refillPerSecond);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized double available() {
            return tokens;
        }
    }
}