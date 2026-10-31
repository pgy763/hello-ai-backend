package helloai.multiprovider;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 整章的核心配置。新接一家 OpenAI 兼容的厂商，只需要往 yaml 里加一段 —— 不动 Java。
 *
 * <p>注意 {@code ProviderConfig} 里那几个「看似多余」的字段：它们不是配置洁癖，
 * 每一个都对应 3.5 里一组真实的兼容性差异。
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(Map<String, ProviderConfig> providers, RoutingConfig routing) {

    public record ProviderConfig(
            String baseUrl,
            String apiKey,
            String model,
            boolean supportsStreamOptions, // 3.5 第三组：不认 stream_options 的实现传了会 400
            Double minTemperature, // 3.5 第一组：null = 不设下限
            Double maxTemperature, // 3.5 第一组：null = 不设上限
            boolean systemAsUserPrefix, // 3.5 第二组：要求把 system 并进第一条 user
            List<ErrorRule> errorMapping // 3.5 第四组：429 到底是限流还是余额不足
            ) {

        /** 同一个 HTTP 状态码可以挂多条规则，所以是列表不是 Map */
        public record ErrorRule(int httpStatus, String code, ErrorAction action) {}

        public double clampTemperature(double want) {
            double lo = minTemperature() == null ? 0d : minTemperature();
            double hi = maxTemperature() == null ? 2d : maxTemperature();
            return Math.max(lo, Math.min(hi, want));
        }
    }

    public record RoutingConfig(String defaultProvider, Map<String, String> byTask) {

        public String providerFor(String task) {
            if (byTask != null && byTask.containsKey(task)) {
                return byTask.get(task);
            }
            return defaultProvider;
        }
    }
}