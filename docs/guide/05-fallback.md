---
title: 第 5 章 · 降级链：主备模型自动切换
---

# 第 5 章 · 降级链：主备模型自动切换

## 这一章解决什么问题

周日晚上主模型挂了四十分钟。你的客服系统跟着挂了四十分钟 —— 明明代码里一个 bug 都没有。

问题不在"厂商会挂"，这是必然会发生的事，早晚而已。问题在你只有一条路。

这一章把第二条路修出来，并且回答一个更难的问题：**什么时候该走第二条路，什么时候走了反而更糟。**

## 你会得到

- 一条按优先级排好的降级链，主路不通自动走备路
- 一张「哪些错误该降级、哪些不该」的判定表 —— 这是本章最值钱的产出
- 一套超时预算，保证用户等的时间有上限，而不是三跳加起来三千毫秒
- 一个九种故障场景全部验证过的示例（`examples/05-fallback/`）

## 前置

[第 3 章 · 统一多模型接入](/guide/03-multi-provider) —— 这一章的降级目标就是第 3 章注册的那些 provider。

## 5.1 降级链不是重试，也不是负载均衡

这三个词经常被混着说，但它们解决的是完全不同的三件事：

| | 用在什么时候 | 失败之后做什么 | 用户会感觉到什么 |
| --- | --- | --- | --- |
| **重试** | 偶发抖动 | 打同一个目标，再来一次 | 慢一点 |
| **负载均衡** | 目标都健康 | 换一个**同样健康**的 | 没有感觉 |
| **降级** | 目标**病了** | 换一个**能力可能更差**的 | 质量可能下降 |

差别在最后一行。

重试和负载均衡都建立在一个假设上：**再试一次就好了。** 降级承认的是另一个前提：**这条路今天不行了，而且不知道什么时候能好。**

所以降级链的每一次跳转都是一个产品决定，不是技术决定：要么给用户一个差一点的答案，要么不给答案。这两个选项都不好听，你必须选一个。

> 顺带说清楚：这一章讲"谁接手"，下一章讲"怎么别再打某一家"。熔断是**停止**打某个目标，降级是**换**一个目标接手 —— 一个是在做减法，一个是在做替换。第六章会细说它们怎么配合。

## 5.2 先分清「挂」的三种样子

大部分人说到"上游挂了"，想的是第一种。实际线上三种都会出现，第三种最坑。

**第一种：连不上**

DNS 解析失败、连接被拒、TLS 握手失败。特征是**快** —— 几百毫秒内就报错。这种最省心，因为它自己会告诉你"我不行"。

**第二种：连上了，但不回话**

TCP 通了，请求发出去了，然后就没有然后了。可能卡在对方的队列里，可能卡在它自己的上游。特征是**慢** —— 你要是不设超时，它能把你挂到天荒地老。

**第三种：回了 200，内容是空的**

```json
{"id":"chatcmpl-xxx","choices":[{"index":0,"message":{"role":"assistant"}}],"usage":{"total_tokens":0}}
```

HTTP 200、Content-Type 正确、JSON 合法 —— **你所有的监控都是绿的。** 然后前端拿到一个空字符串。

第三种是这一章里唯一需要单独写代码检查的情况，因为前两种它自己会喊，第三种不会。示例里它长这样：

```java
if (status == 200) {
    String answer = extractAnswer(body);       // 只认 choices[0].message.content
    if (answer == null || answer.isBlank()) {
        return Attempt.failed(target.name(), Outcome.MALFORMED, ms,
            "HTTP 200 但取不到 content：" + snippet(body));
    }
    return Attempt.ok(target.name(), answer, ms);
}
```

`extractAnswer` 里不要 `asText()` 一把梭：字段可能不存在、可能是 `null`、`content` 可能是个数组（多模态场景），直接强转会在最不该崩的时候抛 NPE。

## 5.3 哪些错该降级，哪些不该 —— 这一章的核心

这是最容易写错的地方。多数人的实现是「catch (Exception e) { 换下一家 }」，然后线上出现两种怪事：

- 死循环：三家全挂，用户等满三次超时
- 故障掩盖：一个我们自己写错的参数，在三个厂商那里各撞一次，日志里三倍的 400

正确的判断标准只有一句：**换一家厂商，这个错误会不会消失？**

会消失的，降级。不会消失的，立刻失败。

示例里这句话写成了一个枚举方法：

