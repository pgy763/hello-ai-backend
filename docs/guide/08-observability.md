---
title: 第 8 章 · 可观测性：token 计量与成本核算
---

# 第 8 章 · 可观测性：token 计量与成本核算

## 这一章解决什么问题

月底老板问：这个月 AI 花了多少钱？

你说「应该不多」。这个回答在第一次超预算之后，就再也没用了。

更常见的版本是反过来的：账单出来发现是这个月的三倍，然后开始猜 —— 是新上线的功能？还是某个客户在刷？还是我们重试把同一个请求发了五遍？**猜不出来，因为你没有记。**

可观测性不是"接个 Prometheus"，先要回答三个具体问题：

1. **这条请求花了多少钱？** —— 需要 token 数和单价
2. **这笔钱该记在谁头上？** —— 需要租户、功能这些维度
3. **用户等了多久？** —— 需要首字延迟（TTFT），不是总时长

这三个问题的答案都在同一张表里。这一章就是把这张表设计出来，并且把最容易算错的地方一个个验证掉。

## 你会得到

- 一张调用日志表的完整字段清单（12 个字段，每个都说清漏了会怎样）
- token 的三个来源及其代价：`usage` 字段、流式补丁、只能估算（实测偏差 **-12.6%**）
- 一套用整数算钱的成本模型（微元），以及按租户/模型/功能聚合的 SQL
- 前几章日志字段的对齐方案，一条 `trace_id` 串起 7 章的内容

## 前置

[第 4 章 · 虚拟密钥](/guide/04-virtual-key) —— 租户这个维度在那儿就有了。
[第 7 章 · 状态机](/guide/07-state-machine) —— 你已经见过 `llmCalls` 和 `tokens` 两个数字，这一章把它们变成能对账的东西。

## 8.1 一行日志要记什么

一次调用一行。字段不用多，但每一个都得有理由：

| 字段 | 为什么必须记 | 漏了会怎样 |
| --- | --- | --- |
| `trace_id` | 串起一次请求的所有尝试 | 重试过几次查不出来，对账对不上 |
| `attempt` | 第几次尝试 | 分不清"贵"和"重试太多" |
| `tenant` | 谁花的钱 | 没法按客户计费，也查不出谁在刷 |
| `feature` | 哪个功能花的 | 新功能上线后成本涨了，不知道是不是它 |
| `provider` + `model` | 谁家的哪个型号 | 模型改版/换供应商后无法对比 |
| `stream` | 流式还是非流式 | 流式的计量问题和同步的不一样，混在一起看不出异常 |
| `prompt_tokens` | 输入 token | 算不出钱 |
| `cached_tokens` | 命中缓存的输入 token | 会把缓存省下的钱重复算一遍 |
| `completion_tokens` | 输出 token | 输出通常比输入贵一倍以上，算漏了差得最多 |
| `tokens_estimated` | 这行的 token 是估的还是厂商给的 | 估算值和真实值混在账里，误差永远查不清 |
| `cost_micros` | 花了多少钱 | 每次查账都要现算，单价一改历史数据全变 |
| `ttft_ms` + `total_ms` | 首字延迟、总时长 | 只知道"慢"，不知道是"开始得慢"还是"吐得慢" |
| `status` + `error_code` | 成功/被限流/失败 | 失败率算不出来，也不知道该不该重试 |

还有两个容易漏的：**`created_at`**（用服务端时间，别用厂商返回的）和**`request_id`**（厂商给的 ID，跟厂商对账时唯一能对上的东西）。

### 为什么成本要落库，而不是查询时现算

```sql
-- 错：每次查账都现算
select sum(prompt_tokens * 2.0 / 1000000 + completion_tokens * 8.0 / 1000000) from llm_call_log;
```

单价一旦调整（降价、换了套餐、缓存折扣变了），**历史数据会被按新单价重算** —— 上个月的账这个月就变了。

正确做法是下单时就按当时的单价算好、存下来。单价变了是**未来**的事，不该改历史。

