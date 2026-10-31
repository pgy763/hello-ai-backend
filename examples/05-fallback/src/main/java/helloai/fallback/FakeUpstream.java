package helloai.fallback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 假上游：三家厂商，各自可以被打成指定故障。
 *
 * <p>真厂商不会配合你演戏，所以「挂了会怎样」这件事只能自己造。
 */
@RestController
public class FakeUpstream {

    public enum Fault {
        OK, E500, E429, E400_INVALID, E400_QUOTA, E401, HANG, SLOW, GARBAGE
    }

    private final Map<String, Fault> faults = new ConcurrentHashMap<>();

    public void markFault(String vendor, Fault fault) {
        faults.put(vendor, fault);
    }

    public void clearFaults() {
        faults.clear();
    }

    @PostMapping(path = "/fake/{vendor}/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chat(@PathVariable String vendor, @RequestBody String body) {
        return switch (faults.getOrDefault(vendor, Fault.OK)) {
            case OK -> ok(vendor, body);
            case SLOW -> {
                sleep(700);
                yield ok(vendor, body);
            }
            case HANG -> {
                sleep(10_000); // 客户端早就超时走了，这里只是模拟「对方卡住不返回」
                yield ok(vendor, body);
            }
            case GARBAGE -> ResponseEntity.ok("""
                {"id":"chatcmpl-garbage","choices":[{"index":0,"message":{"role":"assistant"}}],
                 "usage":{"total_tokens":0}}
                """);
            case E500 -> error(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "模拟上游 500");
            case E429 -> error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "模拟上游限流");
            case E400_INVALID -> error(HttpStatus.BAD_REQUEST, "invalid_request_error", "模拟请求体参数错误");
            case E400_QUOTA -> error(HttpStatus.BAD_REQUEST, "insufficient_quota", "模拟这家额度用完了");
            case E401 -> error(HttpStatus.UNAUTHORIZED, "invalid_api_key", "模拟 key 失效");
        };
    }

    private ResponseEntity<String> ok(String vendor, String body) {
        String answer = "[" + vendor + "] 收到：" + userPrompt(body);
        return ResponseEntity.ok("""
            {"id":"chatcmpl-fake","object":"chat.completion",
             "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":20,"completion_tokens":12,"total_tokens":32}}
            """.formatted(quote(answer)));
    }

    private ResponseEntity<String> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body("""
            {"error":{"code":"%s","message":"%s","type":"fake_error"}}
            """.formatted(code, message));
    }

    /** 从请求体里抠出 user 的 content，好让回复能证明「这句话确实传到了哪一家」。 */
    private String userPrompt(String body) {
        if (body == null) {
            return "";
        }
        int i = body.indexOf("\"content\":\"");
        if (i < 0) {
            return body.length() > 40 ? body.substring(0, 40) + "…" : body;
        }
        int start = i + "\"content\":\"".length();
        int end = body.indexOf('"', start);
        return end < 0 ? body.substring(start) : body.substring(start, end);
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 控制口

    /**
     * 把某一家打成指定故障，厂商名要和 application.yml 里 url 的路径段一致。
     *
     * <pre>curl -X POST 'localhost:8080/api/fault/primary/E500'</pre>
     */
    @PostMapping("/api/fault/{vendor}/{fault}")
    public Map<String, Fault> setFault(@PathVariable String vendor, @PathVariable Fault fault) {
        markFault(vendor, fault);
        return faults;
    }

    @GetMapping("/api/fault")
    public Map<String, Fault> listFaults() {
        return Map.copyOf(faults);
    }

    @PostMapping("/api/fault/reset")
    public Map<String, Fault> resetFaults() {
        clearFaults();
        return Map.copyOf(faults);
    }
}