```java
public enum Outcome {
    OK,           // 拿到可用答案
    TIMEOUT,      // 超时预算用完
    NETWORK,      // 连不上
    SERVER_ERROR, // 5xx / 429：对方这会儿不行
    CLIENT_ERROR, // 400 参数错：换一家还是错
    NO_QUOTA,     // 这家没额度了：换一家有
    AUTH,         // 401 / 403：我们自己的 key 坏了
    MALFORMED,    // 200 但内容不能用
    SKIPPED;      // 预算不够启动这一跳

    public boolean shouldFallback() {
        return switch (this) {
            case TIMEOUT, NETWORK, SERVER_ERROR, NO_QUOTA, MALFORMED -> true;
            case OK, CLIENT_ERROR, AUTH, SKIPPED -> false;
        };
    }
}
```

整个第五章的判断，就落在这一个方法上。

### 完整对照表

| 上游表现 | 分类 | 降级？ | 理由 |
| --- | --- | --- | --- |
| 连接被拒 / DNS 失败 | `NETWORK` | ✅ | 换一家当然连得上 |
| 超过预算没返回 | `TIMEOUT` | ✅ | 对方卡住了，我们等不起 |
| 500 / 502 / 503 | `SERVER_ERROR` | ✅ | 对方内部出问题 |
| 429 限流 | `SERVER_ERROR` | ✅ | 这家满了，别家可能空着 |
| 400 `invalid_request_error` | `CLIENT_ERROR` | ❌ | **请求体是我们写的**，换一家还是同一个错 |
| 400 `insufficient_quota` | `NO_QUOTA` | ✅ | 这家没额度了，别家有 |
| 401 / 403 | `AUTH` | ❌ | 我们的 key 配错了，切过去也一样 |
| 200 但 content 为空 | `MALFORMED` | ✅ | 这次调用实际失败了 |

### 表里两个反直觉的点

**第一，同样是 400，一个降级一个不降。**

`invalid_request_error` 是我们把请求写错了 —— 换一家，人家照样回你 400，只是白花一跳的时间。
`insufficient_quota` 是这家没钱了 —— 换一家还有额度，降级立刻见效。

都是 4xx，含义完全相反。所以**不要按 HTTP 状态码分类，要按 `error.code` 分类**。这就是第 3 章那个 `error-mapping` 配置存在的原因。

**第二，401 不该降级，但很多人第一反应是"key 坏了赶紧切"。**

恰恰相反。401 说明你的配置有问题，这时候降级只是把一个配置错误藏起来：服务看起来还活着，用的是贵三倍的备路，而没有任何人知道主路的 key 已经失效了两周。

**这一类错误的正确处理是立刻失败 + 告警，让运维来修。**

实测的差距有多大：

| 场景 | 尝试次数 | 总耗时 | 结果 |
| --- | --- | --- | --- |
| 主路 400 参数错 | **1** | 58ms | 失败，日志一条 |
| 主路 401 | **1** | 4ms | 失败，日志一条 |
| 主路 500 | 2 | 9ms | 备路接手 |
| 主路额度用完 | 2 | 12ms | 备路接手 |

## 5.4 降级顺序怎么排

顺序不是"先便宜的，再贵的"，也不是"先好的，再差的"。判据是**能力对等**：备路能不能接下主路的活。

常见的四种排法，各有前提：

| 排法 | 什么时候成立 | 什么时候是灾难 |
| --- | --- | --- |
| 同厂商换小模型 | 任务简单（分类、抽取、摘要） | 需要长上下文或复杂推理 |
| 换厂商同档模型 | 已经做过契约测试，输出格式一致 | 没测过，回来的是另一种 JSON |
| 同厂商换大模型 | 主路是"不够聪明"而不是"挂了" | 成本瞬间翻十倍 |
| 自部署小模型兜底 | 只要"有回复"就行 | 业务要求准确率 |

示例里排的是「主力 → 换厂商同档 → 同厂商小模型」：

```yaml
fallback:
  total-budget-ms: 2500
  targets:
    - name: qwen-primary
      model: qwen-plus
      timeout-ms: 900
    - name: deepseek-backup
      model: deepseek-chat
      timeout-ms: 900
    - name: qwen-small
      model: qwen-turbo
      timeout-ms: 600
```

最后一跳放小模型，是因为前面两跳都挂了的时候，你想要的已经不是"好答案"，而是"别报错"。这个位置放什么，取决于你的业务能不能接受一个质量更低的回复 —— 这是产品问题，我替不了你决定。

**但有一个硬性要求：备路必须支持主路用到的所有参数。**

如果你的业务在用 Tool Calling，备路不支持，那降级过去会得到一个语法正确、语义全错的回答 —— 比直接报错更糟。这一条只能靠契约测试守住，见第 3 章 3.2。

## 5.5 一条能跑的降级链

主循环只有二十行，但三个约束都藏在里面：

