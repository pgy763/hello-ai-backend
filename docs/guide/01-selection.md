---
title: 第 1 章 · 选型：Spring AI vs LangChain4j vs 裸 HTTP
---

# 第 1 章 · 选型：Spring AI vs LangChain4j vs 裸 HTTP

## 结论先行

如果你赶时间，看完这张表就可以走了。本章剩下的部分解释**为什么**这么选。

| 你的情况 | 建议 | 理由 |
| --- | --- | --- |
| 给存量 Spring Boot 系统加 AI，要长期维护 | **Spring AI** | 和 Spring 生态同源，配置、监控、依赖注入全都能复用 |
| 一个人、两周内要出东西，只是加个对话入口 | **裸 HTTP** | 少一层抽象，就少一堆版本兼容问题 |
| 要做多步 Agent 编排、工具调用密集、多模型混合 | **LangChain4j** | 抽象更贴近 Agent 场景，链式 API 更灵活 |
| 团队已有 Python 版 LangChain 资产要平移 | **LangChain4j** | 概念能一一对应（Chain / Agent / Memory） |
| 只是想验证 AI 到底有没有用 | **随便选一个，先跑通** | 选型的价值在维护期，不在验证期 |

> **一句话版本**：Spring 生态里选 Spring AI；重 Agent 编排选 LangChain4j；只做一个功能点，裸 HTTP 就够。

---

## 为什么不直接给一个「正确答案」

因为选型是权衡，不是对错。这三个选项在**功能上完全能互相替代** —— 都能调通同一个模型，都能流式输出，都能做工具调用。差别在于：**抽象层帮你承担多少，以及你为这层抽象付多少代价。**

先看清楚三者的定位，答案自己就浮出来了。

## 三个选项分别是什么

### 1. 裸 HTTP —— 最被低估的选项

大模型厂商提供的 API 本质就是 HTTP + JSON。所谓 SDK，做的主要是「拼 JSON」和「解析 JSON」两件事。

用 Spring 自带的 `RestClient`，二十行就能跑通：

```java
@Configuration
class RawAiConfig {
    @Bean
    RestClient aiHttp() {
        return RestClient.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .defaultHeader("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                .build();
    }
}
```

```java
@Component
public class RawChatClient {

    private final RestClient http;

    public RawChatClient(RestClient aiHttp) {
        this.http = aiHttp;
    }

    public String chat(String question) {
        Map<String, Object> body = Map.of(
                "model", "deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", question))
        );

        ChatResponse resp = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatResponse.class);

        return resp.choices().get(0).message().content();
    }

    record ChatResponse(List<Choice> choices) {}
    record Choice(Message message) {}
    record Message(String content) {}
}
```

**它常常是对的原因**：没有版本冲突，没有抽象泄漏，出问题看堆栈一眼就知道在哪。换厂商就是改一个 `baseUrl`。

**它什么时候会错**：当你需要流式输出、自动重试、多模型切换、结构化输出、Tool Calling 的时候。这些自己写，第一版很短，三个月后会变成一坨没人敢改的代码。

写这段代码不超过半小时。**强烈建议你在选框架之前，先花这半小时。** 跑通之后你对「框架在帮我做什么」会有实感，而不是听别人说。

### 2. Spring AI

定位：把 AI 能力做成 Spring 生态里的一等公民。

```java
@RestController
class ChatController {

    private final ChatClient chat;

    ChatController(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem("你是一个简洁的客服助手，回答不超过两句话。")
                .build();
    }

    @GetMapping("/ask")
    String ask(@RequestParam String q) {
        return chat.prompt().user(q).call().content();
    }
}
```

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
```

依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
```

> 版本号请查 Maven Central 上的最新稳定版。Spring AI 迭代较快，**不要用动态版本号**，锁死并定期手动升级。

**优点**：配置写在 `application.yml`，`ChatClient` 可以注入到任何 Service，和 Spring 的监控、事务、Profile 机制天然打通。

**会踩的坑**（来自真实项目，不是文档里会写的）：

::: warning 1. 抽象层会挡住厂商特有参数
比如某些模型的思考模式开关、缓存命中计数字段、`reasoning_content`。一旦业务必须要用，你要么在 Configure 里塞自定义参数（不同版本 API 不一样），要么单独写一个裸 HTTP 的 Provider 兜底。

