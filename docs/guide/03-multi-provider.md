---
title: 第 3 章 · 统一多模型接入
---

# 第 3 章 · 统一多模型接入

::: info 本章状态：撰写中
已放出 **3.1**、**3.2**、**3.3**、**3.4**、**3.5** 五节，只剩 3.6 结论还是提纲。想优先看哪一节？[开个 Issue](https://github.com/pgy763/hello-ai-backend/issues) 说清你的场景，我按热度排优先级。
:::

## 这一章解决什么问题

第一个模型接完了。现在业务方说：便宜的活给 A 模型，难一点的给 B 模型，别的地方已经在用 C 了。

于是你的代码里开始出现到处散落的 `if (model.equals("xxx"))`。

## 你会得到

- 一个 Provider 抽象，业务层只写一句话，换厂商不改业务代码
- 配置驱动的模型注册表，新厂商接进来只改 `yaml`
- 一份「所谓 OpenAI 兼容」的真实兼容性差异清单

## 3.1 为什么一定会变成多模型

不是技术潮流，是三个绕不开的现实原因。

**一、成本。** 不同任务对模型能力的需求差一个量级。

经验值：工单分类、意图识别、字段抽取这类任务，各家的轻量档模型通常能到 95% 上下的准确率，而旗舰档的单 token 价格是它的 15–30 倍。日均 20 万次调用的分类场景，这个差价按月算是几万块。

反过来说：把「多步推理」的活丢给轻量模型，它答错一次你要重试两次，再加上人工兜底的那部分，比一开始就用旗舰更贵。

所以口径要对：**按「单次任务的总成本」算，不要按「每百万 token 单价」算。** 单价便宜 20 倍、但重试 3 次还要人工兜底的方案，从来没便宜过。

**二、能力差异，而且不是「贵的全能」。**

- **长上下文**：都是「读一份 200 页的合同」，有的能吃 20 万 token，有的只到 128k；还有的标称很长，但中段召回明显下滑（经验值：不少模型在超过一半窗口后，中段信息的召回就开始掉）。
- **Tool Calling**：多轮里参数丢失、不支持并行调用、把数字塞成字符串 —— 这些在 demo 里完全看不出来，一到 Agent 场景就让流程卡在某个工具调用上。
- **结构化输出**：同样说「返回 JSON」，有的能上严格的 JSON Schema 约束，有的只是 prompt 里答应你一句。
- **中文长文本**：涉及行业术语、政策合规表述时，国产模型往往更稳。

**三、可用性：单供应商就是单点。**

真实会撞上的：限流（TPM / RPM 打满）、区域故障、模型下线、改价。主流厂商的模型生命周期经验值是 6–18 个月，「某个版本突然通知 deprecate、一个月内迁完」是常态。你的业务不会因为模型下线而暂停。

### 什么情况下不该做多模型

这是个取舍，不是越多越好：

- **日调用量几百次、月账单不到小几百块**：抽象层的维护成本（适配代码 + 两套 prompt + 回归测试 + 排查时多一层）远超省下的钱。先用一家跑通。
- **有硬性合规要求、只能走某家私有化部署**：没有多模型的余地，别为了架构美观硬抽一层。
- **团队只有一两个人**：能省的心智负担就省。一个 `if` 比一层抽象好维护。

判断阈值大概是两条线：**月账单到了四位数，或者业务明确不能中断。** 没到这两条线之前，多模型的收益是想象中的，成本是当天的。

## 3.2 OpenAI 兼容协议：事实标准，但它不保证兼容

`/v1/chat/completions` 事实上成了行业接口。好处是真的：换厂商时改 `base_url` 和 `api_key`，大部分调用能直接跑通。

但「兼容」这个词被用得太宽松了 —— 它描述的是「像」，不是「等价」。

### 能真正指望的部分

- 请求体形状：`messages` 数组（`role` / `content`）、`model`、`temperature`、`max_tokens`、`stream`
- 流式响应格式：每个事件一行 `data: {...}`，最后一行 `data: [DONE]`
- 取文本的路径：非流式 `choices[0].message.content`，流式 `choices[0].delta.content`
- 认证：`Authorization: Bearer <key>`

这四条覆盖了 80% 的调用代码。用官方 OpenAI SDK 换掉 `baseUrl`，问一句「你好」就跑通了 —— **也正因为这么顺，问题全藏在剩下那 20% 里。**

### 按翻车概率排序的差异点

| 差异点 | 典型表现 | 后果 |
| --- | --- | --- |
| `system` 角色 | 有的只接受 system 作第一条；有的一律拒绝 system，要你并进第一条 user；有的收下但不生效 | prompt 效果打折，或者直接 400 |
| `temperature` 范围 | OpenAI 是 0–2，不少实现只到 1 | 传 `1.2` 报 400，或者被静默截断成 1.0 |
| `max_tokens` 语义 | 有的指输出上限，有的指输入 + 输出合计 | 长 prompt 下输出莫名被截断 |
| `finish_reason` | 取值集合不统一：有 `stop` / `length` / `tool_calls`，也有 `eos`、`null`、数字枚举 | 分支判断掉进 default，截断检测失效 |
| 流式最后一帧的 `usage` | OpenAI 要显式传 `stream_options: {"include_usage": true}`；不少兼容实现不认这个字段 | 传了报 400，不传则拿不到 token 数，计量直接断掉 |
| 错误码 | 429 有时是限流、有时是余额不足；400 有时是参数错、有时是内容被安全策略拦下 | 你分不清该重试还是该告警 |
| Tool Calling | 并行调用、参数 JSON 类型、`tool_call_id` 的回传格式 | Agent 流程卡死或死循环 |

**这一节最值钱的一句：把「OpenAI 兼容」当成「调用形状相似」，不要当成兼容性契约。**

落到工程上是三件事：

1. **差异显式建模**，别让 `if (vendor.equals(...))` 散在业务代码里。抽一张厂商能力表 —— 可用参数白名单、各参数取值范围、错误码映射、是否支持 `stream_options` —— 放进配置。
2. **每接一家补一组契约测试**：非流式、流式、带 tool call、超长输入被截断。四个用例过完再上线，别等线上发现。
3. **报错原文原样记下来**，log 里至少带上厂商名和原始响应体。归一化之前先别丢 —— 你丢掉的那段，正是排查时唯一有信息量的东西。

## 3.3 Provider 接口怎么设计

先给结论：**`chat` 和 `stream` 拆成两个方法，`embed` 另开一个接口。**

理由不是审美，是返回类型。

### 为什么不能只留一个方法

最省事的写法是一个方法打天下：

```java
public interface LlmProvider {
    Object chat(ChatRequest req);   // 想同时兼容同步和流式
}
```

这个「兼容」的代价是连锁的：返回 `Object`，调用方每次都要 `instanceof` 判一下再强转；接口文档写不清它到底返回什么；用错了编译期查不出来，运行时才炸。类型系统本来能免费帮你挡掉这类 bug，这么写等于主动放弃。

真正的分歧有两点：

| | 非流式 | 流式 |
| --- | --- | --- |
| 返回 | 一次拿到完整文本 | 一个元素序列，边到边处理 |
| 调用方关心 | 最终结果 | 首字延迟、增量内容 |
| 失败时机 | 一次调用一次结果 | **已经吐了一半才失败** |

最后一行是关键 —— 流式的错误发生在序列**中间**，返回值必须能承载「中途失败」这件事。这个语义塞不进一个「要么返回字符串要么返回流」的签名里。

### 三个方法，两个接口

```java
public interface LlmProvider {
    ChatResponse chat(ChatRequest req);
    Stream<String> stream(ChatRequest req);
}

// 嵌入是另一类能力，单独开一个接口
public interface EmbeddingProvider {
    EmbeddingResponse embed(EmbeddingRequest req);
}
```

`embed` 单独开一个接口（而不是塞进 `LlmProvider`）的理由：嵌入是另一类能力 —— 输入输出都是向量，没有流式，也没有 `temperature`、`system` 这些概念。硬塞在一起，每个只想做对话的厂商适配器都得写一个 `embed` 抛 `UnsupportedOperationException`，接口的诚实度反而下降。

`chat` 和 `stream` 的参数用**请求对象**，不用一长串方法参数：

```java
public record ChatRequest(
        String model,
        List<Message> messages,
        double temperature,
        Integer maxTokens,
        List<Tool> tools) {}
```

理由硬且只有一个：**大模型的参数在持续增加**。今天加 `tools`，明天加 `responseFormat`，后天加 `reasoningEffort`。用对象传参，加字段时不动任何调用方签名；用平铺参数，每加一个就要改所有实现和所有调用点，很快会撞上「参数太多只能靠位置记」的泥潭。

> 反过来：如果一个接口只有两个参数、三年没变过，别为它造请求对象 —— 那是过度设计。判断标准是「参数会不会继续长」，不是「对象看起来更专业」。

### 流式返回 `Stream<String>` 还是 `Flux<String>`

Spring AI 用 `Flux`，LangChain4j 走回调 handler，裸 HTTP 你返回什么都行。

**MVC 项目里 `Stream<String>` 就够了，别为了「现代」强上 Reactor。**

一旦接口返回 `Flux`，整条链路都得跟着变响应式：Controller 要么改成 WebFlux，要么在 MVC 里加适配（那个适配本身就有坑，见踩坑手册里「MVC 项目里返回 `Flux` 报 `No converter for`」那条）；Service 的返回类型、异常处理、事务边界、线程模型全要重做。换来的是背压能力 —— 而大多数业务是「用户点一下问一句」，根本不产生需要背压的流量。**没有背压需求的时候，Reactor 的成本是确定的，收益是假设的。**

## 3.4 注册表与配置化

目标一句话：**新接一家厂商，只改 `application.yml`，不动 Java 代码。**

### 配置长什么样

```yaml
llm:
  providers:
    deepseek:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model: deepseek-chat
      supports-stream-options: false
    qwen:
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      api-key: ${DASHSCOPE_API_KEY}
      model: qwen-plus
      supports-stream-options: true
  routing:
    default: deepseek
    by-task:
      classify: qwen     # 分类走便宜的
      extract: qwen
      reason: deepseek   # 多步推理走强的
```

两个要点：

1. **`api-key` 写 `${DEEPSEEK_API_KEY}`，不写明文。** 这个 yml 是要进 git 的。明文 key 一旦提交，删掉当前代码没用 —— 得重写 git 历史（坑见踩坑手册里「密钥提交进了 git 历史」那条）。本地用环境变量，线上用 K8s Secret 或密钥管理服务注入环境变量，两条路都行，别把 key 写进文件。
2. **`supports-stream-options` 这类开关，就是 3.2 那张能力表的落地点。** 厂商差异不写成散落的 `if (vendor.equals(...))`，写进配置 —— 上面那段 yaml 加一行就够了。

### 绑定到代码

```java
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(Map<String, ProviderConfig> providers, RoutingConfig routing) {

    public record ProviderConfig(String baseUrl, String model, String apiKey,
                                 boolean supportsStreamOptions) {}

    public record RoutingConfig(String defaultProvider, Map<String, String> byTask) {}
}
```

Spring Boot 3 上 `record` + `@ConfigurationProperties` 走**构造器绑定**，不用写一堆 setter，一个配置类体量直接砍半。字段名和 yaml 的 kebab-case 会自动对上（`base-url` → `baseUrl`），不用加注解。用它要在主类或配置类上开 `@EnableConfigurationProperties` / `@ConfigurationPropertiesScan`。

### 注册表交给 Spring，别自己 new 一个 Map

每个厂商一个实现类，交给容器管，**Bean 名就取配置里的 key**：

```java
@Component("deepseek")
class DeepSeekProvider implements LlmProvider { /* ... */ }

@Component("qwen")
class QwenProvider implements LlmProvider { /* ... */ }
```

然后注入 `Map<String, LlmProvider>` —— Spring 会把所有该类型的 Bean 塞进来，**map 的 key 就是 Bean 名**：

```java
@Service
public class ModelRouter {
    private final Map<String, LlmProvider> providers;   // Bean 名 → 实现

    public ModelRouter(Map<String, LlmProvider> providers) {
        this.providers = providers;
    }

    public ChatResponse chat(String task, ChatRequest req) {
        String name = props.routing().byTask()
                .getOrDefault(task, props.routing().defaultProvider());
        return providers.get(name).chat(req);
    }
}
```

这一步是配置化的关键：**注册表不是你维护的 `HashMap`，是 Spring 容器。** 接一家厂商 = 写一个 `@Component` + 加一段 yaml，`ModelRouter` 一行都不用改。

### 业务层按「任务」调用，不按「模型名」调用

注意上面 `chat` 的第一个参数是 `task` 而不是模型名。业务代码写：

```java
router.chat("classify", req);   // ✅ 我要做分类
// router.chat("qwen-plus", req);  // ❌ 别这么写
```

差别在换模型那天暴露：前者改 yaml，后者改所有调用点。任务名的粒度也不用太细 —— 三五个够了，每个都配一遍等于没抽象。

### 配置化到哪里该停

配置化不免费，这几种情况反而应该更硬编码：

- **只有一家厂商、模型名一年不动。** 注册表 + 路由表 + 任务映射全是负资产。直接 `@Value` 写死，等第二家进来再抽 —— 这不是技术债，是延迟决策。
- **把 prompt 也搬进 yaml。** 多行 prompt 在 yaml 里得用 `|` 块，缩进错一格就是「静默生效的错 prompt」，没有任何编译期检查，排查时你对着日志看不出 prompt 被截了。prompt 放代码或独立模板文件，配置里只放「用哪个模型」。
- **为了热更新引一套配置中心。** `@RefreshScope` + 配置中心确实能做到改完即刻生效。但先问自己：模型配置变更一个月几次？**多数项目重启 30 秒能解决的事，不值得换来一个组件的运维成本。** 真到了多环境、多租户、要按用户切模型的时候再上。

## 3.5 兼容性差异实录

3.2 那张表按「翻车概率」排序。这一节按**你被卡住之后要改哪一行**来分：五组，每组给现象、绕法，以及这个绕法**什么时候是错的**。

### 第一组：参数取值范围被各家自己收窄

最常撞的是 `temperature`。OpenAI 的定义域是 0–2，不少实现只开到 1 —— 这是各家自己的选择，不是 bug。传 `1.5` 过去，典型返回是一个 400（措辞可能因版本而异）：

```json
{"error":{"message":"Invalid value for 'temperature': 1.5 is not in [0, 1]","type":"invalid_request_error"}}
```

更麻烦的是另一种：**不报错，静默截成 1.0**。你调半天以为「这模型创造力不行」，其实参数根本没生效。

绕法不是加 `if`，是把它写进 3.4 那张厂商能力表，请求前统一归一化：

```java
// ❌ 散在业务里：if (vendor.equals("qwen")) temp = Math.min(temp, 1.0);
// ✅ 进适配器：拿能力表里的范围兜住
double temp = clamp(req.temperature(), cap.minTemperature(), cap.maxTemperature());
```

**用 `clamp` 而不是直接取 `min`** —— 下限也要管：有的实现不接受 `0`，最小要 `0.01`。具体数字去官方文档抄，别猜。

同类还有 `max_tokens`：OpenAI 语义是「输出上限」，另一些实现是「输入 + 输出合计」，长 prompt 下后者会让你的输出莫名被砍。**判断方法：一个确定的短 prompt + 一个长 prompt 各打一次，比较实际输出长度** —— 一次就能试出来，比读文档快。

### 第二组：消息结构

`system` 角色至少有三种处理方式，**同一家的不同模型都可能不一样**：

- 只接受 system 作第一条消息
- 一律拒绝 system，要求并进第一条 user
- 收下，但不生效

前两种给你 400，第三种最坑：**没有错误，prompt 效果就是打折**。「换了模型之后回答变差」第一个要查的不是 prompt 写得不好，是这个。

绕法：能力表加一个 `systemAsUserPrefix`，适配器里决定发 `role: "system"` 还是把它拼进第一条 `user`。**这个分支只能在适配器里** —— 业务层永远只写「这是一条系统指令」这个语义。

多模态 `content` 的形状也不统一：OpenAI 新版是数组（`[{"type":"text","text":"..."}]`），部分兼容实现只认字符串。同一个字段两种形状，放序列化层兼容，别让业务层去分支。

### 第三组：响应解析

三个高频点：

1. **`finish_reason` 取值不统一。** 你能见到 `stop` / `length` / `tool_calls`，也能见到 `eos`、`null`、数字枚举。写 `if (reason.equals("stop"))` 就完事的话，遇到别的实现会掉进 default，**截断检测直接失效** —— 输出被 `length` 截了一半，你的代码还当它正常结束。至少把「非 `stop` 且非 `tool_calls`」当异常收口。
2. **流式的 `usage` 在哪一帧。** OpenAI 要显式传 `stream_options: {"include_usage": true}`，usage 出现在**最后一帧，且该帧 `choices` 是空数组**。两头都可能出问题：不传，拿不到 token 数、计量断掉（第 8 章要用）；传了，不认这个字段的实现可能直接 400。所以它必须进能力表 —— 3.4 的 yaml 里 `supports-stream-options` 就是干这个的。
3. **`delta.content` 可能整个缺席。** 不是每个 chunk 都带正文：有的 `delta` 只有 `role`，有的只有 `tool_calls`。解析时先判空再 append，别假设它一定在。

### 第四组：错误语义

最要命的一条：**`429` 不一定是限流**。有的用它表示「余额不足」，有的表示「当日配额用完」。三种的处理完全不同 —— 限流该退避重试；余额不足重试一百次也没用，还白烧钱。

所以不能只看 HTTP 状态码，要落到错误体里的字段。绕法是维护一张**错误码映射表**，同样写进配置：

```yaml
llm:
  providers:
    some-vendor:
      error-mapping:
        "429:insufficient_quota": billing   # 告警，不重试
        "429:rate_limit_exceeded": retry     # 退避重试
        "400:content_filter": reject         # 直接拒绝用户，别重试
```

还有一个只有走中转/聚合网关才会遇到的：**HTTP 200，但响应体里是错误。** `{"error": {...}}` 套在 200 里返回，你的 HTTP 客户端不抛异常，直接当正常响应解析，然后在 `choices` 取值时 NPE。**只要链路里有网关，解析前就必须先判 `error` 字段是否存在。**

### 第五组：Tool Calling

Agent 场景里这三条几乎必踩：

- **并行调用**。OpenAI 支持一轮返回多个 `tool_calls`，部分实现只回一个。你的循环若假设「一轮多个」，在只回一个的实现上不会错，只是慢；反过来假设「一轮一个」而对方回了多个，你会丢调用，流程卡死。
- **`function.arguments` 的类型**。OpenAI 的约定是 **JSON 字符串**（要自己解析），但有的兼容实现直接回一个对象。用 `JsonNode` 接、判类型再处理，比按 `String` 强转稳。
- **`tool_call_id` 回传**。多轮里每次工具结果都要带 `tool_call_id`，且必须和上一条 assistant 消息里的 id 完全一致。有的实现不校验，有的严格校验 —— 你本地（不校验那个）跑通，上线换一家直接 400。

> 这五组绕法有个共同点：**都落在适配器里，没有一个该进业务代码。** 这正是 3.3 那个接口存在的意义 —— 如果这些兼容处理散在 service 层，抽象就等于没做。

## 后续小节（提纲）

- **3.6 结论** —— 抽象到什么程度就该停手

## 前置

[第 1 章 · 选型](/guide/01-selection) · [第 2 章 · 流式响应](/guide/02-first-request)