```java
public Answer ask(String prompt) {
    long start = System.nanoTime();
    long deadline = start + totalBudgetMs * 1_000_000L;
    List<Attempt> attempts = new ArrayList<>();

    for (FallbackProperties.TargetConfig target : targets) {
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;

        // 约束一：预算不够就别启动这一跳，启动了也来不及
        if (remaining < target.minBudgetMs()) {
            attempts.add(Attempt.failed(target.name(), Outcome.SKIPPED, 0,
                "剩余预算 " + remaining + "ms，不够启动这一跳"));
            continue;
        }

        // 约束二：每一跳能用多久，由前面花掉多少决定
        long budget = Math.min(target.timeoutMs(), remaining);
        Attempt attempt = client.call(target, prompt, budget);
        attempts.add(attempt);

        if (attempt.outcome() == Outcome.OK) {
            return new Answer(attempt.answer(), target.name(), target.model(),
                attempts.size() > 1, attempts, elapsedMs(start));
        }

        // 约束三：换一家会撞同样墙的错，立刻停
        if (!attempt.outcome().shouldFallback()) {
            break;
        }
    }
    throw new AllTargetsFailedException(attempts, elapsedMs(start));
}
```

### 必须把每一次尝试带出去

注意 `attempts` 这个列表，它最后会原样出现在响应里：

```json
{"error":"all_targets_failed","totalMs":2443,
 "attempts":[
   {"target":"qwen-primary","outcome":"TIMEOUT","ms":925},
   {"target":"deepseek-backup","outcome":"TIMEOUT","ms":911},
   {"target":"qwen-small","outcome":"TIMEOUT","ms":602}]}
```

没有这段，线上出问题时你只能看到"接口 503"，然后开始猜：是主路挂了？是备路也挂了？还是压根没配上？

**降级链的调试成本，几乎全部来自"不知道卡在第几跳"。** 把每一次尝试写进响应和日志，是这一章性价比最高的一行代码。

## 5.6 超时预算：为什么"每跳 900ms"不等于"总共 2.7 秒"

如果你给每一跳都设 900ms 超时，三跳全挂的时候用户要等 2700ms —— 加上网络往返，三秒。三秒没有回复，用户已经认为你挂了。

所以需要一个**总死线**，而且每一跳能用多久要看"还剩多少"：

```java
long remaining = (deadline - System.nanoTime()) / 1_000_000L;
if (remaining < target.minBudgetMs()) {
    // 剩 267ms，启动这一跳也注定超时，不如直接跳过
}
long budget = Math.min(target.timeoutMs(), remaining);
```

实测把总预算从 2500ms 压到 1200ms，同样的"三条全卡死"，行为立刻变了：

```
qwen-primary    TIMEOUT   929ms
deepseek-backup SKIPPED     0ms   ← 剩余 267ms，不够 400ms 的起跳门槛
qwen-small      TIMEOUT   277ms   ← 用剩下的 267ms 打，超时就超时
```

**总耗时 1212ms，而不是 2700ms。** 用户少等了一秒半，代价是第三跳可能只跑了一半就被砍掉 —— 这笔交换在故障场景里永远划算，因为一个跑了 90% 然后失败的答案，和没跑是一样的。

### 总预算该给多少

从用户那头倒推，不要从技术这头正推：

| 场景 | 用户能忍多久 | 建议总预算 |
| --- | --- | --- |
| 聊天界面（有打字机效果） | 首字 2 秒内 | 2500–3000ms |
| 表单里点"帮我写"（同步等结果） | 5 秒 | 4000–5000ms |
| 后台任务（异步） | 几分钟 | 不需要降级预算，用重试 |

超过这个数，降级的价值就没了 —— 用户早就关掉页面了。

### 一个实测撞出来的坑

`HttpRequest.timeout()` 和 `CompletableFuture.orTimeout()` 都能做超时，但抛的异常不一样：

- `orTimeout` → `TimeoutException`
- `HttpRequest.timeout` → `HttpTimeoutException`（它是 `IOException` 的子类）

两个都设的时候，谁先触发取决于时序 —— 于是同一个"超时"，有时分类成 `TIMEOUT`，有时分类成 `NETWORK`。我在示例里第一次跑"三条全卡死"就是这个结果：

```
qwen-primary    TIMEOUT      905ms
deepseek-backup NETWORK      900ms   ← HttpTimeoutException 被当成了连接失败
```

**统计报表会因此对不上，告警规则也会漏。** 修法是把两者归一到同一个分类：

```java
if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
    return Attempt.failed(target.name(), Outcome.TIMEOUT, ms, "超过 " + budgetMs + "ms 预算");
}
```

另外注意：`HttpRequest.timeout()` 只覆盖到**响应头到达**为止，不覆盖读完 body。对 SSE 这种流式响应，它基本等于没设。真正的总时长限制只能靠外层 `orTimeout` 或者你自己读流的循环计时。

