package helloai.obs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单价表，配置化 —— 因为模型会改名、会降价、会有阶梯。
 *
 * <p>注意：下面的数字是示例，不是任何厂商的真实报价。换成你自己合同里的数字。
 */
@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(Map<String, ModelPrice> models) {

    /** 每百万 token 多少元。 */
    public record ModelPrice(BigDecimal inPerM, BigDecimal outPerM, BigDecimal cachedInPerM) {}

    /**
     * 算钱。全程 {@link BigDecimal}，并且用「微元」（1e-6 元）的整数存库：
     * 一次调用可能只有 0.0012 元，用 double 累加一天会飘。
     */
    public long costMicros(String model, int promptTokens, int cachedTokens, int completionTokens) {
        ModelPrice p = models.get(model);
        if (p == null) {
            throw new IllegalArgumentException("没有配置单价的模型: " + model);
        }
        int billedPrompt = Math.max(0, promptTokens - cachedTokens);

        BigDecimal cost = p.inPerM().multiply(BigDecimal.valueOf(billedPrompt))
            .add(p.cachedInPerM().multiply(BigDecimal.valueOf(cachedTokens)))
            .add(p.outPerM().multiply(BigDecimal.valueOf(completionTokens)))
            .divide(BigDecimal.valueOf(1_000_000L), 12, RoundingMode.HALF_UP);

        // 元 → 微元
        return cost.multiply(BigDecimal.valueOf(1_000_000L)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String yuan(long micros) {
        return BigDecimal.valueOf(micros).divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
            .toPlainString();
    }
}