---
title: 第 7 章 · 多步任务编排：状态机与断点续跑
---

# 第 7 章 · 多步任务编排：状态机与断点续跑

## 这一章解决什么问题

一个「分析这批工单」的任务要跑 20 分钟，中间调了 8 次模型。跑到第 6 步，服务重启了。

于是：前 5 次调用的钱白花了（真金白银），20 分钟白等了，用户还得从头再点一次。如果这个任务一天跑一千次、一周重启一次 —— 这笔浪费是可以算出来的。

更糟的是另一种写法：把整段逻辑塞进一个 `while` 循环加一个 `retry` 计数器。看起来更简单，实际上四种死法：

| 死法 | 表现 |
| --- | --- |
| 重复计费 | 重试时把已经成功的前几步又跑一遍，钱双倍 |
| 状态丢失 | 进程重启，循环里的局部变量全没了，只能从头来 |
| 并发双跑 | 用户连点两次，两个线程同时跑同一个任务 |
| 卡死不回收 | 进程被杀，任务永远停在「进行中」，谁也发现不了 |

这一章把这四种死法一个一个堵上。手段只有一个：**把任务的状态写进数据库。**

## 你会得到

- 两张表（任务 + 步骤），任务跑到哪一步在数据库里查得到
- 断点续跑：实测只补 1 次调用，而不是重跑 3 次
- 一行 CAS 挡住并发：3 个请求同时续跑，只有 1 个拿到任务
- 租约与僵尸回收：`kill -9` 之后任务能被接手，且已完成的步骤不会被重跑

## 前置

[第 5 章 · 降级链](/guide/05-fallback) —— 一次调用可能失败，那一步失败之后怎么办，是这一章的问题。
[第 6 章 · 熔断与限流](/guide/06-ratelimit) —— 任务里的每一次调用都该受它们保护。

## 7.1 三种代价，状态机只省第三种

先说清楚状态机**不**解决什么：它不会让单次调用变快，也不会让模型变聪明。它只解决一件事：

> **任务被中断之后，怎么不重复做已经做完的事。**

值得为它付出复杂度的前提是：**重跑很贵。** 贵在三个地方：

**一、钱。** 一次调用几分钱，8 步一次任务就是几毛。一天一千次任务是几百块。重跑一遍就是双倍。

**二、时间。** 20 分钟的任务重跑一遍又是 20 分钟。用户等不起，你的机器也占着。

**三、非确定性。** 这条最容易被忽略：**同一个 prompt 调两次，结果不一样。** 所以「重跑」不只是多花钱，还会让最终结果和用户之前看到的不一致 —— 第 3 步基于第 2 步的输出，你重跑了第 2 步，第 3 步就白做了。

第三条决定了做法：**每一步的产物必须落库，续跑时从库里读，绝不重算。**

## 7.2 两张表

状态机不是"写个 enum 存一下"，是两个实体：

```
llm_task       一个任务一行：整体状态 + 当前步 + 租约
llm_task_step  一步一行：（task_id, step_name）唯一
```

步骤表的关键字段：

| 字段 | 为什么要 |
| --- | --- |
| `status` | `PENDING / RUNNING / SUCCEEDED / FAILED` |
| `attempt` | 试了几次 —— 用来发现"一直失败"的步骤 |
| `output` | 产物落库。续跑时后面的步骤靠它，不靠重算 |
| `started_at / finished_at` | 每步耗时，才知道慢在哪 |

任务状态和步骤状态是两级：任务 `SUCCEEDED` 意味着所有步骤 `SUCCEEDED`；任何一步 `FAILED`，任务就 `FAILED`，但**已经成功的步骤保持原样** —— 这才是续跑的前提。

### 状态转换要能一眼看出非法

```java
PENDING   → RUNNING → SUCCEEDED
                    → FAILED → RUNNING（续跑）
```

想禁止的转换，靠一条 SQL 的 `WHERE` 条件挡住，而不是靠代码里的 if：

```sql
update llm_task set status = 'RUNNING'
 where id = ? and status in ('PENDING', 'FAILED')
```

这就是 7.5 节那个 CAS。**非法转换 = 更新影响 0 行**，天然并发安全。

## 7.3 步骤边界怎么切

这是这一章最容易做错的地方。切错了，续跑省不下钱；切太碎，一本书的代码量。

三条判据，按重要性排：

**一、切在"花钱的调用"外围。** 一次 LLM 调用就是一步。这样"重跑"的粒度正好等于"一次计费"，不会有"重跑了一个包含两次调用的步骤，白花一次"。

**二、每一步的产物能被下一步直接使用。** 也就是步骤之间只通过落库的文本传递，不通过内存里的对象。否则重启之后对象没了，续跑照样得重算。

**三、每一步都能独立观察。** 你希望日志里看到"第 3 步花了 4.2 秒、失败了一次"，而不是"任务跑了 87 秒"。

反面例子：