## 5.7 降级发生了，然后呢

降级本身只是让请求活下来。接下来三件事必须处理，否则你会用另一种方式还债。

### 一、必须在响应里标出来

```java
return new Answer(attempt.answer(), target.name(), target.model(),
    attempts.size() > 1, attempts, elapsedMs(start));
//  ↑ degraded：这条回答不是主路给的
```

不标会怎样？举两个真实会发生的：

- 下游业务拿这条回复做了个自动化决策，质量下降没人知道
- 一个月后复盘"最近回答质量变差了"，查了半天才发现主路已经降级运行了两周

`degraded=true` 应该同时进三个地方：响应体、日志、监控指标。**指标这一条最重要** —— 降级率突然从 0 涨到 30%，那就是主路出问题的第一信号，比任何告警都快。

### 二、能力不对等

备路能回答，不代表回答得一样好。三件事提前想清楚：

| 差异 | 后果 | 怎么办 |
| --- | --- | --- |
| 上下文窗口更小 | 长 prompt 直接报错 | 降级前先截断，或者干脆不降级 |
| 不支持 Tool Calling | 返回一段"我建议你调用…"的文本 | 契约测试提前发现 |
| 输出格式不同（JSON mode） | 解析失败 | 降级路径要有单独的输出解析 |

第三条最容易出事：主路用 JSON mode 保证返回合法 JSON，备路不支持，返回了一段带 markdown 围栏的文本，你的 `readTree()` 直接抛异常 —— 而这个异常发生在"降级成功"之后，日志上看起来像是备路的锅。

### 三、成本不一定降

直觉上"备路 = 更便宜"，实际经常相反：主路走的是包月套餐，备路是按量付费的贵三倍的那家。

**降级期间的成本要单独统计。** 否则月底你只会看到"AI 支出涨了 40%"，然后花两天才想起来那周主路挂过。

## 5.8 什么时候不该降级

这一节讲的是"别把降级当成万能药"。三种情况下，失败比降级更好。

**一、结果会被拿去做不可逆的动作**

比如"根据模型输出直接调用退款接口"。这种情况下，一个质量没保证的答案比一个明确的错误危险得多 —— 错误用户可以重试，错误地退了款不会自己回来。

**二、降级之后的质量，业务根本不能用**

如果备路的准确率只有 60%，而业务要求 95%，那降级只是把"一次失败"变成"一次看起来成功的失败"。用户不会重试，因为他以为成功了。

**判据：降级后的结果如果必须再人工校验一遍，那还不如直接失败。**

**三、这次调用是给"另一个系统"看的**

给下游系统返回降级结果，会让污染的传播范围更大 —— 下游可能缓存它、基于它做决策、再传给更下游。人看到"系统繁忙"会重试，系统看到一个格式正确的错答案会一路传下去。

### 一张按业务重要度分的表

| 业务类型 | 该不该降级 | 理由 |
| --- | --- | --- |
| 客服问答 | ✅ 降级 | 差一点的回答也比"系统错误"好 |
| 代码补全 | ✅ 降级 | 用户能看出来质量 |
| 内容摘要 | ✅ 降级 | 摘要质量下降可接受 |
| 数据抽取（进数据库） | ⚠️ 看情况 | 必须标注 `degraded`，下游要能过滤 |
| 自动决策（退款/风控） | ❌ 不降级 | 不可逆 |
| 给其他系统调用的 API | ❌ 默认不降级 | 污染会传播 |

## 5.9 结论

如果只记三句话：

1. **降级的判断标准是「换一家这个错会不会消失」，不是「是不是抛异常了」。** 400 参数错和 401 配置错必须立刻失败，降级只会把问题藏起来。
2. **超时要有总预算，而且预算要递减。** 每一跳 900ms 加起来是 2.7 秒，用户等不到第四秒。剩 267ms 就别启动下一跳了。
3. **降级必须可见。** 响应里的 `degraded`、日志里的跳转原因、监控里的降级率 —— 少一个，你就只能等用户来告诉你"最近回答变差了"。

这一章的示例把九种故障场景都跑过了（`examples/05-fallback/`），你可以直接把 `Outcome.shouldFallback()` 那张表抄走 —— 那张表比这段代码更值钱。

**下一章是这一章的另一半**：降级解决"换谁"，但没解决"怎么停止打一个已经确定坏掉的目标"。如果主路挂了，你那 200 QPS 还是会每秒打它 200 次，每次等 900ms 超时 —— 你已经知道它坏了，代码还在试。

→ [第 6 章 · 熔断与限流：Redis + Lua 令牌桶](/guide/06-ratelimit)