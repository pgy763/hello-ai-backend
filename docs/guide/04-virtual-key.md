---
title: 第 4 章 · 虚拟密钥：多租户、额度与审计
---

# 第 4 章 · 虚拟密钥：多租户、额度与审计

::: info 本章状态：撰写中
已放出 **4.1–4.5** 五节，剩下的 4.6 审计字段 / 4.7 结论还是提纲。想优先看哪一节？[开个 Issue](https://github.com/pgy763/hello-ai-backend/issues) 说清你的场景，我按热度排优先级。
:::

## 这一章解决什么问题

三条业务线要用同一个模型。你不可能把厂商的真密钥发给三个团队 —— 一旦泄漏，你连是谁泄漏的都不知道。

## 你会得到

- 一套虚拟密钥体系：业务方拿到的 key 只在你的系统里有意义
- 额度控制：按业务线、按天、按 token 数三条线都能卡住
- 一份能直接拿去对账的调用审计日志

## 4.1 真密钥、虚拟密钥、以及为什么必须有中间层

第一反应通常是「给你们各发一把 key」—— 打开厂商控制台建三把，分别塞进三个项目的环境变量。看起来挺妥，甚至更像「最小权限」。

代价要到第一次出事那天才付。

**一、泄漏了，但定位不到。** 厂商发来邮件说检测到 key 在异常地区调用。你手上有三把，却不知道是哪一把 —— 因为三把打的是同一个账号，账单是同一张，调用明细里也没有「哪把 key」这个维度。于是只能三把一起吊销：三个业务线同时停服，三个团队同时改配置、重启、验证。**从发现问题到恢复，中间是一段协调三个团队的时间，不是一次操作。**

**二、额度被一条线吃光。** 多数厂商的配额（RPM/TPM、日额度）是**账号级**的，不是 key 级。三把 key 是「三把钥匙开同一个仓库」，不是三个仓库。一条业务线写出死循环（Agent 场景很常见），十分钟就能把账号 TPM 打满，另外两条线看到的现象是「自己什么都没干，突然开始 429」。

**三、账单对不上。** 月底厂商只给一张总账。老板问三条线各花了多少，你没有数据：日志里有「这次用了 3000 token」，但没有「这次属于谁」。

三件事指向同一个句子：**请求发出去了，但你不知道它是谁的。**

虚拟密钥就是这句话的实现 —— 业务方拿到的 key 只在你的系统里有意义，它调不了厂商；中间层拿它换出真密钥，再转发。所以它的价值排序是：

1. **归属**：每个请求带着「哪条业务线、哪个应用、甚至哪个用户」的标识，一路进日志。
2. **可单独吊销**：某条线泄漏，吊销它那一把，另外两条线零改动，厂商真密钥也不动。
3. **可单独限额**：额度从账号级下沉到 key 级，死循环最多烧完自己那份。
4. **可对账**：打标签的动作发生在你的网关，账单才能按业务线拆。

安全是顺带的。虚拟 key 挡不住内部人拿它去调 —— 它本来就能调。它保证的是**事后你知道是谁**。别把它当 WAF 用。

### 中间层的最小形态

听到「网关」就去评估 Kong / APISIX，项目通常就停在这了。第一版不需要：**一个 Spring 的 Filter，四件事** —— 验虚拟 key、查额度、换真 key 转发、记一条日志。放你已经有的服务里，两百行以内。等它真撑不住（要跨语言、要独立扩缩容、要给外部客户开）再拆，那时接口已经稳定，拆的成本很低。

业务方看到的东西只有这些：

```yaml
llm:
  gateway: http://llm-gateway.internal:8080
  api-key: vk_billing_7f3a9c...      # 虚拟 key，只有网关认得
```

**判断架构有没有做对，一条就够：业务方的代码和配置里永远不该出现厂商真 key。** 出现 `sk-` 开头的字符串，就是漏了。

### 什么情况下不需要这一层

- **一个团队、一条业务线、月账单几百块。** 真 key 放环境变量直接调厂商。多一层网关就多一个故障点、多一次要跨服务排查，收益是零。
- **内部工具，用户就是自己同事。** 没人需要对账，也没有第二个人来泄漏。
- **厂商已经支持 key 级配额和用量明细。** 少数控制台确实能做 key 级限额与分账，那这层的价值只剩「统一协议」—— 那属于第 3 章的 Provider 层，不必再造网关。

粗略的分界线：**出现第二个「需要独立知道花了多少钱」的主体时，就该上虚拟 key 了。** 这个主体可能是业务线、是客户，也可能只是「老板想知道」。只要有人要一份能对上的账，共享真 key 就不够了。

## 4.2 表结构：三张表，加一张你可能会省的

先把结论放这：**密钥表、额度桶、预扣单据、调用日志**，四张。前两张是必需的，第三张是 4.4 混合方案的直接产物，第四张可以先不落库（见本节末尾）。MySQL 8，`utf8mb4`，引擎默认 InnoDB。

### 密钥表：别存明文

```sql
CREATE TABLE llm_virtual_key (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  key_hash    CHAR(64)    NOT NULL COMMENT 'SHA-256(明文 key)，明文不落库',
  key_prefix  VARCHAR(16) NOT NULL COMMENT 'vk_billing_7f3a，日志和后台展示用',
  tenant      VARCHAR(64) NOT NULL COMMENT '业务线标识，一路带进日志',
  status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 0 已吊销',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at  DATETIME    NULL,
  UNIQUE KEY uk_hash (key_hash),
  KEY idx_tenant (tenant)
);
```

两个字段值得解释：

- **`key_hash` 而不是 `key`。** 验 key 走 `uk_hash`，一次等值查询。用 SHA-256 而不是 bcrypt —— 这点和密码不一样：虚拟 key 是你生成的 32 字节随机串，熵已经足够，不需要慢哈希；而且每次请求都要验一次，bcrypt 百毫秒级的成本放在网关上等于自加延迟，还没法建索引。代价是**明文找不回来**，只能在创建时给一次。产品经理迟早会提「后台要能再看到 key」，这个需求要顶回去，不然前面的都白做。
- **`key_prefix`。** 日志里打印 `vk_billing_7f3a...` 这 16 个字符，出事后能一眼对上是谁，同时不足以拿来爆破。没有它，你的日志只能记 `key_id=37`，排查时还得反查一次库。

### 额度桶：`used` 一列是不够的

4.4 选了混合方案，那么「已用」就不是一个数，是三个：

```sql
CREATE TABLE llm_quota_bucket (
  id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  key_id       BIGINT UNSIGNED NOT NULL,
  period       VARCHAR(8)  NOT NULL COMMENT 'day / month',
  period_start DATE        NOT NULL,
  total_tokens BIGINT      NOT NULL COMMENT '配额',
  reserved     BIGINT      NOT NULL DEFAULT 0 COMMENT '预扣未结算',
  settled      BIGINT      NOT NULL DEFAULT 0 COMMENT '已结算用量',
  UNIQUE KEY uk_key_period (key_id, period, period_start)
);
```

**可用额度 = `total_tokens - reserved - settled`。** 放行判断写成 `update ... set reserved = reserved + ? where id = ? and total_tokens - reserved - settled >= ?`，靠这一条语句的原子性挡住大半竞态 —— 剩下那半（结算时的归还顺序）是 4.5 要处理的。

周期为什么是**一行一个周期、到点插新行**，而不是一行来回重置？因为重置就把历史抹了。月底老板问「这条线这个月比上月多花多少」，你要能查；出事故复盘「额度是几点被吃光的」，你也要能查。多存几十行没有成本，丢数据才要命。

### 预扣单据：能扫、能审

```sql
CREATE TABLE llm_quota_hold (
  id         BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  bucket_id  BIGINT UNSIGNED NOT NULL,
  request_id CHAR(32)    NOT NULL COMMENT '链路追踪 ID，和调用日志对得上',
  amount     BIGINT      NOT NULL COMMENT '预扣 token 数',
  status     TINYINT     NOT NULL DEFAULT 0 COMMENT '0 PENDING 1 SETTLED 2 EXPIRED',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expire_at  DATETIME    NOT NULL,
  UNIQUE KEY uk_request (request_id),
  KEY idx_expire (status, expire_at)
);
```

`idx_expire` 的列顺序是 `(status, expire_at)`，不是 `(expire_at)`。定时任务扫的是「PENDING 且已过期」，`status` 放前面能把已经结算完的行整段跳过；反过来只按时间扫，表里 99% 是历史行，索引选择性差到等于全表扫。这种错在小数据量下看不出来，等显形时你已经在翻慢日志了。

`uk_request` 顺带解决了另一件事：**同一条请求重复回调时不会重复结算**，插入冲突直接忽略即可。

### 调用日志：先给它单独的写入路径

日志字段的最小集在 4.6 展开，这里只说结构上的一个决定：**日志表的写入量和额度表差两个数量级**，一条请求一条日志，一次调用一轮额度。别让它们共用同一个库实例一起备份 —— 日志可以只留 30 天、可以归档，额度表不行。起步阶段最省事的做法是**日志先异步写、甚至先写本地文件**，真要按业务线出报表了再入库。

### 什么情况下这几张表都嫌多

- **一个团队、一把 key、一个月度总额。** 密钥可以就是配置里的一行，额度就是一张单行表，`period`、`hold` 全都不需要。先用这批代码上线，比先把表设计完美更值。
- **额度扣减放在 Redis。** 第 6 章的令牌桶就在 Redis 里做。那时候 MySQL 这张桶表只当**账本**，`reserved` 别再在两边各维护一份 —— 双写必然不一致，而且不一致的时候你分不清哪边是对的。
- **调用日志日增不到十万行。** 单表加定期删除就够，分区表带来的运维复杂度远超它省下的那点查询时间。

## 4.3 鉴权链路：一条请求怎么带着身份走到底

表设计好了，密钥发下去了，接下来是那条最容易写歪的管道：**请求进来时验一次，然后让「它是谁的」这个信息活着走到日志里。**

三个决定要先定下来，错了任何一个都要返工：

1. **在哪验** —— Filter 还是 Interceptor
2. **验完的信息放哪** —— `ThreadLocal` 还是显式传参
3. **流式请求**为什么会让前两个决定同时失效

### 在哪验：用 Filter，别用 Interceptor

```java
@Component
@RequiredArgsConstructor
public class VirtualKeyFilter extends OncePerRequestFilter {

    private final VirtualKeyRepository keys;
    private final ObjectMapper json;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws IOException, ServletException {
        String raw = bearer(req.getHeader("Authorization"));
        if (raw == null) { reject(resp, 401, "missing_key"); return; }

        KeyRecord k = keys.findByHash(sha256(raw));       // uk_hash 等值查询
        if (k == null || k.status() != 1) { reject(resp, 401, "invalid_key"); return; }

        req.setAttribute(AccessContext.ATTR,
                new AccessContext(k.id(), k.tenant(), k.keyPrefix()));
        chain.doFilter(req, resp);
    }

    private void reject(HttpServletResponse resp, int code, String reason) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        json.writeValue(resp.getWriter(), Map.of("error", reason));
    }
}
```

`HandlerInterceptor` 看着更「Spring」，但有两个毛病：

**一、异步请求会走两遍。** SSE 和 `DeferredResult` 这类异步请求，容器在结果写完之后会把请求**再 dispatch 一次**，`preHandle` 于是第二次执行。`AsyncHandlerInterceptor.afterConcurrentHandlingStarted` 这个方法存在，就是为了处理这件事。你要是在 `preHandle` 里顺手扣了额度、发了一条审计，一次提问就记了两次账。

**二、管不到 Controller 之外。** 静态资源、`/actuator`、`/error` 都不经过 `preHandle`。其中 `/actuator` 恰恰是必须挡的 —— 里面可能有 env 和 heapdump。

`OncePerRequestFilter` 默认跳过 async dispatch（`shouldNotFilterAsyncDispatch()` 返回 `true`），天然只跑一次；而且它对所有路径生效，放行谁由你显式列白名单。

顺手一个 header 命名约定：**别用下划线**。Nginx 的 `underscores_in_headers` 默认是 `off`，带下划线的 header 会被静默丢掉 —— 本地通、上线 401，且 Nginx 不留任何日志。`X-Tenant-Id` 好，`X_Tenant_Id` 别碰。

### 身份别放 `ThreadLocal`

验完的 `tenant` 要一路到日志、到指标、到账单。最省事的写法是 `ThreadLocal` + 一个静态 `get()`，然后在任何地方随手 `AccessContext.tenant()`。这个写法在你写第二个线程之前都很好用。

它失效的场景恰好是重点场景：SSE 的写回跑在你的线程池里，`@Async` 换线程，Reactor 的调度器更不用说。**失效的后果不是抛异常，是 `tenant` 变成 `null`、日志打出一条没有归属的记录** —— 不出声的 bug，上线一个月后对账才发现有一批账挂不上。

所以 `AccessContext` 当普通参数往下传：

```java
public interface LlmClient {
    String complete(AccessContext ctx, String prompt);
    void stream(AccessContext ctx, String prompt, Consumer<String> onChunk);
}
```

签名难看？这个难看是设计在提醒你：**这次调用要花钱，它本来就该显式带上「谁在花」。** 真觉得每个方法都多一个参数太吵，退一步的方案是在网关入口处把 `ctx` 塞进一个请求作用域的 Bean（`@RequestScope`），但流式回调里仍然要捕获一次 —— 见下一节。

### 流式：在请求线程上取一次，捕获进闭包

```java
AccessContext ctx = AccessContext.current();   // 从当前 request 的 attribute 取，不是 ThreadLocal；此刻仍在请求线程上

SseEmitter emitter = new SseEmitter(300_000L);
executor.submit(() -> {
    try {
        llm.stream(ctx, prompt, chunk -> send(emitter, chunk));   // 变量捕获，不依赖线程
    } catch (Exception e) {
        emitter.completeWithError(e);
    }
});
```

`RequestContextHolder` 在「Controller 返回 `Callable`」这种异步里，Spring 会帮你继承请求上下文（`Callable` 是在容器管理的线程上跑的，Spring 特意为它做了 WebAsyncManager 集成）；`SseEmitter` 跑在你自己 `submit` 的线程池上时不保证。这个「保不保证」还跟版本和具体写法有关，**不要赌，捕获一个局部变量最便宜。**

代价是长的生成任务会一直持有 `ctx` 和 `emitter` 的引用，内存里多留一会儿。它只有几个字段，无所谓；但别顺手把整个 `HttpServletRequest` 也捕获进去 —— 那会把请求体和连接一起钉在堆上，直到生成结束。

### 查库这一步，什么时候该加缓存

链路定下来后，每个请求的成本是：一次 SHA-256（微秒级）+ 一次走唯一索引的等值查询（经验值 1ms 上下）。网关 QPS 上到几百，这条查询就是第一个热点。

用 Caffeine 缓存 `key_hash` → 记录，TTL 30–60 秒。**代价要说清楚：吊销的最长生效延迟等于 TTL。** 所以「吊销立即生效」和「缓存」只能选一个 —— 想都要，就在吊销接口里主动 `invalidate(keyHash)`，别去缩短 TTL（缩短了等于没缓存）。

### 什么情况下这条链路是过度设计

- **单租户、内部服务。** 一个静态 token，`if (!expected.equals(token)) return 401;`，够了。`AccessContext` 这层要等出现第二个「需要独立知道花了多少」的主体才成立 —— 和 4.1 那条分界线是同一根。
- **只要审计、不要限额。** 那 `key` 表还是要有（不然「谁」从哪来），但额度桶和预扣单据可以整块砍掉，`tenant` 直接写进日志表。省两张表，不少功能。
- **接口不对外，只有自己的前端在调。** 别为了「规范」把 key 在浏览器里传来传去。前端带 session，网关在服务端换 key，虚拟 key 只发给机器对机器的调用方。

## 4.4 额度控制：预扣还是后结算

额度表有一个绕不过去的决定：花掉的额度在**什么时候**从池子里扣。这不是实现细节，它决定了并发下会不会超卖，也决定了 4.2 那张表要留几个字段、要不要状态机。

两种做法，先看清楚它们各自错在哪里。

**后结算（记账式）**：请求先放行，调用完成后按响应里的 `usage.total_tokens` 写一条流水、扣减额度。

- 好处：实现只有一个 `UPDATE quota SET used = used + ?`，不需要额外状态；失败的请求没 usage，天然不占额度。
- 代价：**最后那一批请求必然超**。额度剩 1000，同时进来 20 个请求、每个输出 2000 token，20 个全部放行，最后花掉 40000。超额量大致是「并发数 × 单请求用量」，量级不是几个百分点的问题。
- 并发下更糟：`select` 读余额和 `update` 写扣减之间没有任何互斥，20 个线程读到同一个「剩 1000」，全部通过检查。

**预扣（预留式）**：请求进来先按预估用量扣掉，调用结束再按实际用量结算、把差额退回。

- 好处：池子不会透支。超额最多是「预估偏低」的那一段。
- 代价：**预估值一定是错的**，你只能按最坏情况估 —— `prompt_tokens + max_tokens`。一个 `max_tokens=4000` 的请求即使只输出 50 token，中途也占着 4000。十个并发的长请求就能把日额度「虚占」光，实际只花了零头。
- 还有一个必须处理的尾巴：请求超时、进程被 kill、上游 500 —— 预扣没有对应的结算，额度永久泄漏。所以每条预扣记录都要带过期时间。

给个量级感（经验值，不是基准测试）：后结算在 20 并发下超额经常落在 5%–20%；预扣则反过来，短回答为主的场景（意图分类、字段抽取）虚占率能到 90% —— 预扣 4000、实扣 50。两个方案的误差方向和量级都不同，这也是为什么单看「谁更准」选不出来。

实际落地多半是混合：

1. 预扣 `估算输入 token + max_tokens`，写一条 `PENDING` 记录，带 `expire_at`
2. 调用结束：转 `SETTLED`，按实际用量结算，差额归还
3. 定时任务扫 `PENDING` 且已过期的记录，归还额度 —— 余量给 5 分钟以上，长回答真的可能跑三分钟
4. 日/月这类「软额度」仍然只用后结算，省掉整套状态机

选型的判据不是「精确」，是**「花超了会不会变成事故」**：

| 场景 | 建议 | 理由 |
| --- | --- | --- |
| 内部工具、日额度软限制 | 后结算 | 超一点没人疼，不值得上状态机 |
| 免费额度、试用额度 | 预扣 | 被刷是成本事故，不能靠自觉 |
| 对客户计费 | 预扣 + 结算流水 | 扣错要退款，比多写一层逻辑麻烦得多 |
| 单请求可能超过总额度 | 预扣 | `max_tokens` 开到 64k 时，后结算等于允许透支 100% |
| 高并发 + 短请求（分类/抽取） | 后结算 + 低频对账 | 预扣虚占太高，反而把并发压到接近串行 |

这一节只解决了「什么时候扣」，没解决「怎么扣才不打架」：预扣里「读余额」和「写扣减」两步照样不是原子的，`update ... where used < quota` 也只挡住了一半。那是 4.5 的事。

## 4.5 并发下的额度竞态

4.2 里那条带 `where total_tokens - reserved - settled >= ?` 的 UPDATE 已经挡掉了大部分问题。剩下的坑都在缝里：**两条语句之间、两个事务之间、两个实例之间。** 这些错误单看每一行代码都是对的，只有压测或者账单对不上时才会露头。

### 一、`select` 判余额，是三个动作不是一个

```java
// 错法
Long available = jdbc.queryForObject(
    "select total_tokens - reserved - settled from llm_quota_bucket where id = ?", Long.class, id);
if (available < need) return reject(402, "quota_exceeded");
jdbc.update("update llm_quota_bucket set reserved = reserved + ? where id = ?", need, id);
```

两个并发请求各读到 `1000`，各要 `800`，都通过检查，最后 `reserved` 冲出 600。**这不是「偶尔撞上」，并发一上来就是常态。**

更隐蔽的一层：RR 隔离级别下，这条 `select` 是快照读 —— 它看到的是事务开始时的版本。别的事务刚提交的扣减，这里看不见。所以问题不只是「判断和写入有间隔」，而是**你判断所依据的那个值，本来就可能不是最新的**。

改成一条语句：

```java
int n = jdbc.update("""
    update llm_quota_bucket
       set reserved = reserved + ?
     where id = ?
       and total_tokens - reserved - settled >= ?
    """, need, id, need);

if (n == 0) return reject(402, "quota_exceeded");   // 业务分支，不是异常
```

成立的原因有两条：`update` 是**当前读**，`reserved` 一定是已提交的最新值；InnoDB 对同一行的更新加行锁，同一行上的这些语句天然串行。判断和写入落在同一个语句里，中间没有窗口。

`n == 0` 一定要走正常分支。额度跑满的业务线一天会撞上几百次，包成异常的话，异常栈会以每秒一条的速度把日志刷满 —— 真正的事故日志就被淹在里面了。

### 二、CAS 失败别重试

见过这样的写法：`for (int i = 0; i < 3; i++) { if (update(...) == 1) break; }`。

这里失败的含义是**额度不够**，不是并发冲突。重试三次仍然不够，唯一的效果是把「立刻 402」拖成「100ms 后 402」，同时把数据库连接多占三倍时长。高并发下，这会把一个额度问题放大成连接池耗尽。

需要重试的是死锁，那是另一类，得靠异常类型区分（见下一节）。

### 三、结算要在一条语句里

```java
// 错法：两个字段分两次写
jdbc.update("update llm_quota_bucket set reserved = reserved - ? where id = ?", hold, id);
// ...中间还有几行别的逻辑
jdbc.update("update llm_quota_bucket set settled = settled + ? where id = ?", actual, id);
```

两条语句之间，`available = total - reserved - settled` 会**临时变大** —— 本来被别人预占着的额度看起来空出来了，别的请求趁机挤进来。结果就是那种复现不了的超卖：两个事务各自看都对，合起来账不平。

一条语句写完，放同一个事务里：

```java
@Transactional
public void settle(long bucketId, long hold, long actual) {
    int n = jdbc.update("""
        update llm_quota_bucket
           set reserved = reserved - ?, settled = settled + ?
         where id = ?
        """, hold, actual, bucketId);
    if (n == 0) throw new IllegalStateException("bucket missing: " + bucketId);
}
```

两个字段同进同出，中间不存在任何能被别的会话观测到的状态。

### 四、两张表一起动，加锁顺序会咬人

预扣要同时碰 `llm_quota_bucket` 和 `llm_quota_hold`。如果预扣这条路径是「先 bucket 后 hold」，而过期回收那条路径是「先 hold 后 bucket」，两个事务就会各持一把、互等对方 —— MySQL 检测到之后回滚其中一个，抛出来的是这句：

```
Deadlock found when trying to get lock; try restarting transaction
```

（MySQL 错误码 1213，措辞可能因版本而异。Spring 的异常翻译层会把它转成 `CannotAcquireLockException`，老版本里对应的类是 `DeadlockLoserDataAccessException`，具体类名以你用的 Spring 版本为准。）

三件事按顺序做：

1. **固定顺序**，成本为零。所有碰这两张表的事务，一律先 `bucket` 后 `hold`；一次涉及多个 bucket 时按 `id` 升序。这一条能解掉绝大多数死锁。
2. **死锁可以重试一次**。它不是业务失败，捕获那个异常重跑一遍，第二次基本都能过。注意别把 `402` 那条分支也包进重试范围 —— 那是两回事。
3. **别为了躲死锁拆事务**。拆成「不在一个事务里」等于把一个确定的死锁换成一个不确定的超卖。

### 五、过期回收：两个实例会各归还一次

`4.4` 第 3 步那个定时任务，单机跑没问题。部署两台之后，两边同时扫到同一条过期的 `PENDING`，各归还一次 —— 额度凭空多出来一份，而且没有任何报错。

不用上分布式锁，用单据自己的状态做 CAS：

```java
int claimed = jdbc.update(
    "update llm_quota_hold set status = 2 where id = ? and status = 0", holdId);
if (claimed == 0) return;   // 别的实例先抢到了，什么都不做
// 只有 claimed == 1 的那一个实例，才去执行归还
```

`and status = 0` 是全部关键：行锁保证只有一个实例能把它从 0 改成 2。这就是第一节那个 CAS，只是对象从额度换成了单据。

MySQL 8 上还有个更省事的写法，批量取待处理行时用 `select ... for update skip locked` —— 别的实例直接跳过被别人锁住的行，不用一条条去抢。

### 怎么证明写对了

代码审查看不出这类问题，只能压。一个够用的验证：把额度设成「刚好够 25 个请求的量」，起 50 个线程同时打同一把 key，全部结束后查一次：

```sql
select total_tokens, reserved, settled, total_tokens - reserved - settled as available
  from llm_quota_bucket where id = ?;
```

合格的输出只有两种：`available >= 0` 且通过的请求数 ≤ 25；或者 `available < 0`（说明还有洞）。第三种情况最坑 ——**`available` 看着正常，但通过的请求数超了**，那说明有某条路径绕过了 CAS。每次改动这块代码都跑一遍，比读十遍代码有用。

### 什么情况下这一节整段跳过

- **单实例部署，QPS 个位数。** 一条带 `where` 的 UPDATE 就够，结算也可以只用后结算。别在这上面花时间 —— 4.4 的表格已经给了这个判断。
- **额度搬到 Redis。** 第 6 章的令牌桶就在 Redis 里。`INCRBY` 和 Lua 脚本本身是原子的，这一节的 CAS 全部不需要。代价只有一个：**Redis 和 MySQL 只能有一个是准的**，别再双写两边。「MySQL 记流水、Redis 管实时余额」听起来很稳，实际上不一致的那几分钟你根本分不清哪边对。
- **只有一把 key 的业务。** 没有多租户竞争，`update ... where` 一条语句，到此为止。

有个反例值得单独说：**别拿 `synchronized` 或者 `ReentrantLock` 顶这一节。** 单机上它确实能挡住，测起来一切正常；等哪天加到两个实例（或者运维开了双副本），它悄无声息地失效，而且没有任何告警 —— 因为你从来没写过「我以为这把锁是全局的」这句话。这类锁的时间炸弹在扩容那天才炸。

## 后续小节（提纲）

- **4.6 审计日志最小字段集** —— 少了任何一个，月底对账都会卡住
- **4.7 结论** —— 什么规模的团队可以先用一张表凑合

## 前置

[第 3 章 · 统一多模型接入](/guide/03-multi-provider)