**这不是 Spring AI 的问题，是所有抽象层的通病。** 提前知道，遇到时不慌。
:::

::: warning 2. 升级会打破流式和 Tool Calling
这两个是最容易在版本升级中行为变化的模块。升级前，优先跑一遍流式输出和工具调用的回归测试，别只看普通对话能不能通。
:::

::: warning 3. base-url 结尾别画蛇添足
`spring.ai.openai.base-url` 只要写到厂商域名（或 `/v1`），**框架会自己拼 `/v1/chat/completions`**。你手动写上完整路径，得到的一定是 404。
:::

### 3. LangChain4j

定位：把 Python LangChain 的心智模型搬到 Java 上。

```java
interface Assistant {
    @SystemMessage("你是一个简洁的客服助手，回答不超过两句话。")
    String chat(@UserMessage String question);
}

@Configuration
class AiConfig {

    @Bean
    Assistant assistant() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .modelName("deepseek-chat")
                .build();

        return AiServices.create(Assistant.class, model);
    }
}
```

**优点**：**用接口声明意图**是一个很舒服的设计 —— 上面那个 `Assistant` 接口，调用方完全看不出背后是大模型。做 Agent、多轮记忆、工具调用时，它的抽象层次比 Spring AI 更顺手。

**会踩的坑**：

::: warning 1. 别和 Spring AI 同时出现在一个项目里
两者功能高度重叠。同时用意味着两套配置、两套抽象、两种错误处理 —— 维护成本直接翻倍。团队里定一个，写进规范。
:::

::: warning 2. 文档覆盖不均衡
基础用法（模型调用、简单 RAG）文档很全。但高级特性（自定义 Agent 策略、记忆存储扩展、工具执行的并发控制）经常得看源码或 example 工程。**在国内社区，能搜到的 LangChain4j 中文资料也明显少于 Spring AI。**
:::

::: warning 3. 模块版本要对齐
`langchain4j`、`langchain4j-open-ai`、各类集成模块有各自的版本。混用不同版本线会出现 `NoSuchMethodError`。建议用 BOM 统一管理。
:::

---

## 换框架的代价有多大

这一节比选型本身更重要。因为最常见的错误不是「选错了」，而是「选完又想换」。

| 从 → 到 | 要动的地方 | 经验工作量 |
| --- | --- | --- |
| 裸 HTTP → Spring AI | 替换调用代码、补配置、删掉自己写的 JSON 拼装 | 1–2 天 / 单个功能点 |
| Spring AI → LangChain4j | 替换调用代码 + 重写 Agent 编排与记忆 | 3–5 天 / 单个功能点 |
| 任意 → 任意，且已经上了生产 | 上面全部 + 灰度、回归、回滚预案 | 乘 3 |

> 工时是经验估计，仅供参考。但**结构性结论是确定的：接入层的框架一旦选定，换的成本远大于忍耐的成本。**

---

## 结论

1. **大多数「给存量 Spring Boot 系统接 AI」的场景，选 Spring AI。** 你不需要为 AI 单独维护一套技术栈，这是它最大的价值。

2. **选框架之前，先用裸 HTTP 花半小时跑通一个请求。** 你会立刻明白框架在帮你做什么，也会明白它的边界在哪。这半小时是所有投入里回报最高的。

3. **别做技术选型的完美主义者。** 真正的难点不在这里，而在第 4 到第 8 章 —— 密钥怎么管、主模型挂了怎么办、账单怎么算、任务断了怎么续。**那才是决定这个项目能不能上生产的地方。**

4. **选定后写进团队规范，别再动。** 把省下来的精力拿去做第 2 部分。

---

## 本章代码

`examples/01-selection/` —— 上面三段代码的完整可运行版本，三个独立的 Maven 模块，共用同一份 `application.yml` 模板。

> 目录正在整理中。当前你可以直接复制本章的代码块到自己的项目里运行，依赖只需要一个 `spring-boot-starter-web`（裸 HTTP 版本）。
>
> 需要 Spring AI 与 LangChain4j 的依赖坐标时，请以官方 Maven Central 上的最新稳定版为准。

---

**下一章**，不管选了哪个，我们都要解决同一个问题：**让用户不用等 12 秒才看到第一个字。**

→ [第 2 章 · 第一个请求：从 Controller 到 SSE 流式响应](/guide/02-first-request)
