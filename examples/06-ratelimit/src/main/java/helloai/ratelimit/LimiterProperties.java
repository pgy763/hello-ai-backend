package helloai.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流与熔断参数。
 *
 * <p>{@code capacity} 是允许的突发量，{@code refillPerSec} 是长期平均速率。
 * 从厂商的 RPM 反推：RPM=600 → 平均 10 QPS，但业务从不匀速，
 * 所以 {@code capacity} 留出 2–4 秒的量，{@code refillPerSec} 用平均值。
 */
@ConfigurationProperties(prefix = "limiter")
public record LimiterProperties(int capacity, double refillPerSec, int failureThreshold, long openMillis) {}