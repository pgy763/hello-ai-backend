package helloai.fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 打一跳。只做一件事：把 HTTP 的千奇百怪翻译成 {@link Outcome}。 */
@Component
public class TargetClient {

    private static final Logger log = LoggerFactory.getLogger(TargetClient.class);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private final ObjectMapper json = new ObjectMapper();

    public Attempt call(FallbackProperties.TargetConfig target, String prompt, long budgetMs) {
        long start = System.nanoTime();

        HttpRequest request = HttpRequest.newBuilder(URI.create(target.url()))
            // 这个 timeout 只覆盖到「响应头到达」，不覆盖读完 body。
            // 真正的总预算靠下面的 orTimeout —— 两个都要，别只留一个。
            .timeout(Duration.ofMillis(budgetMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(target, prompt)))
            .build();

        try {
            HttpResponse<String> response = http
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(budgetMs, TimeUnit.MILLISECONDS)
                .join();
            return classify(target, response, elapsedMs(start));
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            long ms = elapsedMs(start);
            // 两个超时机制都会走到这里：orTimeout 抛 TimeoutException，
            // HttpRequest.timeout 抛 HttpTimeoutException。不归一化的话，同一个原因会报出两种 outcome，
            // 统计和告警全都对不上。
            if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                return Attempt.failed(target.name(), Outcome.TIMEOUT, ms, "超过 " + budgetMs + "ms 预算");
            }
            log.debug("上游连接失败 target={} cause={}", target.name(), cause.toString());
            return Attempt.failed(target.name(), Outcome.NETWORK, ms, cause.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return Attempt.failed(target.name(), Outcome.NETWORK, elapsedMs(start), e.getClass().getSimpleName());
        }
    }

    private Attempt classify(FallbackProperties.TargetConfig target, HttpResponse<String> response, long ms) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();

        if (status == 200) {
            String answer = extractAnswer(body);
            if (answer == null || answer.isBlank()) {
                // 这一支是整章的重点：监控看板上一片绿，用户拿到的是空回复
                return Attempt.failed(target.name(), Outcome.MALFORMED, ms, "HTTP 200 但取不到 content：" + snippet(body));
            }
            return Attempt.ok(target.name(), answer, ms);
        }

        String code = errorCode(body);

        if (status == 401 || status == 403) {
            return Attempt.failed(target.name(), Outcome.AUTH, ms, "HTTP " + status + " " + code);
        }
        if (status == 429 || status >= 500) {
            return Attempt.failed(target.name(), Outcome.SERVER_ERROR, ms, "HTTP " + status + " " + code);
        }
        // 剩下的 4xx 里，只有一个例外值得降级：这家没额度了，别家还有
        if ("insufficient_quota".equals(code) || "quota_exceeded".equals(code)) {
            return Attempt.failed(target.name(), Outcome.NO_QUOTA, ms, "HTTP " + status + " " + code);
        }
        return Attempt.failed(target.name(), Outcome.CLIENT_ERROR, ms, "HTTP " + status + " " + code);
    }

    private String body(FallbackProperties.TargetConfig target, String prompt) {
        try {
            return json.writeValueAsString(Map.of(
                "model", target.model(),
                "temperature", 0.2,
                "messages", new Object[] {Map.of("role", "user", "content", prompt)}
            ));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 只认 choices[0].message.content。取不到就是 MALFORMED，别猜。 */
    private String extractAnswer(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode node = root.path("choices").path(0).path("message").path("content");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String errorCode(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode code = root.path("error").path("code");
            return code.isTextual() ? code.asText() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String snippet(String body) {
        String s = body.replaceAll("\\s+", " ");
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}