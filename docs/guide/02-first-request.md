---
title: 第 2 章 · 第一个请求：从 Controller 到 SSE 流式响应
---

# 第 2 章 · 第一个请求：从 Controller 到 SSE 流式响应

## 先看一组数字，它决定了这一章的必要性

一个同步接口，用户点一次「生成」，要等 12 秒。这 12 秒是怎么来的：

| 环节 | 典型耗时 | 说明 |
| --- | --- | --- |
| 首 token 延迟（TTFT） | 0.3 – 1.5 秒 | 模型开始吐第一个字之前 |
| 输出速度 | 20 – 60 tokens/秒 | 之后稳定出字的速度 |
| 300 字中文回答 ≈ 400–500 tokens | 8 – 20 秒 | 前面两段之和 |

关键洞察在这里：**用户真正在等的是「第一个字」，不是「全部字」。**

而第一个字通常 1 秒内就能到。也就是说，那 11 秒是**纯浪费** —— 内容早就开始产出了，只是被憋在服务端。

把「首字可见时间」从 12 秒压到 1 秒以内，这不是性能优化，这是**「能不能用」的分界线**。用户在这 12 秒里会做一件事：关掉页面。

---

## 2.1 为什么是 SSE，不是 WebSocket

想到「服务端持续推数据」，很多人的第一反应是 WebSocket。但在 AI 对话这个场景里，SSE 是更合适的选择。

| 维度 | SSE | WebSocket |
| --- | --- | --- |
| 通信方向 | 服务端 → 客户端，单向 | 双向 |
| 协议基础 | 就是普通 HTTP | 需要 Upgrade 握手 |
| 网关 / 代理兼容 | 绝大多数网关透明支持 | 部分网关需额外配置，甚至不支持 |
| 浏览器断线重连 | `EventSource` 自带 | 要自己实现心跳与重连 |
| 服务端复杂度 | 低（`SseEmitter` / `Flux`） | 需要维护连接状态与心跳 |
| 适合的场景 | 文本逐段输出 | 双向实时协作、游戏、IM |

结论很直接：**AI 对话的交互模式是「问一次、流式答一段」，单向就够了。** 为了一个单向场景引入 WebSocket 的连接管理、心跳、重连，是在给自己加工作量。

> 一个例外：如果你要做「对话中途打断」「用户边输入边引导模型」这类双向交互，那 WebSocket 才是对的。**先想清楚交互模式，再选技术。**

---

## 2.2 两条技术路线：`SseEmitter` 与 `Flux`

Spring 生态里做 SSE，有两条路：

| | `SseEmitter`（MVC） | `Flux`（WebFlux） |
| --- | --- | --- |
| 所属栈 | Spring MVC，Servlet 同步模型 | WebFlux，响应式 |
| 学习成本 | 低，就是回调 | 需要懂 Reactor |
| 线程模型 | 每个连接占用一个线程（要注意线程池） | 事件驱动，少量线程支撑高并发 |
| 适合 | 已有 MVC 项目、并发量中等 | 已用 WebFlux、并发量高 |

**怎么选**：项目已经是 MVC + 同步阻塞的，就用 `SseEmitter`，别为了一个流式接口把整个项目改成响应式。反过来，项目已经在 WebFlux 上了，用 `Flux` 更自然。

这一章我们用 `SseEmitter` —— 因为它对应的是本书预设的读者：**手上有一套存量 Spring Boot 系统。**

---

## 2.3 MVC 下的完整实现

先看接口层：

```java
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/ai")
public class StreamChatController {

    private static final Logger log = LoggerFactory.getLogger(StreamChatController.class);

    /** 流式连接的最长存活时间，必须显式设置 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final AiStreamClient aiStreamClient;

    public StreamChatController(AiStreamClient aiStreamClient) {
        this.aiStreamClient = aiStreamClient;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam String q) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 连接异常终止时记录下来，否则排查时两眼一抹黑
        emitter.onTimeout(() -> log.warn("SSE 超时: q={}", q));
        emitter.onError(e -> log.warn("SSE 出错: q={}", q, e));

        aiStreamClient.stream(q, new StreamHandler() {

            @Override
            public void onNext(String token) {
                try {
                    emitter.send(SseEmitter.event().data(token));
                } catch (IOException e) {
                    // 客户端断了（关页面、切网络）。抛出去，让上游停止调用模型 ——
                    // 不然你还在为一个已经离开的用户付 token 的钱
                    throw new StreamAbortedException(e);
                }
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }

            @Override
            public void onError(Throwable t) {
                // 必须通知前端。不调用的话，前端会一直等一个永远不会来的结束标志
                emitter.completeWithError(t);
            }
        });

        return emitter;
    }
}
```

