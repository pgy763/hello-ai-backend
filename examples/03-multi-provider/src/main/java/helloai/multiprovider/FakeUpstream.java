package helloai.multiprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 假上游 —— 一个最小的 OpenAI 兼容服务，让这个示例**不需要真 API key 就能跑**。
 *
 * <p>它同时是第 3 章的观察窗口：把收到的请求体原样记下来，
 * 于是你可以在 {@code /api/last} 里亲眼看到适配器到底翻译成了什么
 * （system 有没有被并进 user、temperature 有没有被夹到 1.0）。
 */
@RestController
@RequestMapping("/fake")
public class FakeUpstream {

    private final ObjectMapper json;
    private final AtomicReference<String> lastRequest = new AtomicReference<>("{}");

    public FakeUpstream(ObjectMapper json) {
        this.json = json;
    }

    @PostMapping(
        value = "/chat/completions",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public String completions(@RequestBody String rawBody) throws Exception {
        lastRequest.set(rawBody);

        JsonNode body = json.readTree(rawBody);
        double temperature = body.path("temperature").asDouble(-1);
        int messageCount = body.path("messages").size();
        String lastUser = body.path("messages").path(messageCount - 1).path("content").asText("");

        ObjectNode resp = json.createObjectNode();
        resp.put("id", "fake-1");
        resp.put("object", "chat.completion");
        resp.put("model", body.path("model").asText("unknown"));

        ObjectNode message = resp.putArray("choices").addObject().put("index", 0)
            .put("finish_reason", "stop").putObject("message");
        message.put("role", "assistant");
        message.put(
            "content",
            "假上游收到了 " + messageCount + " 条消息，temperature=" + temperature + "，最后一条 user 是：「" + truncate(lastUser) + "」"
        );

        ObjectNode usage = resp.putObject("usage");
        usage.put("prompt_tokens", rawBody.length() / 4);
        usage.put("completion_tokens", 24);
        return json.writeValueAsString(resp);
    }

    /** 观察窗口：适配器实际发出去的东西 */
    @GetMapping(value = "/last", produces = MediaType.APPLICATION_JSON_VALUE)
    public String last() {
        return lastRequest.get();
    }

    private static String truncate(String s) {
        String oneLine = s.replace("\n", " / ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}