package helloai.firstrequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 裸 HTTP 打模型厂商的流式接口，解析 SSE，逐段回调。
 *
 * <p>全程只用 JDK 自带的 {@link HttpClient} 和 Spring Boot 顺带带进来的 Jackson，
 * 不引任何模型 SDK —— 目的是让「流式到底是什么」看得见。
 */
@Component
public class AiStreamClient {

    private static final Logger log = LoggerFactory.getLogger(AiStreamClient.class);

    /** 只用来给线程起名，方便 jstack 里一眼认出是哪类线程 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HttpClient 是线程安全的，全局一个即可 —— 不要每次请求都新建 */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 专门跑流式调用的线程池。
     *
     * <p>不要用默认的 ForkJoinPool.commonPool：它会被其他并行流任务干扰，线程数也很少。
     * 注意真实行为 —— 核心线程满 8 条后先排队，队列满了才扩到 32：
     * 前 72 个任务（8 跑 + 64 等）都只有 8 条线程在干活。
     */
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
        8,
        32,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64),
        r -> {
            Thread t = new Thread(r, "ai-stream-" + SEQ.incrementAndGet());
            t.setDaemon(true); // 别拖住 JVM 关闭
            return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public AiStreamClient(
        @Value("${llm.base-url}") String baseUrl,
        @Value("${llm.api-key}") String apiKey,
        @Value("${llm.model}") String model
    ) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = trimmed;
        this.apiKey = apiKey;
        this.model = model;
    }

    @PostConstruct
    void checkConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("没有配 LLM_API_KEY —— 页面能打开，但发消息会返回 401/失败。见 README 的「怎么跑」。");
        } else {
            log.info("上游: {} 模型: {}", baseUrl, model);
        }
    }

    /** 交给线程池执行，立刻返回 —— 绝不在 Tomcat 请求线程上跑完整段回答 */
    public void stream(String question, StreamHandler handler) {
        streamExecutor.submit(() -> {
            try {
                doStream(question, handler);
                handler.onComplete();
            } catch (StreamAbortedException e) {
                // 用户关了页面。上游已经在 doStream 的 try-with-resources 里关掉了，
                // 这里不再回调 onError —— 连接都没了，没人收得下这个错误
                log.info("客户端已断开，上游调用已中止");
            } catch (Exception e) {
                log.warn("流式调用失败: {}", e.toString());
                handler.onError(e);
            }
        });
    }

    private void doStream(String question, StreamHandler handler) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(
            Map.of(
                "model",
                model,
                "stream",
                true,
                "messages",
                List.of(Map.of("role", "user", "content", question))
            )
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        // 注意：这里不设 request.timeout()。那是整个请求的总超时，
        // 流式场景下会在回答说到一半时强行中断。

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("上游返回 " + response.statusCode() + "：" + err);
        }

        // try-with-resources 是关键：客户端中途断开时这里会把连接关掉，上游才会真的停，
        // 否则你还在为一个已经离开的用户付 token 的钱
        int chunks = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 只认 data 字段；event: / id: / 注释行一律跳过
                if (!line.startsWith("data:")) {
                    continue;
                }
                // 兼容 "data: xxx" 和 "data:xxx" 两种写法
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String delta = extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    chunks++;
                    handler.onNext(delta);
                }
            }
        } finally {
            // 正常读完会走到这，客户端断开（onNext 抛异常）也会走到这。
            // 打出来才知道这次到底是用完了、还是被中断了 —— 排查时最常缺的就是这一行
            log.debug("上游连接已关闭，本次收到 {} 块", chunks);
        }
    }

    private String extractDelta(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            return content.isMissingNode() || content.isNull() ? null : content.asText();
        } catch (IOException e) {
            // 有些厂商会插入非 JSON 的心跳行，忽略即可，不要让它中断整个流
            log.debug("跳过无法解析的流片段: {}", payload);
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
    }
}