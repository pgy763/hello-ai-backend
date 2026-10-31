package helloai.fallback;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 降级链的配置。顺序就是优先级 —— 这条链本身就是策略，不该藏在代码里。
 *
 * @param totalBudgetMs 整条链的总预算。超过它，宁可失败也不要让用户继续等
 * @param targets       按优先级排好的目标，第一个是主路
 */
@ConfigurationProperties(prefix = "fallback")
public record FallbackProperties(long totalBudgetMs, List<TargetConfig> targets) {

    /**
     * @param timeoutMs   这一跳最多等多久
     * @param minBudgetMs 剩余预算低于这个值就别启动这一跳 —— 启动了也来不及
     */
    public record TargetConfig(String name, String url, String model, long timeoutMs, long minBudgetMs) {}
}