```java
// 错：把「抽取 + 分类」写成一步
String out = llm.complete(extractPrompt + classifyPrompt);
// 抽取出结果了，分类失败了 —— 这一步的 output 是 null，续跑时抽取也得重做
```

```java
// 对：一步一次调用
extract:   llm.complete("抽取要素：" + input)
classify:  llm.complete("分类：" + ctx.of("extract"))
```

## 7.4 续跑：只有一行代码

整个断点续跑，本质就是一个 `continue`：

```java
for (Step step : steps) {
    StepRow row = loadStep(taskId, step.name());
    if ("SUCCEEDED".equals(row.status())) {
        continue;              // ← 已完成，跳过，不花钱
    }
    executeStep(taskId, step, new TaskContext(taskId, input, outputs(taskId)));
}
```

`outputs(taskId)` 从库里把前面所有步骤的产物读出来，拼成下一步的输入。**注意它是从数据库读的，不是从内存里的 `Map` 读的** —— 否则进程一重启，前面的产物全没了，跳过也没用。

### 实测：省下来的就是钱

示例里给第三步注入一次失败：

```json
{"status":"FAILED","failedStep":"summarize","claimed":true}
步骤: extract=SUCCEEDED/a1  classify=SUCCEEDED/a1  summarize=FAILED/a1  persist=PENDING/a0
LLM 调用: {"llmCalls":2,"tokens":400}
```

此时 2 次调用已经花掉。接着续跑：

```json
{"status":"SUCCEEDED","failedStep":null,"claimed":true,"message":"全部步骤完成"}
步骤: extract=SUCCEEDED/a1  classify=SUCCEEDED/a1  summarize=SUCCEEDED/a2  persist=SUCCEEDED/a1
LLM 调用: {"llmCalls":3,"tokens":600}
```

**总共 3 次调用，不是 5 次。**

| 做法 | LLM 调用 | token |
| --- | --- | --- |
| 续跑（本章做法） | **3** | **600** |
| 从头重跑 | 5 | 1000 |

前两步的 `attempt` 还是 1，说明它们真的没有被重跑 —— 这个 `attempt` 字段就是给这种时候检查用的。

## 7.5 并发：一行 SQL 就够

「用户连点两次」和「两个实例同时续跑」是同一个问题。解法不是分布式锁，是一条带条件的 `UPDATE`：

```java
private boolean claim(String taskId, boolean resume) {
    String sql = resume
        ? "update llm_task set status = 'RUNNING', lease_until = ?, updated_at = ? "
          + " where id = ? and status in ('PENDING','FAILED')"
        : "update llm_task set status = 'RUNNING', lease_until = ?, updated_at = ? "
          + " where id = ? and status = 'PENDING'";

    return jdbc.update(sql, lease, now, taskId) == 1;   // 只有一行被改，才算抢到
}
```

数据库的行锁保证了：**只有第一个 `UPDATE` 能把 `PENDING` 改成 `RUNNING`**，后面的都影响 0 行。不需要 Redis 锁，不需要 `synchronized`。

实测（任务跑着的时候并发打 3 次）：

```json
{"status":"RUNNING","claimed":false,"message":"任务当前状态为 RUNNING，不能在这里启动"}
{"status":"RUNNING","claimed":false,"message":"任务当前状态为 RUNNING，不能在这里启动"}
{"status":"RUNNING","claimed":false,"message":"任务当前状态为 RUNNING，不能在这里启动"}
```

三个都被拒了，任务的每一步 `attempt` 都是 1，总共 3 次调用 —— **没有一次重复**。

### 为什么不用 Redis 分布式锁

能做，但没有必要：**加锁的目的是改数据库里的状态，而数据库自己就能原子地做这件事。** 引入 Redis 只是多了一个会挂的组件，还多了一次网络往返。

什么时候真的需要分布式锁？当你的"临界区"跨了数据库之外的东西 —— 比如"抢占成功后要往对象存储写文件"。那种情况下，锁的粒度也不再是任务，而是那个外部资源。

## 7.6 僵尸任务：租约到期就能接手

最容易漏的一种情况：进程被 `kill -9`，它正在跑的任务状态停在 `RUNNING`，**永远不会再变**。

因为没有进程知道"这个任务的前任已经死了"。它在列表里永远显示"进行中"，用户永远等不到结果。

解法是**租约（lease）**：

- 抢到任务时写一个 `lease_until = now + 6s`
- 跑的过程中，每 2 秒续一次（心跳）
- 进程死了 → 心跳停了 → 租约到期
- 任何人（通常是启动时的一次扫描）看到 `status='RUNNING' and lease_until < now`，就知道这是僵尸

```java
public int recoverStale() {
    return jdbc.update("""
        update llm_task set status = 'FAILED', error = 'runner lost: 租约过期', updated_at = ?
         where status = 'RUNNING' and lease_until < ?
        """, now, now);
}
```