## 8.2 token 从哪来：三条路，代价不同

### 第一条：响应里的 `usage`（有就用）

非流式调用，响应里通常带：

```json
{
  "choices": [{"message": {"content": "..."}}],
  "usage": {"prompt_tokens": 2229, "completion_tokens": 159, "total_tokens": 2388}
}
```

用这个数，精确。注意 `prompt_tokens` 是**包含**缓存命中的，缓存命中数在另一个字段里（各家名字不同，`prompt_tokens_details.cached_tokens` 是常见的一种），所以要减出来单独算钱。

### 第二条：流式调用要主动要（能要就要）

流式响应默认常常**不带** `usage` —— 因为流是分块吐的，"总共多少 token"要到最后一个块才知道。

多数厂商支持在请求里加一个开关让它带上（常见写法是 `stream_options: {"include_usage": true}`），最后会多一个只含 `usage` 的块。

示例里两条路都走了，结果一样精确：

```json
{"stream":true,"estimated":false,"promptTokens":2229,"completionTokens":159,"costMicros":5730}
```

**这一条是本章最省钱的一句话：能在请求里要到 usage，就一定要。** 参考各家的 API 文档确认这个开关的名字。

### 第三条：拿不到就只能估（最后手段）

如果厂商就是不给你 usage，只能按字符数估：

```java
static int estimateTokens(String text) {
    return Math.max(1, (int) Math.ceil(text.length() / 2.0));   // 中文约 2 字符/token
}
```

有多不准？示例里让假厂商"偷偷"返回真值，才能算出来：

```json
{"estimated":true,"promptTokens":1950,"completionTokens":139,
 "trueCompletionTokens":159,"errorRate":"-12.6%"}
```

**估算偏低 12.6%，成本也就少算了 12.6%。**

而且现实里你**不知道**偏差是多少 —— 示例能算出 12.6% 是因为假厂商给了真值，真厂商不会给。方向也可能相反：英文文本按"4 字符/token"估，中文按"2 字符/token"估，混排文本里两个都不准。

所以规则是：

| 情况 | 做法 |
| --- | --- |
| 响应里有 `usage` | 用 `usage`，`tokens_estimated=false` |
| 流式、厂商支持 `include_usage` | 打开它，用 `usage` |
| 前两条都不行 | 估算，**`tokens_estimated=true`**，并在报表里单独标出这一部分 |

那个 `tokens_estimated` 字段的价值就在这儿：**账里有多少比例是估的，你必须知道。** 如果 90% 的调用都是估算，那这张表只能用来做趋势，不能用来对账。

## 8.3 成本模型：用整数算钱

### 单价表配置化

模型会改名、会降价、会有阶梯定价、缓存有折扣。这些都不该写死在代码里：

```yaml
pricing:
  models:
    demo-large:
      in-per-m: 2.0        # 输入，元 / 百万 token
      out-per-m: 8.0       # 输出
      cached-in-per-m: 0.5 # 命中缓存的输入，便宜很多
    demo-small:
      in-per-m: 0.5
      out-per-m: 1.5
      cached-in-per-m: 0.1
```

> 上面的数字是示例，不是任何厂商的真实报价。换成你自己合同里的数字。

### 三个坑

**坑一：输出比输入贵，别用同一个单价。** 示例里 out 是 in 的 4 倍。一次调用输入 2229、输出 159，输出只占 token 总量的 6.7%，却贡献了 22% 的成本。

**坑二：缓存命中要单独算，而且要从输入里减掉。**

```java
int billedPrompt = Math.max(0, promptTokens - cachedTokens);
BigDecimal cost = inPerM * billedPrompt + cachedInPerM * cachedTokens + outPerM * completionTokens;
```

漏了减这一步，缓存省下的钱一分都体现不出来，你会以为缓存没用。实测（2229 输入里 800 命中缓存）：

| 情况 | 成本 |
| --- | --- |
| 无缓存 | 5730 微元 |
| 800 token 命中缓存 | **4530 微元（↓21%）** |

