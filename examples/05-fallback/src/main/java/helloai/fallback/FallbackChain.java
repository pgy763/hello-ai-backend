package helloai.fallback;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 降级链：按顺序试，第一个给出可用答案的赢。
 *
 * <p>三个约束比「按顺序试」本身更重要：
 * <ol>
 *   <li><b>总预算</b>：整条链有个死线，超过就失败，不让用户陪着等</li>
 *   <li><b>预算递减</b>：后面每一跳能用多久，取决于前面花掉了多少</li>
 *   <li><b>不该降级的错不降级</b>：遇到 400 参数错，换一家还是同样的错</li>
 * </ol>
 */
@Service
public class FallbackChain {

    private static final Logger log = LoggerFactory.getLogger(FallbackChain.class);

    private final List<FallbackProperties.TargetConfig> targets;
    private final TargetClient client;
    private final long totalBudgetMs;

    public FallbackChain(FallbackProperties props, TargetClient client) {
        this.targets = List.copyOf(props.targets());
        this.client = client;
        this.totalBudgetMs = props.totalBudgetMs();
    }

    public Answer ask(String prompt) {
        long start = System.nanoTime();
        long deadline = start + totalBudgetMs * 1_000_000L;
        List<Attempt> attempts = new ArrayList<>();

        for (FallbackProperties.TargetConfig target : targets) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining < target.minBudgetMs()) {
                attempts.add(Attempt.failed(target.name(), Outcome.SKIPPED, 0,
                    "剩余预算 " + Math.max(remaining, 0) + "ms，不够启动这一跳"));
                continue;
            }

            long budget = Math.min(target.timeoutMs(), remaining);
            Attempt attempt = client.call(target, prompt, budget);
            attempts.add(attempt);

            if (attempt.outcome() == Outcome.OK) {
                boolean degraded = attempts.size() > 1;
                if (degraded) {
                    log.warn("降级生效：主路失败，由 {} 接手，共 {} 次尝试，总耗时 {}ms",
                        target.name(), attempts.size(), elapsedMs(start));
                }
                return new Answer(attempt.answer(), target.name(), target.model(), degraded, attempts, elapsedMs(start));
            }

            if (!attempt.outcome().shouldFallback()) {
                // 换一家会撞同样的墙：立刻停，别把时间浪费在注定失败的下一跳上
                log.error("不可降级的失败 target={} outcome={} detail={}",
                    target.name(), attempt.outcome(), attempt.detail());
                break;
            }
            log.warn("这一跳失败，尝试下一个 target={} outcome={} ms={}",
                target.name(), attempt.outcome(), attempt.ms());
        }

        throw new AllTargetsFailedException(attempts, elapsedMs(start));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 成功的答案。{@code degraded} 必须一路带到响应里 —— 否则没人知道这条回答是备路给的。 */
    public record Answer(String answer, String target, String model, boolean degraded,
                         List<Attempt> attempts, long totalMs) {}

    public static class AllTargetsFailedException extends RuntimeException {

        private final transient List<Attempt> attempts;
        private final long totalMs;

        public AllTargetsFailedException(List<Attempt> attempts, long totalMs) {
            super("所有目标均不可用，共尝试 " + attempts.size() + " 次，耗时 " + totalMs + "ms");
            this.attempts = attempts;
            this.totalMs = totalMs;
        }

        public List<Attempt> attempts() {
            return attempts;
        }

        public long totalMs() {
            return totalMs;
        }
    }
}