package helloai.obs;

import helloai.obs.CallLogRepository.CallLog;
import helloai.obs.FakeVendor.Reply;
import helloai.obs.FakeVendor.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 一次聊天调用的完整生命周期，以及它留下的那行日志。 */
@Service
class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final FakeVendor vendor;
    private final PricingProperties pricing;
    private final CallLogRepository repository;

    ChatService(FakeVendor vendor, PricingProperties pricing, CallLogRepository repository) {
        this.vendor = vendor;
        this.pricing = pricing;
        this.repository = repository;
    }

    record Request(String tenant, String feature, String model, String prompt,
                   boolean stream, boolean includeUsage, int cachedTokens, boolean failFirst) {}

    Map<String, Object> chat(Request req) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        for (int attempt = 1; ; attempt++) {
            try {
                Reply reply = vendor.reply(req.prompt(), req.stream(), req.includeUsage(),
                    req.cachedTokens(), attempt, req.failFirst());

                // usage 可能为 null：流式且厂商没开 include_usage
                Usage usage = reply.usage();
                boolean estimated = usage == null;
                int promptTokens = estimated ? FakeVendor.estimateTokens(req.prompt()) : usage.promptTokens();
                int cachedTokens = estimated ? 0 : usage.cachedTokens();
                int completionTokens = estimated ? FakeVendor.estimateTokens(reply.text()) : usage.completionTokens();

                long costMicros = pricing.costMicros(req.model(), promptTokens, cachedTokens, completionTokens);

                repository.insert(new CallLog(traceId, attempt, req.tenant(), req.feature(), "fake-vendor",
                    req.model(), req.stream(), promptTokens, cachedTokens, completionTokens, estimated,
                    costMicros, (int) reply.ttftMs(), (int) reply.totalMs(), "OK", null));

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("traceId", traceId);
                result.put("attempt", attempt);
                result.put("estimated", estimated);
                result.put("promptTokens", promptTokens);
                result.put("cachedTokens", cachedTokens);
                result.put("completionTokens", completionTokens);
                result.put("costMicros", costMicros);
                result.put("costYuan", PricingProperties.yuan(costMicros));
                result.put("ttftMs", reply.ttftMs());
                result.put("totalMs", reply.totalMs());
                if (estimated) {
                    int trueCompletion = FakeVendor.trueTokens(reply.text());
                    result.put("trueCompletionTokens", trueCompletion);
                    result.put("errorRate", errorRate(completionTokens, trueCompletion) + "%");
                }
                return result;

            } catch (RateLimited e) {
                // 429 不产生 token，也就不该计费 —— 但这一行必须记，否则你不知道自己被打回过
                repository.insert(new CallLog(traceId, attempt, req.tenant(), req.feature(), "fake-vendor",
                    req.model(), req.stream(), 0, 0, 0, false, 0L, null, null, "RATE_LIMITED", "429"));

                if (attempt >= 2) {
                    throw e;
                }
                log.warn("trace={} 第 {} 次被限流，Retry-After={}s，重试一次", traceId, attempt, e.retryAfterSeconds());
            }
        }
    }

    private static String errorRate(int estimated, int real) {
        return BigDecimal.valueOf(estimated - real)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(real), 1, RoundingMode.HALF_UP)
            .toPlainString();
    }
}