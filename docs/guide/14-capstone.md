---
title: 第 14 章 · 毕业设计：工单智能助手
---

# 第 14 章 · 毕业设计：工单智能助手

## 这一章解决什么问题

前面 13 章是零件。这一章把它们装成一台能跑的机器 —— **而且你能自己动手装一遍**。

不给新知识。给的是一个完整项目：需求、数据模型、分步实现、验收标准，每一块都能对上前面的章节。

## 你会得到

- 一个能跑起来的项目：**工单智能助手**（客服工单自动分类 + 基于知识库作答）
- 七步实现路径，每步都有可验收的产出
- 一份验收清单，做完就知道自己掌握了没有
- 一份实测报告：这套东西每张工单花多少钱

## 为什么选这个题目

因为它同时具备四个特征，**缺一个就练不到东西**：

| 特征 | 为什么重要 |
| --- | --- |
| 异步（工单不是同步返回的） | 逼你处理状态机、重试、断点续跑 |
| 有知识库（答案在文档里） | 逼你做 RAG，而不是把问题直接丢给模型 |
| 有成本（每次调用都花钱） | 逼你做计量和降级 |
| 会失败（上游 503、解析失败、配额超限） | 逼你做重试、退避、死信 |

对比一下常见的毕设题目：「做一个聊天机器人」—— 一个 HTTP 转发就完事了，练不到上面任何一条。

## 14.1 需求：三句话

```
客服在后台提交一条工单文本
系统自动给它分类（退款/发票/物流/会员/其他）
再基于知识库生成一条带引用来源的答复建议
```

就这样。没有前端，没有用户体系，没有多轮对话 —— **这些是加分项，不是核心**。

三条验收线：

1. 一条工单从提交到出结果，**不超过 5 秒**，中间状态可见
2. 答案里的每一句话，都能追溯到知识库的**某个文档的某一段**
3. 每张工单的成本**能算出来**，且主模型挂掉时系统**不挂**

## 14.2 架构：一次请求的完整旅程

```
POST /api/tickets  ──→  ticket 表（PENDING）
                              │
                    ┌─────────┴──────────┐
                    │  定时任务（200ms）   │
                    │  抢占一条 PENDING   │
                    └─────────┬──────────┘
                              ↓
                    ① 限流（令牌桶，容量 5 / 2 QPS）
                              ↓
                    ② 分类：调模型（小 prompt）
                              ↓
                    ③ 检索：知识库 top-2（不花钱）
                              ↓
                    ④ 生成：调模型（带资料的 prompt）
                              ↓
                    ⑤ 记账：写入 llm_call，累加到 ticket
                              ↓
                    ANSWERED ── 失败 ──→ PENDING（退避重试）──→ DEAD（人工重入）
```

每个环节对应哪一章：

| 环节 | 来自哪一章 | 示例里的类 |
| --- | --- | --- |
| 令牌桶限流 | 第 6 章 | `LlmGateway.TokenBucket` |
| 主模型 → 降级 | 第 5 章 | `LlmGateway.complete` |
| 状态机 + 抢占 | 第 7 章 | `TicketService.tick` |
| token 计量与成本 | 第 8 章 | `llm_call` 表 + `costMicros` |
| 切分与检索 | 第 9 章 | `Kb` |
| 重试、退避、死信 | 第 10 章 | `TicketService.process` |

**这就是为什么这本书按这个顺序写** —— 拆开看都是小问题，合起来才是工程。

## 14.3 数据模型：两张表够了

```sql
create table ticket (
  id varchar(32) primary key,
  text clob not null,
  status varchar(16) not null,       -- PENDING / RUNNING / ANSWERED / DEAD
  attempt int not null default 0,    -- 重试次数
  category varchar(32),              -- 分类结果
  answer clob,                       -- 生成的答复
  citations varchar(255),            -- 引用了哪些块，如 d1/2,d1/0
  error varchar(255),                -- 失败原因
  cost_micros bigint not null default 0,
  created_at timestamp not null,
  updated_at timestamp not null
);

create table llm_call (
  id bigint auto_increment primary key,
  ticket_id varchar(32),
  provider varchar(32),              -- big-model / small-model
  purpose varchar(16),               -- classify / answer
  prompt_tokens int,
  completion_tokens int,
  cost_micros bigint,
  latency_ms bigint,
  created_at timestamp not null
);
```

