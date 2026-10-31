# 03-multi-provider · 一个适配器接多家厂商

第 3 章的完整可运行版本。**不需要 API key** —— 示例自带一个假上游，开箱就能看到「换厂商只改 yaml」。

## 演示什么

```
业务代码                     注册表                      适配器               上游
router.chat("classify")  →  qwen  →  OpenAiCompatProvider  →  /chat/completions
router.chat("reason")    →  deepseek ┘        （同一个类）        （两家）
```

四件事，跑一遍全能看见：

| 正文 | 这里怎么体现 |
| --- | --- |
| 3.3 Provider 接口 | `LlmProvider` —— `chat` / `stream` 分开，请求用对象传参 |
| 3.4 配置驱动路由 | `application.yml` 里 `routing.by-task`，业务层只说任务名 |
| 3.4 注册表交给容器 | `ProviderRegistry` 启动时按 yaml 实例化，配置对不上**启动就炸** |
| 3.5 五组兼容性差异 | `OpenAiCompatProvider` 里三处翻译：system 合并、温度归一化、错误码映射 |

**关键点：两家厂商共用同一个 `OpenAiCompatProvider` 类。** 换厂商不是「写一个新类」，是「加一段 yaml」。

## 怎么跑

```bash
cd examples/03-multi-provider
mvn spring-boot:run
```

不需要任何 key、不需要外网。`application.yml` 里两家的 `base-url` 都指向本进程内的 `/fake`。

## 预期看到什么

**1. 路由表**

```bash
curl -s localhost:8080/api/routing
```

```json
{"classify":"qwen","extract":"qwen","reason":"deepseek","*":"deepseek"}
```

**2. 分类任务走便宜的，推理任务走强的**

```bash
curl -s 'localhost:8080/api/ask?task=classify&temperature=1.5'
curl -s 'localhost:8080/api/ask?task=reason&temperature=1.5'
```

第一次返回 `provider=qwen, model=qwen-plus`，第二次返回 `provider=deepseek, model=deepseek-chat`。**业务代码一个字没变。**

**3. 差异翻译真的发生了** —— 这是这个示例最值钱的部分

```bash
curl -s localhost:8080/fake/last
```

`classify`（qwen，`system-as-user-prefix: true`、`max-temperature: 1`）之后：

```json
{"temperature":1.0,
 "messages":[{"content":"你是一个严谨的助手，只输出结论。\n\n帮我判断这条工单的类别","role":"user"}],
 "model":"qwen-plus"}
```

- `temperature` 传的是 **1.5**，发出去变成 **1.0** → 被 `max-temperature` 夹住了
- `system` 只剩一条 `user` 消息 → 被并进了正文

`reason`（deepseek，`max-temperature: 2`、`system-as-user-prefix: false`）之后：

```json
{"temperature":1.5,
 "messages":[{"content":"你是一个严谨的助手，只输出结论。","role":"system"},
             {"content":"帮我判断这条工单的类别","role":"user"}],
 "model":"deepseek-chat"}
```

- `temperature` 原样 **1.5**
- `system` 是独立的一条

同一份 `buildBody` 代码，两段完全不同的报文 —— 差异全在配置里。

## 加第三家厂商要写多少代码

**0 行 Java。** 如果它兼容 OpenAI 协议：

```yaml
llm:
  providers:
    third:
      base-url: https://api.example.com
      api-key: ${THIRD_API_KEY}
      model: third-large
  routing:
    by-task:
      summarize: third
```

重启，`router.chat("summarize", req)` 直接走新厂商。

**真的要写类的情况**：协议不兼容（比如请求体结构完全不同、或者要用厂商自己的签名算法）。那就实现 `LlmProvider`，Bean 名字和 yaml 的 key 相同，`ProviderRegistry` 会优先用它 —— 见 `ProviderRegistry#pickOverride`。

## 已经验证过的

| 项 | 结果 |
| --- | --- |
| `mvn package` | 通过（JDK 21 编译，target 17） |
| 启动 | 无需 key，无需外网 |
| 路由 | `classify` → qwen，`reason` → deepseek，未知任务走 default |
| 温度归一化 | 传 1.5，qwen 实际收到 1.0，deepseek 收到 1.5 |
| system 合并 | qwen 收到 1 条 user（含系统指令），deepseek 收到 system + user |
| 配置写错时 | 路由指向不存在的 provider → **启动期** `IllegalStateException`，不是第一次请求才炸 |

## 工程量

7 个类约 420 行（含假上游），`pom.xml` 一个，`application.yml` 一个。只有 `spring-boot-starter-web` 一个依赖。

## 这个示例没做的

- **额度、重试、降级、审计**：都不在适配器里（3.6 讲的就是这条边界），属于第 4–8 章
- **真厂商的契约测试**：正文 3.2 建议「每接一家补一组契约测试」，这个示例只演示机制，没接真厂商