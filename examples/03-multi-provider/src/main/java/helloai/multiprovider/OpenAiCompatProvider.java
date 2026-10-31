package helloai.multiprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 整个第 3 章就靠这一个类：**所有 OpenAI 兼容的厂商，共用这一份实现。**
 *
 * <p>换厂商 = 换 {@link LlmProperties.ProviderConfig}。差异全部落在这一个类的三处：
 * <ol>
 *   <li>{@link #translateMessages} —— system 角色怎么处理（3.5 第二组）</li>
 *   <li>{@link #buildBody} 里的温度归一化与 stream_options（3.5 第一、三组）</li>
 *   <li>{@link #mapError} —— 429 到底是限流还是余额不足（3.5 第四组）</li>
 * </ol>
 *
 * <p>除了这三处，再没有第二个地方需要知道「这是哪家厂商」。
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);

    private final String name;
    private final LlmProperties.ProviderConfig cfg;
    private final HttpClient http;
    private final ObjectMapper json;

    public OpenAiCompatProvider(
        String name,
        LlmProperties.ProviderConfig cfg,
        HttpClient http,
        ObjectMapper json
    ) {
        this.name = name;
        this.cfg = cfg;
        this.http = http;
        this.json = json;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResponse chat(ChatRequest req) {
        try {
            HttpResponse<String> resp = http.send(
                request(req, false),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            JsonNode root = json.readTree(resp.body());
            // 有的网关 HTTP 200 但响应体里套着 error —— 只看状态码会直接 NPE 在 choices 上
            if (resp.statusCode() != 200 || root.has("error")) {
                throw mapError(resp.statusCode(), root, resp.body());
            }
            return parse(root);
        } catch (IOException e) {
            throw new LlmException(name, -1, "io_error", ErrorAction.RETRY, String.valueOf(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(name, -1, "interrupted", ErrorAction.FAIL, String.valueOf(e));
        }
    }

    @Override
    public Stream<String> stream(ChatRequest req) {
        try {
            HttpResponse<InputStream> resp = http.send(
                request(req, true),
                HttpResponse.BodyHandlers.ofInputStream()
            );
            if (resp.statusCode() != 200) {
                String raw = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw mapError(resp.statusCode(), json.readTree(raw), raw);
            }
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8)
            );
            // 惰性：调用方不消费，就不会读上游；close() 会关掉连接（try-with-resources 用）
            return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(new SseIterator(reader, json), Spliterator.ORDERED),
                false
            ).onClose(() -> closeQuietly(reader));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(name, -1, "interrupted", ErrorAction.FAIL, String.valueOf(e));
        }
    }

    // ---------------------------------------------------------------- 请求翻译

    private HttpRequest request(ChatRequest req, boolean stream) {
        String body;
        try {
            body = json.writeValueAsString(buildBody(req, stream));
        } catch (IOException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
        return HttpRequest.newBuilder()
            .uri(URI.create(trimSlash(cfg.baseUrl()) + "/chat/completions"))
            .header("Authorization", "Bearer " + (cfg.apiKey() == null ? "" : cfg.apiKey()))
            .header("Content-Type", "application/json")
            .header("Accept", stream ? "text/event-stream" : "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            // 刻意不设 timeout()：那是整条请求的总超时，流式会在回答说到一半时被掐断
            .build();
    }

    /** 包级可见：示例里用 /api/last 把「实际发出去的是什么」打出来，方便对照 3.5 */
    Map<String, Object> buildBody(ChatRequest req, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", cfg.model());
        body.put("messages", translateMessages(req.messages()));
        body.put("temperature", cfg.clampTemperature(req.temperature())); // 第一组
        if (req.maxTokens() != null) {
            body.put("max_tokens", req.maxTokens());
        }
        if (stream) {
            body.put("stream", true);
            if (cfg.supportsStreamOptions()) {
                body.put("stream_options", Map.of("include_usage", true)); // 第三组
            }
        }
        return body;
    }

    /**
     * 第二组：system 角色的三种态度。
     *
     * <p>要求「并进第一条 user」的实现，就得在这里合并；业务层永远只写「这是一条系统指令」。
     */
    private List<Map<String, String>> translateMessages(List<ChatRequest.Message> in) {
        List<Map<String, String>> out = new ArrayList<>();
        StringBuilder pendingSystem = new StringBuilder();

        for (ChatRequest.Message m : in) {
            if ("system".equals(m.role()) && cfg.systemAsUserPrefix()) {
                if (!pendingSystem.isEmpty()) {
                    pendingSystem.append("\n\n");
                }
                pendingSystem.append(m.content());
                continue;
            }
            String content = pendingSystem.isEmpty()
                ? m.content()
                : pendingSystem + "\n\n" + m.content();
            pendingSystem.setLength(0);
            out.add(Map.of("role", m.role(), "content", content));
        }

        // 只有 system、没有 user：退化成一个 user 发出去，总比 400 好
        if (!pendingSystem.isEmpty()) {
            out.add(Map.of("role", "user", "content", pendingSystem.toString()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 响应解析

    private ChatResponse parse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        JsonNode finish = choice.path("finish_reason");
        JsonNode usage = root.path("usage");
        return new ChatResponse(
            content,
            finish.isMissingNode() || finish.isNull() ? "unknown" : finish.asText("unknown"),
            new ChatResponse.Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0)
            )
        );
    }

    // ---------------------------------------------------------------- 错误语义

    /** 第四组：状态码 + code 一起看，才能区分「该退避」和「该告警」 */
    private LlmException mapError(int status, JsonNode root, String raw) {
        JsonNode error = root.path("error");
        String code = error.path("code").asText(error.path("type").asText("unknown"));
        ErrorAction action = ErrorAction.FAIL;

        for (LlmProperties.ProviderConfig.ErrorRule rule : rules()) {
            if (rule.httpStatus() == status && Objects.equals(rule.code(), code)) {
                action = rule.action();
                break;
            }
        }
        // 原文必须留下：归一化之前先别丢，丢掉的那段正是排查时唯一有信息量的东西
        log.warn("上游报错 provider={} status={} code={} 原文={}", name, status, code, raw);
        return new LlmException(name, status, code, action, raw);
    }

    private List<LlmProperties.ProviderConfig.ErrorRule> rules() {
        return cfg.errorMapping() == null ? List.of() : cfg.errorMapping();
    }

    // ---------------------------------------------------------------- 工具

    /** SSE 解析：只认 data 字段，跳过 event / id / 注释行 */
    private static final class SseIterator implements Iterator<String> {

        private final BufferedReader reader;
        private final ObjectMapper json;
        private String next;

        SseIterator(BufferedReader reader, ObjectMapper json) {
            this.reader = reader;
            this.json = json;
        }

        @Override
        public boolean hasNext() {
            while (next == null) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (line == null) {
                    return false;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                try {
                    JsonNode delta = json
                        .readTree(payload)
                        .path("choices")
                        .path(0)
                        .path("delta")
                        .path("content");
                    // 不是每个 chunk 都带正文：有的只有 role，有的只有 tool_calls
                    if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
                        next = delta.asText();
                    }
                } catch (IOException e) {
                    log.debug("跳过无法解析的流片段: {}", payload);
                }
            }
            return true;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            String v = next;
            next = null;
            return v;
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        try {
            reader.close();
        } catch (IOException ignore) {
            // 连接已经没了
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}