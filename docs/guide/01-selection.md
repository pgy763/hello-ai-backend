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
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);      // 连不上就别耗着
        factory.setReadTimeout(120_000);       // 大模型出字慢，读超时给够

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl("https://api.deepseek.com/v1")
                .defaultHeader("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"))
                .build();
    }
}
```

> **注意那两个超时值，第一版就该写。**
>
> 不写 `readTimeout`，默认值可能短到复杂问题还没出完字就被掐断；不写 `connectTimeout`，网络不通时请求会挂到你怀疑人生。这两个数字还是第 2 章做流式的前提 —— 流式连接的生命周期比普通请求长得多，超时设错会表现为「回答说到一半突然断掉」，非常难查。

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

::: warning 4. 流式返回的类型，MVC 里直接返回会翻车
`.stream()` 返回的是 `Flux<String>`。如果你的项目是 Spring MVC（不是 WebFlux），直接把它当 Controller 返回值，会得到一堆序列化得很奇怪的输出 —— MVC 不认识 Reactor 类型。

MVC 下的两条出路：用 `SseEmitter` 手动桥接（第 2 章给出完整做法），或者引入 WebFlux 但接受「项目里同时存在两套 Web 栈」的后果。

**第一次接流式的人几乎都会在这里卡一次。** 提前知道能省半天。
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

LangChain4j 也提供 Spring Boot Starter：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

> 但**它的 Spring 集成成熟度不如 Spring AI** —— 配置项的覆盖度、自动装配的完整度、社区示例的数量都要差一些。团队已经在 Spring 生态里的话，这一点值得纳入考量。

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

## 三者能力对照

上面讲的是定位，这一节是细节。如果你已经有两个候选在纠结，直接看表。

| 能力 | 裸 HTTP | Spring AI | LangChain4j |
| --- | --- | --- | --- |
| 基础对话 | 自己拼 JSON | `ChatClient` | `AiServices` 接口代理 |
| 流式输出 | 自己解析 SSE | `Flux<String>` | `TokenStream` |
| 结构化输出 | 自己写 Schema + 解析 | `.entity(Class)` | `AiServices` 返回自定义类型 |
| Tool Calling | 自己实现完整循环 | 注解式 `@Tool` | 注解式 `@Tool` |
| 多轮记忆 | 自己存历史 | `ChatMemory` + 存储 | `ChatMemory` + 存储 |
| RAG | 自己拼检索与上下文 | `VectorStore` + Advisor | `ContentRetriever` |
| 可观测性 | 自己埋点 | 接 Micrometer | 需自己接 |
| 配置方式 | 全在代码里 | `application.yml` 为主 | 代码为主 |
| 单元测试友好度 | 最容易 mock | 可 mock `ChatClient` | 接口代理，天然好测 |

**一句话读法**：从左到右，框架替你做的事越来越多，你的自由度越来越小。**没有哪一列全是优点。**

三个具体的取舍点：

- **裸 HTTP 那一列全是「自己写」**。好处是你完全知道发生了什么，坏处是这些代码会长成项目里最没人敢动的部分。
- **Spring AI 的优势集中在「可观测性」和「配置管理」** —— 这是 Spring 生态的传统强项，也是存量系统最需要的两块。
- **LangChain4j 的体验优势在「接口即意图」**，但它不解决运维问题。而运维问题最终都要你自己解决。

---

## 同一个需求，三种写法，三个月后

抽象地比不如具体地看。假设需求是：**给客服系统加一个「AI 回复建议」按钮。**

**第一周，三者看起来差不多。** 都是几十行代码，都能跑通。这时候你会觉得「选型辩论没什么意义」。

**第一个月，开始分化。**

- **裸 HTTP**：业务提了新要求 ——「模型偶尔返回空内容，要重试」「要按业务线统计调用次数」。你开始往自己的 `RawChatClient` 里加东西。它从 60 行长到了 300 行。
- **Spring AI**：重试有内置的重试配置，统计接 Micrometer，业务代码基本没动。
- **LangChain4j**：重试和统计都要给 `OpenAiChatModel` 包一层装饰器 —— 不难，但要自己设计这层结构。

**第三个月，真正的差距出现。**

- 产品说：换个厂商的模型试试效果。
  → 裸 HTTP 改 `baseUrl`（但如果新厂商响应结构有细微差异，得改解析代码）；Spring AI 改一行配置；LangChain4j 换一个 `ChatModel` 实现。
- 运维说：主模型上周挂了 40 分钟，要能自动切换。
  → **三者起点完全一样 —— 都得自己写。** 这是第 5 章。
- 财务说：这个月花了多少，按业务线拆一下。
  → **同样，三者都得自己写。** 这是第 8 章。

> 这张推演图想说明的只有一件事：**框架能帮你的，是「调用」那一段。** 而「怎么不挂、花了多少钱、断了怎么续」，三种方案全都得靠你自己。

这就是本书把绝大部分篇幅放在第 4 到第 8 章的原因。那才是决定项目成败的地方，不是选型。

---

## 换框架的代价有多大

这一节比选型本身更重要。因为最常见的错误不是「选错了」，而是「选完又想换」。

| 从 → 到 | 要动的地方 | 经验工作量 |
| --- | --- | --- |
| 裸 HTTP → Spring AI | 替换调用代码、补配置、删掉自己写的 JSON 拼装 | 1–2 天 / 单个功能点 |
| Spring AI → LangChain4j | 替换调用代码 + 重写 Agent 编排与记忆 | 3–5 天 / 单个功能点 |
| 任意 → 任意，且已经上了生产 | 上面全部 + 灰度、回归、回滚预案 | 乘 3 |

> 工时是经验估计，仅供参考。但**结构性结论是确定的：接入层的框架一旦选定，换的成本远大于忍耐的成本。**

除代码之外，还有三块成本几乎没人算进去，但它们往往才是真正拖垮迁移的原因：

1. **团队认知成本。** 换框架意味着每个人都要重建一套心智模型。文档看十遍不如自己踩一遍，这个过程省不掉，而且是**并行的** —— 迁移期间老功能还得维护。
2. **模式惯性。** 老代码还停在旧框架上，新代码用新的。半年后项目里两套写法并存，新人看不出哪段是「应该照着写的范本」。这是迁移最常见的中期状态。
3. **监控与告警要重配。** 新框架的异常类型、指标名称、日志字段都不一样。之前配好的告警规则全部失效 —— 而迁移期恰恰是最需要告警的时候。

---

## 结论

1. **大多数「给存量 Spring Boot 系统接 AI」的场景，选 Spring AI。** 你不需要为 AI 单独维护一套技术栈，这是它最大的价值。

2. **选框架之前，先用裸 HTTP 花半小时跑通一个请求。** 你会立刻明白框架在帮你做什么，也会明白它的边界在哪。这半小时是所有投入里回报最高的。

3. **别做技术选型的完美主义者。** 真正的难点不在这里，而在第 4 到第 8 章 —— 密钥怎么管、主模型挂了怎么办、账单怎么算、任务断了怎么续。**那才是决定这个项目能不能上生产的地方。**

4. **选定后写进团队规范，别再动。** 把省下来的精力拿去做第 2 部分。

---

## 几个被问得最多的问题

**Q：Spring AI 和 LangChain4j 能不能各管一块？比如一个负责普通对话，一个负责 Agent？**

技术上可以，实践中不要。两套 HTTP 客户端、两套重试逻辑、两套日志埋点。你会花大量时间在「这个异常到底是谁抛的」上面。**收益远小于成本。**

**Q：我先用裸 HTTP 上线，以后再换框架，可行吗？**

可行，但要提前做一件事：**把调用代码收拢到一个接口后面。** 只要业务代码依赖的是你自己的接口，而不是散落各处的 `RestClient` 调用，将来替换的成本就从「重写」降级为「换个实现类」。

```java
public interface AiClient {
    String chat(String question);
}
```

**这就是本书「接入层」最早的雏形。** 第 3 章的 Provider 抽象，本质上是这个接口的正式版 —— 只是它还要处理多厂商、多模型、故障转移。

**Q：公司指定必须用某个框架，怎么办？**

那就不用纠结了。但要注意：**第二部分（第 4–8 章）的内容与框架无关。** 降级、限流、计量、编排，三种方案都得自己实现。换不了框架，不影响你把这些做好 —— 而这些才是决定项目能不能上生产的东西。

**Q：Spring AI 的版本号写哪个？**

写你**亲手验证过能跑通**的那个具体版本，而不是「最新」。然后在独立分支上升级，跑完 **流式输出、Tool Calling、结构化输出** 三个回归再合并。不要用 `LATEST`，也不要用版本区间。

**Q：国内厂商都说自己「OpenAI 兼容」，能直接换吗？**

大部分能，但**不是 100% 兼容**。实际会遇到的差异：

- **字段缺失** —— 部分响应字段不返回（如 `system_fingerprint`），严格的 POJO 反序列化会报错
- **流式结束标志不一致** —— 有的厂商不发 `[DONE]`，依赖它判断结束的代码会一直等
- **参数被静默忽略** —— 不报错，但也不生效，你以为是「效果不好」
- **错误码体系不同** —— 尤其是限流和额度不足，别只判断 `429`

**做法**：对接每一家厂商时，把「基础对话 + 流式 + Tool Calling」各跑一遍，再宣布支持。第 3 章会给出可以直接照着走的兼容性检查清单。

---

## 本章代码

`examples/01-selection/` **还没建**。计划是放上面三段代码的完整可运行版本 —— 三个独立的 Maven 模块，共用同一份 `application.yml` 模板。

现在想跑，直接复制本章的代码块到自己项目里就行：裸 HTTP 版本只需要一个 `spring-boot-starter-web`。

> Spring AI 与 LangChain4j 的依赖坐标，以官方 Maven Central 上的最新稳定版为准 —— 这两个项目迭代都快，别照抄任何文章里的版本号。

---

**下一章**，不管选了哪个，我们都要解决同一个问题：**让用户不用等 12 秒才看到第一个字。**

→ [第 2 章 · 第一个请求：从 Controller 到 SSE 流式响应](/guide/02-first-request)