把它改成 `FAILED` 而不是直接重新跑，是刻意的：**回收和续跑分开**。回收只是"宣布这个任务没人管了"，续跑是"重新抢一次"。两个动作混在一起，容易在集群里出现两个实例同时接手。

### 实测：kill -9 之后

每步 2 秒的配置下，跑完前两步之后强杀进程：

```
kill 前:  extract=SUCCEEDED  classify=SUCCEEDED  summarize=RUNNING  已花 2 次调用
kill -9  ...
重启后:  status=FAILED  error=runner lost: 租约过期
         extract=SUCCEEDED  classify=SUCCEEDED  summarize=RUNNING  persist=PENDING
续跑后:  extract=SUCCEEDED/a1  classify=SUCCEEDED/a1  summarize=SUCCEEDED/a2  persist=SUCCEEDED/a1
新进程 LLM 调用: {"llmCalls":1,"tokens":200}
```

新进程只补了被打断的那一步 —— **1 次调用，不是 3 次**。前两步的 `attempt` 还是 1。

### 这里有个真踩到的坑

第一次测的时候，明明是「extract、classify 都完成之后才杀的」，重启后续跑却把 classify 重跑了一遍。

原因不在状态机，在数据库：**H2 默认允许最多 1 秒的写延迟**，`kill -9` 把最后一条 `SUCCEEDED` 吞了。加上 `WRITE_DELAY=0` 之后行为就稳定了。

这条在生产的等价物是「状态写入必须真的落盘」：

- MySQL 默认 `innodb_flush_log_at_trx_commit=1` —— 每次提交都刷盘，安全
- 为了性能改成 `2` —— 崩溃时可能丢最后 1 秒的事务，你会看到一模一样的现象

**状态机的正确性依赖数据库的持久性保证。** 这一句不是套话，是压测之前必须先确认的事。

## 7.7 一个能跑的示例

`examples/07-state-machine/`，四步任务、文件版 H2（重启状态还在）、不需要 key。

```bash
mvn spring-boot:run
# 想验证强杀续跑，用慢速配置方便中途动手
mvn spring-boot:run -Dspring-boot.run.arguments=--demo.step-delay-ms=2000
```

接口一览：

| 接口 | 干什么 |
| --- | --- |
| `POST /api/tasks?tenant=&input=` | 建任务，只落库不执行 |
| `POST /api/tasks/{id}/run` | 首次执行 |
| `POST /api/tasks/{id}/resume` | 断点续跑 |
| `GET /api/tasks/{id}` | 任务 + 每一步的状态、attempt、产物、耗时 |
| `POST /api/fault?step=` | 演示：让下一步在这失败一次 |
| `POST /api/recover` | 手动回收僵尸任务 |
| `GET /api/stats` | 本轮调了几次 LLM、花了多少 token |

全部实测结果：

| 场景 | LLM 调用 | token | 步骤状态 |
| --- | --- | --- | --- |
| 一次跑完 | 3 | 600 | 全部 `SUCCEEDED/a1` |
| 第 3 步失败 | 2 | 400 | `summarize=FAILED/a1` |
| 续跑 | 3（+1） | 600 | `summarize` 变 `a2`，前两步仍 `a1` |
| 从头重跑（对照） | 5 | 1000 | —— |
| kill -9 后重启续跑 | 新进程 1 | 200 | 已完成的步骤不重跑 |
| 3 个并发续跑 | 3（不重复） | 600 | 只有 1 个 `claimed=true` |

## 7.8 结论

**多大的任务量才值得上状态机？** 一个可以用的判据：

- **单次调用、一次就完** → 不要状态机，`try/catch` + 降级链就够了（第 2、5 章）
- **两三步、总耗时 < 5 秒** → 也不要。失败了重跑一遍的成本可以接受
- **多步、单次任务 > 30 秒、或者一天跑几千次** → 要。这时候重跑的浪费已经能算进成本

三条结论：

1. **状态进数据库，产物也进数据库。** 内存里的 `Map` 不是状态，进程一死就没了；只存状态不存产物，续跑时还是得重算。
2. **续跑的全部逻辑就是"跳过 `SUCCEEDED`"。** 如果发现自己写了一堆 `if (step == 3) skip` 之类的判断，说明步骤边界切错了 —— 状态机应该让代码不需要知道"这是第几步"。
3. **抢占用一条带 `WHERE` 的 `UPDATE`，别上分布式锁。** 数据库行锁已经够用；锁要保护的如果是数据库之外的东西，再考虑 Redis。

到这里，一条请求的路径已经走完了：接入、选型、额度、失败、限流、编排。

下一章换个视角：**这些调用到底花了多少钱、慢在哪里。** 你已经在示例里看到 `llmCalls` 和 `tokens` 两个数字了 —— 下一章把它们变成能对账、能告警的东西。

→ [第 8 章 · 可观测性：token 计量与成本核算](/guide/08-observability)