两个设计决定值得说明：

**一、`citations` 先存字符串。** 演示项目不需要 join；真实项目里应该建 `ticket_chunk` 关联表，因为要统计"哪些文档最常被引用"。

**二、`cost_micros` 用整数存"微元"。** 一张工单 0.0007 元，用 `decimal` 也行，但整数永远不会因为精度问题算出 0（第 8 章踩过这个坑）。

## 14.4 七步实现

示例代码在 [`examples/14-capstone/`](https://github.com/pgy763/hello-ai-backend/tree/main/examples/14-capstone)，一共 5 个类、约 700 行。按下面顺序读，每步都能单独验收。

### 第一步：建表 + 工单入库

```java
String create(String text) {
    String id = "t-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("insert into ticket (id, text, status, ...) values (?, ?, 'PENDING', ...)", id, text);
    return id;
}
```

**验收**：`POST /api/tickets` 返回 id，`GET /api/tickets/{id}` 能看到 `PENDING`。

### 第二步：定时抢占 + 状态流转

```java
@Scheduled(fixedDelay = 200)
public void tick() {
    // 捞一批 PENDING，按退避时间过滤，再用 CAS 抢占
    int claimed = jdbc.update("update ticket set status='RUNNING' where id=? and status='PENDING'", id);
    if (claimed == 0) return;      // 被别人抢走了
    process(id);
}
```

**验收**：提交工单后 1 秒内状态变成 `ANSWERED`（或 `DEAD`）。

> 单实例这个写法够用。多实例必须靠 CAS（第 7 章），否则两条线程抢同一张工单，重复扣费。

### 第三步：模型网关 —— 限流、降级、记账

```java
Reply complete(String ticketId, String purpose, String prompt) {
    if (!limiter.tryTake()) throw new RateLimited("本地令牌桶已空");
    try {
        return call(ticketId, purpose, prompt, "big-model", ...);
    } catch (UpstreamException e) {
        if (mode == Mode.ALL_DOWN) throw e;
        return call(ticketId, purpose, prompt, "small-model", ...);   // 降级
    }
}
```

三层，一层都不能省：

- **限流在前**：配额没拿到，连发都不要发（第 6 章）
- **降级在后**：主模型 503 时还有一条路（第 5 章）
- **记账在里**：无论走哪条路，都写一条 `llm_call`（第 8 章）

**验收**：`POST /api/fault?mode=primary_down` 后提交工单，仍然 `ANSWERED`，但 `byProvider` 里出现 `small-model`。

### 第四步：接 RAG

```java
List<Kb.Hit> hits = kb.search(text, 2);          // 检索不花钱
StringBuilder prompt = new StringBuilder("只根据下面的资料回答…\n");
for (int i = 0; i < hits.size(); i++) {
    prompt.append('[').append(i + 1).append("] 《").append(c.title()).append("》 ")
          .append(c.text()).append('\n');
}
prompt.append("\n工单：").append(text);
```

三个细节决定质量：

- **编号 `[1]`**：让模型能引用，用户能核对
- **带标题**：模型知道这段在讲什么，不容易张冠李戴
- **"资料里没有就说没有"**：不写这句，模型一定会编

**验收**：答案里出现 `[1] [2]`，且 `citations` 字段能对应到具体块。

### 第五步：失败退避 + 死信

```java
int attempt = current + 1;
boolean dead = attempt >= maxAttempts;
jdbc.update("update ticket set status=?, attempt=?, error=? where id=?",
    dead ? "DEAD" : "PENDING", attempt, e.getMessage(), id);
```

退避在抢占那一步做：

```java
long backoffMs = 500L << Math.min(attempt, 4);   // 500ms、1s、2s、4s、8s
if (now - updatedAt < backoffMs) continue;       // 还没到时间，跳过
```

**验收**：`POST /api/fault?mode=all_down` 后提交工单，观察 `attempt` 从 1 走到 3、状态变 `DEAD`；恢复后 `POST /api/tickets/{id}/retry` 能救回来。

### 第六步：成本账本

```java
long costMicros = Math.round(promptTokens * inPrice + completionTokens * outPrice);
```

因为单价是「元 / 百万 token」，所以 `tokens × 单价` 直接就是微元 —— 这个等式能省掉全部精度烦恼。

**验收**：`GET /api/cost` 能看到总成本、按 provider 拆分、按工单拆分。

### 第七步：故障注入验证

```bash
curl -s -X POST 'localhost:8080/api/fault?mode=primary_down'
curl -s -X POST 'localhost:8080/api/fault?mode=all_down'
curl -s -X POST 'localhost:8080/api/fault?mode=ok'
```

**这一步不是可选的。** 没有故障注入，你永远不知道降级和死信写对没 —— 你也永远不敢在面试里说"我做了降级"。

## 14.5 实测：完整跑一遍

启动服务，提交三张工单：

```bash
mvn spring-boot:run

curl -s -X POST localhost:8080/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"text":"我买的东西退款多久能到账？"}' | jq
# {"id":"t-1ffe373b","status":"PENDING"}

sleep 3
curl -s localhost:8080/api/tickets/t-1ffe373b | jq
```

实测结果（三张工单）：

| 工单 | 状态 | 分类 | 成本 |
| --- | --- | --- | --- |
| 我买的东西退款多久能到账？ | ANSWERED | 退款 | 0.0008 元 |
| 开专票需要提供哪些资料？ | ANSWERED | 发票 | 0.0007 元 |
| 新疆发货一般要几天？ | ANSWERED | 物流 | 0.0007 元 |

答案长这样：

```
根据知识库： [1] 《退款政策》 退款到账时间取决于支付方式：银行卡 1-3 个工作日，支付宝和微信 24 小时内到账。
[2] 《退款政策》 支持 7 天无理由退款，从签收次日 0 点开始计算，商品需保持完好、配件齐全。
```

成本汇总：**6 次调用 / 2136 微元（0.0021 元）**，其中生成 2000 微元、分类 136 微元。

**分类只占总成本的 6%，但它占了一半的调用次数。** 这说明：便宜的小请求该怎么放，值得单独设计（第 5 章的路由）。

### 主模型故障

```bash
curl -s -X POST 'localhost:8080/api/fault?mode=primary_down'
```

提交一张会员工单后：

| provider | purpose | 调用数 | 成本 |
| --- | --- | --- | --- |
| big-model | answer | 3 | 2000 微元 |
| big-model | classify | 3 | 136 微元 |
| **small-model** | answer | 1 | **99 微元** |
| **small-model** | classify | 1 | **10 微元** |

降级后的那张工单成本 **0.0001 元**，比正常的 0.0007 元便宜 86%。

**这是个陷阱**：降级省了钱，但小模型的质量你不知道。所以降级必须打标记（这里用 `provider` 字段），事后能筛出"这批答案是降级期生成的"。

### 全挂 → 死信 → 恢复

```bash
curl -s -X POST 'localhost:8080/api/fault?mode=all_down'
```

实测时间线：

| 时刻 | 状态 | attempt |
| --- | --- | --- |
| 775ms | PENDING | 1 |
| 2248ms | PENDING | 2 |
| 4487ms | **DEAD** | 3 |

恢复后 `POST /api/tickets/{id}/retry`，2 秒内变回 `ANSWERED`，分类是「物流」，引用了正确的文档。

**注意退避的间隔**：500ms → 1s → 2s，总共 4.5 秒烧完 3 次机会。如果改成固定 200ms，三次重试在 600ms 内打完 —— 上游一挂，你的配额瞬间见底。

## 14.6 验收清单

做完之后对着这张表自测。**每一项都能当场演示，才叫做完。**

### 基础层（必须全过）

- [ ] 提交工单返回 id，1 秒内状态从 `PENDING` 变成 `ANSWERED`
- [ ] 答案里有 `[1] [2]` 引用编号，且能对应到具体文档
- [ ] `GET /api/cost` 能算出总成本和按 provider 的拆分
- [ ] 主模型故障时自动降级，业务无感（`provider` 字段能看到 `small-model`）
- [ ] 全挂时重试 3 次进 `DEAD`，且重试有退避
- [ ] `retry` 接口能把 `DEAD` 救回来
- [ ] 限流触发时返回 **429**（不是 500），带 `Retry-After`

### 进阶层（选做 3 项）

- [ ] 把 `Kb.embed` 换成真的 embedding 服务，检索效果对比测过
- [ ] 把内存里的知识库换成 pgvector / Redis，重启不丢
- [ ] 多实例部署，用 CAS 抢占，不重复处理同一张工单
- [ ] 加一个 `GET /api/metrics`，暴露 P95 延迟和失败率
- [ ] 相同文本 5 分钟内复用结果，命中缓存的工单不再调模型

### 收尾（决定能不能写进简历）

- [ ] 一张架构图，标清每个模块对应解决什么问题
- [ ] 一份压测报告：多少并发开始劣化，P95 是多少（第 11 章的方法）
- [ ] 至少一篇事故复盘：你踩的坑、怎么发现的、怎么修的（第 12 章）

## 14.7 换成真实组件

示例用替身实现，是为了**不需要 key 就能跑**。换成真的只动一处：

| 替身 | 换成真的 | 改动量 |
| --- | --- | --- |
| `LlmGateway.FakeModel` | 真实 HTTP 调用（第 3 章的 Provider） | 一个方法 |
| `Kb` 的哈希向量 | 真 embedding 服务 + pgvector | `embed()` + 检索层 |
| H2 内存库 | MySQL | 连接串 |
| 内存令牌桶 | Redis + Lua（第 6 章） | 一个类 |

**注意：换的时候，外面那层壳（状态机、降级、记账、退避）一行都不用改。** 这就是"接入层"的意义 —— 第 1 章讲的那个道理，在这里兑现了。

### 换成真模型：两个环境变量

示例里的 `LlmGateway` 已经有 real 模式，走 **OpenAI 兼容**协议（DeepSeek、通义、智谱、Kimi、本地 vLLM 都能用）：

```bash
HELLOAI_MODE=real \
HELLOAI_BASE_URL=https://api.deepseek.com/v1 \
HELLOAI_API_KEY=sk-你的key \
HELLOAI_MODEL=deepseek-chat \
mvn spring-boot:run
```

**key 只从环境变量读。** `application.yml` 里写的是 `${HELLOAI_API_KEY:}`，没有默认值 —— 没设置时调用会立刻报错，不会把请求发出去。

三条规矩：

1. **不要把 key 写进任何被提交的文件**（yml、md、测试代码）。`.gitignore` 能挡住 `application-local.yml`，但环境变量更安全。
2. **一旦 key 出现在聊天记录、Issue、截图里，就当泄漏处理 —— 立刻吊销，而不是删文件。** 删文件没有用，见第 12 章案例。
3. **只是验证降级/死信的话，不要用真 key。** fake 模式一分钱不花，行为完全一样。

真实模式下启动日志会说明当前状态，**但不会打印 key 的内容**：

```
模型模式: REAL   base-url=https://api.deepseek.com/v1   api-key=已设置   model=deepseek-chat
```

## 14.8 怎么把它写进简历

三条，每条都要有数字：

> - 设计并实现了一套 AI 接入层，统一封装多模型调用、降级与限流，主模型故障时自动切换，业务侧零改动
> - 基于 RAG 实现的工单智能助手，答案带引用来源；检索召回率经评测从 0.70 提升到 1.00（top-3）
> - 建立了 token 级成本归因，单工单成本 0.0007 元可追溯；压测定位到系统在 8 并发饱和、P95 从 417ms 劣化到 3248ms

**没有数字的简历，面试官会默认你没做过。** 因为做过的人一定会记得那几个数。

## 14.9 结论

三条结论：

1. **一个能讲清楚的项目，胜过三个 demo。** 工单助手一共 5 个类、700 行，但它覆盖了限流、降级、状态机、重试、RAG、计量六件事 —— 面试官随便挑一个问，你都能答到细节。
2. **故障注入不是锦上添花。** 不注入故障，你写的降级和死信就是"看起来对"；注入之后，你能报出「775ms 第 1 次、2248ms 第 2 次、4487ms 进死信」这种数字，这才是做过。
3. **所有的"替换"都不该改业务代码。** 替身 embedding → 真模型、H2 → MySQL、内存桶 → Redis，改的都是一个类。如果你的替换要动十处，说明接入层的边界画错了。

到这里，全书的主线就走完了：**从一次请求，到一整套能扛住故障、算得清成本、讲得明白取舍的接入层。**

最后一章是给你查漏补缺的 —— 30 道面试题，每题都能在这本书里找到出处。

← [第 13 章 · 面试 30 问](/guide/13-interview)