**坑三：用整数存钱，别用 double。**

一次调用可能只有 0.00573 元。一天几十万次调用，`double` 累加会出现分位上的漂移，对账时差几毛钱、找半天。

示例用「微元」（1e-6 元）的 `long`：

```json
{"costMicros":5730,"costYuan":"0.005730"}
```

0.005730 元 = 5730 微元。整数相加，永远不会飘。

## 8.4 成本归因：三个维度就够

一张日志表，三个 `group by` 就能回答老板的所有问题：

```sql
select tenant, count(*) as calls,
       sum(prompt_tokens) as prompt_tokens,
       sum(cost_micros) as cost_micros
  from llm_call_log
 where status = 'OK'
 group by tenant
 order by cost_micros desc;
```

实测输出：

```json
按租户: [{"dimension":"t1","calls":3,"prompt_tokens":6408,"costYuan":"0.016472"},
         {"dimension":"t2","calls":2,"prompt_tokens":4458,"costYuan":"0.005883"}]
按模型: [{"dimension":"demo-large","calls":4,"costYuan":"0.021002"},
         {"dimension":"demo-small","calls":1,"costYuan":"0.001353"}]
按功能: [{"dimension":"chat","calls":2,"costYuan":"0.005266"},
         {"dimension":"ticket","calls":1,"costYuan":"0.004640"},
         {"dimension":"batch","calls":2,"costYuan":"0.004460"}]
```

三个维度各自回答一个问题：

- **按租户** → 谁在花钱？能不能按用量收费？
- **按模型** → 是不是某个贵模型被用在了不该用的地方？（比如给"分类"这种简单任务上了最贵的模型）
- **按功能** → 新上线的功能花了多少？值不值？

再加一个维度就有用了：**`where cost_micros > 阈值`**。找出单次特别贵的调用，通常是 prompt 里塞了整个文档，或者输出没限制长度。

## 8.5 延迟：TTFT 和总时长必须分开

用户感受到的不是"总时长 819ms"，是"**等了 259ms 才看到第一个字**"。

| 指标 | 含义 | 用户感受 |
| --- | --- | --- |
| `ttft_ms` | 首字延迟 | 卡不卡 |
| `total_ms` | 总时长 | 读得快不快 |

实测一次流式调用：TTFT 259ms，总时长 819ms。**差的这 560ms 用户是在"读"**，不是在等。

只看总时长会得出错误的优化方向：总时长涨了，可能是首字变慢（要查排队、要查模型版本），也可能是输出变长（prompt 让它多写了，不用优化）。两个数分开记，一眼能分清。

对非流式调用，TTFT 没有意义（第一个字节就是全部内容），记 `null` 就行 —— 但**不要用 0 代替 null**，否则平均值会被拉低。

## 8.6 失败也要记，但别计费

一次被限流后重试成功的调用，日志里是**两行**：

```json
[
  {"attempt":1,"status":"RATE_LIMITED","errorCode":"429","promptTokens":0,"costMicros":0},
  {"attempt":2,"status":"OK","promptTokens":2229,"completionTokens":159,"costMicros":1353}
]
```

两个要点：

**一、失败那行也要记，token 记 0、成本记 0。** 因为 429 没产生 token，不该计费；但"我们被限流了几次"是重要指标 —— 它决定了要不要提配额、要不要加限流。

**二、`trace_id` 相同，`attempt` 递增。** 这样既能算"一次成功请求的真实成本"（按 trace 聚合成功行），也能算"我们为了成功付出了多少次调用"（数行数）。

如果失败发生在**生成到一半**（超时、连接断开），厂商可能已经计费了 —— 这种情况你只能以厂商账单为准，日志里记"我们发了"和"厂商可能算了"两个事实。这也是为什么 `request_id` 要记：对账时只能靠它。

## 8.7 把前七章的日志对齐

这本书前面每一章都在打日志，但字段各写各的。**统一的最小集合就是 8.1 那张表的子集**，每个服务都至少要有这四个：