这段代码有三个地方是**必须写、但文档里经常不提**的：

::: warning 1. 超时时间必须显式设置
不传超时时间，`SseEmitter` 会依赖容器的默认异步超时 —— Tomcat 上是 30 秒。一个稍长的回答还没说完，连接就被容器掐断了，前端表现为「回答说到一半突然停住」。

反过来也别设太大。设置成「你预期的最长回答时间」再留一倍余量就够了。
:::

::: warning 2. 异常必须传出去
`onError` 里如果不调用 `completeWithError`，前端的连接会一直挂着。用户看到的是「一直在加载」，而不是「失败了」。**这类「看起来是卡住了，其实是没通知」的问题，排查成本极高。**
:::

::: warning 3. 客户端断连要能感知，并停止上游调用
`emitter.send()` 抛 `IOException` 是个**有用的信号**，不是噪音 —— 它意味着用户已经离开了。这时候应该立刻中止对模型的调用，否则你付的钱是给一个已经关掉页面的人的。
:::

### 线程池：这里最容易翻车的地方

上面 `aiStreamClient.stream(...)` 如果是**阻塞**的（大多数模型 SDK 都是同步阻塞的），它会占住当前请求线程直到整个回答结束。

在 MVC 下这是致命的：Tomcat 的请求线程数是有限的（默认 200），每个进行中的流式回答会占住一个线程十几秒。**几百个并发用户就能把线程池吃干。**

所以 `AiStreamClient` 内部必须用自己的线程池把调用甩出去：

```java
@Component
public class AiStreamClient {

    /**
     * 专门跑流式调用的线程池。
     * 不要用默认的 ForkJoinPool.commonPool —— 它会被其他并行流任务干扰，
     * 而且默认线程数很少，几个长连接就占满了。
     */
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            8, 32,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new ThreadFactoryBuilder().setNameFormat("ai-stream-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public void stream(String question, StreamHandler handler) {
        streamExecutor.submit(() -> {
            try {
                doStream(question, handler);
                handler.onComplete();
            } catch (Exception e) {
                handler.onError(e);
            }
        });
    }

    // ... doStream 里做真正的 HTTP 调用
}
```

**线程池参数怎么定**：起步可以按「并发上限 = 池大小 + 队列容量」估算。上面这组参数的容量是 32 + 64 = 96 个并发流。超过就触发 `CallerRunsPolicy`（由调用线程执行），相当于给上游一个背压信号。

> 第 6 章会讲怎么用限流器把并发控制在合理范围。**在那之前，先把线程池的上限设出来** —— 有一个明确的数字，比「不知道能扛多少」强得多。

---

## 2.4 怎么对接模型厂商的流式接口

现在看 `doStream` 里到底要做什么。

### 方式一：裸 HTTP（理解 SSE 的本质）

模型厂商的流式接口返回的就是**纯文本**，格式极简单：

```
data: {"choices":[{"delta":{"content":"你"}}]}

data: {"choices":[{"delta":{"content":"好"}}]}

data: [DONE]

```

规矩只有三条：

1. 每个事件以 `data: ` 开头，以**空行**结束
2. 内容在 JSON 的 `choices[0].delta.content` 里（注意不是 `message.content`）
3. 最后会有一个 `data: [DONE]` 作为结束标志

用 JDK 自带的 `HttpClient` 就能解析，不需要任何额外依赖：

