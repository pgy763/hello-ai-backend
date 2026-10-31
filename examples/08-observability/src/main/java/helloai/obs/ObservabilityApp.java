package helloai.obs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ObservabilityApp {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityApp.class, args);
    }
}

@RestController
class ChatController {

    /** 拼一个长一点的 prompt，让 token 数落在真实量级上（几百到一千多）。 */
    private static final String SEED = "客户说订单三天没发货，很着急，请帮我看看物流到哪了。";

    private final ChatService chat;
    private final CallLogRepository repository;

    ChatController(ChatService chat, CallLogRepository repository) {
        this.chat = chat;
        this.repository = repository;
    }

    /** 打一次调用。参数就是这一章要观察的四种情况。 */
    @PostMapping("/api/chat")
    public Map<String, Object> chat(
        @RequestParam(defaultValue = "t1") String tenant,
        @RequestParam(defaultValue = "ticket") String feature,
        @RequestParam(defaultValue = "demo-large") String model,
        @RequestParam(defaultValue = "") String prompt,
        @RequestParam(defaultValue = "150") int size,
        @RequestParam(defaultValue = "false") boolean stream,
        @RequestParam(defaultValue = "true") boolean includeUsage,
        @RequestParam(defaultValue = "0") int cachedTokens,
        @RequestParam(defaultValue = "false") boolean failFirst
    ) {
        String text = prompt.isBlank() ? SEED.repeat(size) : prompt;
        return chat.chat(new ChatService.Request(tenant, feature, model, text,
            stream, includeUsage, cachedTokens, failFirst));
    }

    /** 一个 trace 下的所有尝试：正常一次就是一行，重试过就是两行。 */
    @GetMapping("/api/logs/{traceId}")
    public List<CallLogRepository.CallLog> trace(@PathVariable String traceId) {
        return repository.byTrace(traceId);
    }

    /** 成本归因：按租户 / 模型 / 功能聚合。 */
    @GetMapping("/api/usage")
    public List<Map<String, Object>> usage(@RequestParam(defaultValue = "tenant") String groupBy) {
        List<Map<String, Object>> rows = repository.usageBy(groupBy);
        rows.forEach(r -> {
            Object micros = r.remove("cost_micros");
            r.put("costMicros", micros);
            r.put("costYuan", PricingProperties.yuan(((Number) micros).longValue()));
        });
        return rows;
    }

    @GetMapping("/api/summary")
    public Map<String, Object> summary() {
        Map<String, Object> raw = new LinkedHashMap<>(repository.summary());
        Object micros = raw.remove("cost_micros");
        raw.put("costMicros", micros);
        raw.put("costYuan", PricingProperties.yuan(micros == null ? 0L : ((Number) micros).longValue()));
        return raw;
    }

    @PostMapping("/api/reset")
    public Map<String, Object> reset() {
        repository.clear();
        return Map.of("reset", true);
    }

    @ExceptionHandler({IllegalArgumentException.class, RateLimited.class})
    public Map<String, Object> bad(Exception e) {
        return Map.of("error", e.getMessage());
    }
}