```
trace_id, tenant, model, cost_micros
```

放进去的办法（Spring Boot，用 MDC 让它们自动出现在每一行日志里）：

```java
// 在 Filter 里塞进去，之后这一线程的所有日志都带
MDC.put("trace_id", traceId);
MDC.put("tenant", tenant);
try {
    chain.doFilter(request, response);
} finally {
    MDC.clear();   // 必须清，线程池会复用线程
}
```

```xml
<!-- logback：一行日志带上前缀，直接可以被日志平台切字段 -->
<pattern>%d{HH:mm:ss.SSS} [%X{trace_id}] [%X{tenant}] %-5level %logger{20} - %msg%n</pattern>
```

两个提醒：

- **`MDC.clear()` 一定要在 `finally` 里。** 线程池复用线程，不清的话下一个请求会带着上一个的 `trace_id` —— 排查时会看到两条毫不相干的日志串在一起，越查越乱。
- **异步线程要手动传。** `@Async`、线程池、`CompletableFuture` 里 MDC 不会自动继承，需要显式复制（`MDC.getCopyOfContextMap()` 再 `setContextMap`）。第 7 章那种多步任务最容易踩这个。

### 该看哪几个指标

日志是用来**查**的，指标是用来**盯**的。四个就够起步：

| 指标 | 报警线（起点） | 说明什么 |
| --- | --- | --- |
| 错误率 | > 5% 持续 5 分钟 | 上游挂了，或者我们的代码坏了 |
| TTFT p95 | > 3s | 用户觉得卡了 |
| 降级率 | > 10% | 主路不稳（第 5 章那条链在频繁启用） |
| 日成本 | > 过去 7 天均值 × 1.5 | 有人在刷，或者某个循环写错了 |

第四条是最容易被忽略、也最值钱的：**它不需要你知道任何技术原因，只要金额不对就叫。** 这是唯一能兜住"你没想到的那种故障"的告警。

## 8.8 结论

一张可以直接抄的表：

```sql
create table llm_call_log (
  id                bigint auto_increment primary key,
  trace_id          varchar(32)  not null,   -- 一个请求一个，重试共用
  attempt           int          not null,
  tenant            varchar(64)  not null,
  feature           varchar(32),
  provider          varchar(32),
  model             varchar(64)  not null,
  request_id        varchar(64),             -- 厂商给的，对账用
  stream            boolean      not null,
  prompt_tokens     int          not null,
  cached_tokens     int          not null,
  completion_tokens int          not null,
  tokens_estimated  boolean      not null,   -- 这行的 token 是估的还是厂商给的
  cost_micros       bigint       not null,   -- 微元，整数存钱
  ttft_ms           int,
  total_ms          int,
  status            varchar(16)  not null,
  error_code        varchar(64),
  created_at        timestamp    not null
);
-- 两个索引：按 trace 查链路，按时间查报表
create index idx_trace   on llm_call_log (trace_id);
create index idx_created on llm_call_log (created_at);
```

三条结论：

1. **成本必须下单时算好、用整数存。** 查询时现算会被单价调整污染历史；用 `double` 累加会对不上账。
2. **能拿到 `usage` 就用，拿不到才估，并且一定要标 `tokens_estimated`。** 实测估算偏低 12.6%，而现实中你连这个 12.6% 都不知道。
3. **TTFT 和总时长分开记，失败也记但 token 记 0。** 前者决定你优化什么，后者决定你该不该提配额。

到这里，"接入层"这条线走完了：从选模型、发第一个请求、多家适配、虚拟密钥、降级、限流熔断、多步编排，到最后的算账。

后面的章节换战场：**把模型用进业务里** —— 先把知识塞给它（RAG），再让它动手（工具调用）。那些是"怎么让它答得对"，而不是"怎么让它稳定地跑"。

→ [第 9 章 · RAG 落地：向量库选型与切分策略](/guide/09-rag-basics)