```java
@Component
public class RawStreamClient {

    private static final Logger log = LoggerFactory.getLogger(RawStreamClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HttpClient 是线程安全的，全局一个即可 —— 不要每次请求都新建 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void doStream(String question, StreamHandler handler) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", "deepseek-chat",
                "stream", true,
                "messages", List.of(Map.of("role", "user", "content", question))
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        // 注意：这里不设 request.timeout()。
        // HttpRequest.timeout() 是整个请求的总超时，流式场景下会在回答中途强行中断。
        HttpResponse<Stream<String>> response =
                http.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            throw new IOException("上游返回 " + response.statusCode());
        }

        response.body()
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6))
                .filter(payload -> !"[DONE]".equals(payload))
                .forEach(payload -> {
                    String delta = extractDelta(payload);
                    if (delta != null && !delta.isEmpty()) {
                        handler.onNext(delta);
                    }
                });
    }

    private String extractDelta(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
            return delta.isMissingNode() ? null : delta.asText();
        } catch (JsonProcessingException e) {
            // 有些厂商会插入非 JSON 的心跳行，忽略即可，不要让它中断整个流
            log.debug("跳过无法解析的流片段: {}", payload);
            return null;
        }
    }
}
```

**看懂这 60 行，你对流式的理解就到位了。** 剩下的所有复杂度 —— 重试、多厂商、降级、计量 —— 都是在它周围加东西。

::: warning 一个真实会撞上的坑：`timeout` 别乱设
`HttpRequest.timeout()` 是**整个请求**的超时，包括读响应体的全部时间。流式回答可能持续几十秒，设了就是「说到一半断掉」。

流式请求的正确做法是：**只在连接阶段设超时**（`HttpClient.Builder.connectTimeout`），响应体阶段靠「多久没收到新数据」来判断异常，而不是「总共花了多久」。
:::

### 方式二：用 Spring AI

如果你在第 1 章选了 Spring AI，等价代码是：

```java
public Flux<String> doStream(String question) {
    return chatClient.prompt()
            .user(question)
            .stream()
            .content();
}
```

