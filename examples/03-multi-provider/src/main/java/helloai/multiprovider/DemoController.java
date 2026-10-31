package helloai.multiprovider;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示入口。三个端点分别回答三个问题：
 * <ul>
 *   <li>{@code /api/ask} —— 业务层只说任务名，路由到哪个 provider 由 yaml 决定</li>
 *   <li>{@code /api/routing} —— 现在的路由表长什么样</li>
 *   <li>{@code /api/last} —— 适配器**实际发出去**的请求体（看差异翻译得对不对）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final ModelRouter router;
    private final LlmProperties props;

    public DemoController(ModelRouter router, LlmProperties props) {
        this.router = router;
        this.props = props;
    }

    @GetMapping("/ask")
    public AskResult ask(
        @RequestParam(defaultValue = "classify") String task,
        @RequestParam(defaultValue = "帮我判断这条工单的类别") String q,
        @RequestParam(defaultValue = "0.7") double temperature
    ) {
        String providerName = router.providerNameFor(task);
        ChatRequest req = ChatRequest.of("你是一个严谨的助手，只输出结论。", q)
            .withTemperature(temperature);

        ChatResponse resp = router.chat(task, req);
        return new AskResult(
            task,
            providerName,
            props.providers().get(providerName).model(),
            resp.content(),
            resp.finishReason(),
            resp.usage().totalTokens(),
            !"stop".equals(resp.finishReason())
        );
    }

    @GetMapping("/routing")
    public Map<String, String> routing() {
        return router.routingTable();
    }

    public record AskResult(
        String task,
        String provider,
        String model,
        String answer,
        String finishReason,
        int totalTokens,
        boolean truncated
    ) {}
}