然后在 MVC 里要做一次桥接（因为 `Flux` 不能被 MVC 直接序列化）：

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam String q) {
    SseEmitter emitter = new SseEmitter(180_000L);
    Disposable subscription = aiStreamClient.doStream(q)
            .subscribe(
                    token -> {
                        try {
                            emitter.send(SseEmitter.event().data(token));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    },
                    emitter::completeWithError,
                    emitter::complete
            );

    // 关键：连接结束时取消订阅，否则上游还在跑，白白烧 token
    emitter.onCompletion(subscription::dispose);
    emitter.onError(e -> subscription.dispose());

    return emitter;
}
```

**这段桥接代码是「MVC + Spring AI」组合下的标准写法**，值得抄下来。注意最后那两行 `dispose` —— 它对应前面说的「用户离开后要停止上游调用」，在响应式栈里就是用取消订阅实现的。

---

## 2.5 前端怎么接

这是最容易卡住后端工程师的一步。

### 方案 A：`EventSource`（简单，但有个硬伤）

```js
const es = new EventSource(`/ai/chat/stream?q=${encodeURIComponent(q)}`);
es.onmessage = (e) => appendToScreen(e.data);
es.onerror = () => es.close();
```

**硬伤：`EventSource` 不能自定义请求头。** 也就是说，你没法带 `Authorization: Bearer xxx`。

能搜到的「解法」大多是把 token 塞进 query string：

```js
new EventSource(`/ai/chat/stream?q=${q}&token=${token}`)   // ⚠️ 有风险
```

这样能跑，但有两个问题：**token 会出现在 Nginx 访问日志、浏览器历史和 Referer 里。** 如果是内部系统、token 短效，可以接受；面向公网的产品不建议。

### 方案 B：`fetch` + `ReadableStream`（推荐）

用 `fetch` 拿流，自己按 SSE 规则切分。能带 header，也能配合 `AbortController` 实现「停止生成」：

```js
const controller = new AbortController();

const resp = await fetch(`/ai/chat/stream?q=${encodeURIComponent(q)}`, {
  headers: { Authorization: `Bearer ${token}` },
  signal: controller.signal,
});

if (!resp.ok) {
  throw new Error(`HTTP ${resp.status}`);
}

const reader = resp.body.getReader();
const decoder = new TextDecoder();
let buffer = "";

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  buffer += decoder.decode(value, { stream: true });

  // SSE 以空行分隔事件
  const blocks = buffer.split("\n\n");
  // 最后一段可能是不完整的事件，留到下一轮再拼
  buffer = blocks.pop();

  for (const block of blocks) {
    const line = block.split("\n").find((l) => l.startsWith("data: "));
    if (!line) continue;

    const payload = line.slice(6);
    if (payload === "[DONE]") return;

    appendToScreen(payload);
  }
}
```

**`buffer` 那两行是这段代码的关键。** 网络分片不保证按事件边界切分，一个 `data: ...` 事件很可能被拆到两次 `read()` 里。不缓冲的话，你会看到随机出现的乱码和截断 —— 而且这个 bug 在本地测试时**大概率复现不出来**（本地延迟低、分片少）。

> **停止生成**：调用 `controller.abort()` 即可。配合后端那个「`send` 抛 IOException 就中止上游」的逻辑，用户在页面上点「停止」，你就立刻停止为这次回答付费。

---

## 2.6 上线前必须改的三个配置

**这一节是「不改就白干」级别的。** 你在本地测得好好的，一挂到 Nginx 后面，流式就变成了「等 12 秒，然后一次全出来」。

### Nginx

```nginx
location /ai/chat/stream {
    proxy_pass http://backend;

    proxy_buffering off;            # 🔴 最关键的一行
    proxy_cache off;
    proxy_read_timeout 300s;        # 长回答别被掐断
    proxy_set_header Connection '';  # 保持长连接，禁掉默认的 close
    chunked_transfer_encoding on;
}
```

**`proxy_buffering off` 是那个罪魁祸首。** Nginx 默认会缓冲上游响应直到攒够一定大小才转发给客户端 —— 对流式接口来说，这就是「把流憋成了一次性返回」。**这个坑每年都会浪费无数人的一个下午。**

### 网关 / 负载均衡

如果你前面还有 Spring Cloud Gateway、Kong、云厂商的 LB，同样要检查：

- 响应缓冲（buffering）是否关闭
- 读超时是否足够长（很多默认值是 60 秒）
- 是否强制 HTTP/1.1 分块传输

### 应用侧

- `SseEmitter` 的超时时间（2.3 节）
- 线程池要能支撑预期并发（2.3 节）
- 如果用了 `spring.mvc.async.request-timeout`，改的是全局默认值，别误伤其他异步接口

---

## 2.7 怎么验证流式真的生效了

别靠肉眼看页面「字是不是一个个蹦出来」—— 前端渲染可能有动画效果，会骗你。用命令行：

```bash
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/ai/chat/stream?q=用三句话介绍什么是SSE"
```

`-N` 关闭 curl 自己的缓冲。

**判断标准：**

- ✅ 数据**逐块**打印，时间上明显间隔 → 流式正常
- ❌ 停顿几秒后**一次性全打印**出来 → 有东西在缓冲。按这个顺序排查：
  1. Nginx `proxy_buffering`
  2. 网关 / LB 的缓冲配置
  3. 你的 `send()` 是不是被攒着一起发的（检查线程池是不是单线程串行）
  4. 上游厂商的接口是不是真流式（换 `curl` 直接打厂商接口对比）

---

## 2.8 什么时候不该用流式

流式不是万能的。以下四种情况，老老实实用同步返回：

| 场景 | 原因 |
| --- | --- |
| 后台任务、批处理 | 没有人在等，流式只会让代码复杂 |
| 需要结构化输出（JSON） | 流式过程中 JSON 是不完整的，无法解析使用 |
| 结果需要审核后再展示 | 流式意味着用户立刻看到未经审核的内容 |
| 输出很短（< 50 字） | 复杂度换来的收益极小 |

---

## 结论

1. **流式是 AI 接口的默认形态，不是优化项。** 首字延迟 1 秒和 12 秒，是「能用」和「用户会关掉」的区别。

2. **MVC 项目用 `SseEmitter`，别为了流式改成 WebFlux。** 但务必显式设超时、处理异常、感知断连 —— 这三件事不做，出问题时你会查很久。

3. **线程池上限必须显式设出来。** 每个流式连接占一个线程，这是 MVC 方案的真实成本。给一个明确的数字，比「不知道能扛多少」强得多。

4. **部署前先改 `proxy_buffering off`。** 这一行不改，前面所有工作都白费。

5. **用 `curl -N` 验证，别靠眼睛。** 前端动画会骗你，命令行不会。

**下一章**，我们要处理一个必然会来的需求：**不止一个模型。** 主用的贵、备用的便宜、特定任务要更强的模型 —— 怎么让业务代码不用改。

→ [第 3 章 · 统一多模型接入](/guide/03-multi-provider)
