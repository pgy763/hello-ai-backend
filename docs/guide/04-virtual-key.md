---
title: 第 4 章 · 虚拟密钥：多租户、额度与审计
---

# 第 4 章 · 虚拟密钥：多租户、额度与审计

## 这一章解决什么问题

三条业务线要用同一个模型。你不可能把厂商的真密钥发给三个团队 —— 一旦泄漏，你连是谁泄漏的都不知道。

更要命的不是安全，是**你花的钱说不清**：请求发出去了，但不知道它是谁的。

## 主线：一次调用要走完四步

这一章所有内容都挂在这条链上，先记住它，后面每一节都在补其中一环：

```
请求进来 ──① 验虚拟 key──→ ② 预扣额度 ──→ 调用模型 ──→ ③ 按实际用量结算
   │                                                          │
   └──────────────── ④ 落一条调用日志 ←──────────────────────┘
```

| 节 | 补的是哪一环 | 一句话结论 |
| --- | --- | --- |
| 4.1 | 为什么要这层 | 价值排序是「归属 > 可吊销 > 可限额 > 可对账」，安全是顺带的 |
| 4.2 | 落地形态 | 三张表 + 一张可以省的表，`used` 一列不够 |
| 4.3 | ① 验 key | 用 Filter 不用 Interceptor；身份显式传参，别放 ThreadLocal |
| 4.4 | ② 预扣策略 | 判据不是「准不准」，是「花超了会不会变成事故」 |
| 4.5 | ②③ 的并发 | 判断和写入必须在同一条语句里；预扣要能过期回收 |
| 4.6 | ④ 日志 | 一条日志对应**一次上游调用**，不是一次用户请求 |

## 你会得到

- 一套虚拟密钥体系：业务方拿到的 key 只在你的系统里有意义
- 额度控制：预扣 + 结算 + 过期回收的**完整实现**（不是三个碎片）
- 一份能直接拿去对账的调用审计日志
- 一个判断标准：什么时候这套东西是过度设计

## 前置

[第 3 章 · 统一多模型接入](/guide/03-multi-provider)

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
// Filter 里塞进 request attribute 的那个对象，在请求线程上取出来
AccessContext ctx = AccessContext.from(request);   // request.getAttribute(ATTR)

SseEmitter emitter = new SseEmitter(300_000L);
executor.submit(() -> {
    try {
        llm.stream(ctx, prompt, chunk -> send(emitter, chunk));   // 变量捕获，不依赖线程
    } catch (Exception e) {
        emitter.completeWithError(e);
    }
});
```

`ctx` 本身可以从 `RequestContextHolder` 拿（`AccessContext.from(((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest())`），**但必须在请求线程上调用**。一旦进了你 `submit` 的线程池，`RequestContextHolder` 还灵不灵就取决于「Spring 有没有帮你继承上下文」：Controller 返回 `Callable` 时容器会做 `WebAsyncManager` 集成，跑在你自己线程池上的 `Runnable` 则不保证，而且这个行为随版本变。

**所以不要赌：在请求线程上取一次，捕获进闭包。** 代价只是多一个局部变量。

代价是长的生成任务会一直持有 `ctx` 和 `emitter` 的引用，内存里多留一会儿。它只有几个字段，无所谓；但别顺手把整个 `HttpServletRequest` 也捕获进去 —— 那会把请求体和连接一起钉在堆上，直到生成结束。

### 查库这一步，什么时候该加缓存

链路定下来后，每个请求的成本是：一次 SHA-256（微秒级）+ 一次走唯一索引的等值查询（经验值 1ms 上下）。网关 QPS 上到几百，这条查询就是第一个热点。

用 Caffeine 缓存 `key_hash` → 记录，TTL 30–60 秒。**代价要说清楚：吊销的最长生效延迟等于 TTL。** 所以「吊销立即生效」和「缓存」只能选一个 —— 想都要，就在吊销接口里主动 `invalidate(keyHash)`，别去缩短 TTL（缩短了等于没缓存）。

**多实例部署时这条要说两遍**：`invalidate` 只清得掉当前这台。三台机器各自缓存 60 秒，你在 A 上调了吊销，B 和 C 最多还能用一分钟。三种处理，按成本排：

1. 接受它，把 TTL 压到 30 秒 —— 吊销最迟 30 秒生效，多数内部系统够了
2. 加一个「吊销广播」（Redis pub/sub 或直接给一个 `key_version`）让各实例跟着清
3. 高危 key 的吊销走远期方案：先改状态、再广播、最后等 TTL 自然过期

**别选第四种「只清本地还声称立即生效」** —— 那是最容易写出来、也最容易在对账时被打脸的一种。

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
3. 定时任务扫 `PENDING` 且已过期的记录，归还额度 —— **`expire_at` 至少给到 SSE 超时的两倍**（第 2 章那个 300 秒的超时，对应 10 分钟），给短了会在长回答还在生成时就回收额度
4. 日/月这类「软额度」仍然只用后结算，省掉整套状态机

选型的判据不是「精确」，是**「花超了会不会变成事故」**：

| 场景 | 建议 | 理由 |
| --- | --- | --- |
| 内部工具、日额度软限制 | 后结算 | 超一点没人疼，不值得上状态机 |
| 免费额度、试用额度 | 预扣 | 被刷是成本事故，不能靠自觉 |
| 对客户计费 | 预扣 + 结算流水 | 扣错要退款，比多写一层逻辑麻烦得多 |
| 单请求可能超过总额度 | 预扣 | `max_tokens` 开到 64k 时，后结算等于允许透支 100% |
| 高并发 + 短请求（分类/抽取） | 后结算 + 低频对账 | 预扣虚占太高，反而把并发压到接近串行 |

这一节只解决了「什么时候扣」，没解决「怎么扣才不打架」。4.2 那条单语句 UPDATE 挡得住「放行」这一个动作，但预扣落单据、结算归还、过期回收分散在多条语句、多个事务、多个实例里，串起来照样会打架 —— 那是 4.5 的事。

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

1. **固定顺序，而且顺序是「先单据、后额度」。** 所有碰这两张表的事务，一律先 `llm_quota_hold` 再 `llm_quota_bucket`；一次涉及多个 bucket 时按 `id` 升序。

   为什么是这个方向而不是反过来：**单据表达的是意图（「这次调用要花多少」），额度是大家抢的那个资源。** 顺着业务写下来天然就是「先有单据，才知道要动多少额度」；硬扭成「先 bucket 后 hold」你得多查一次库才能拿到 bucketId。而且插入一行比更新一行锁的范围小得多，冲突更少。**两天后回来改代码的人也不会写反，因为顺序是顺着业务走的。**

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

### 六、把三步写成代码：一个能直接抄的 `QuotaService`

前面五节各讲了一个坑，但**碎片拼不出能跑的东西**。这一节把它们合成一个类 —— 预扣、结算、回收三个方法，每个坑都在对应位置标了注释。

#### 预扣：幂等 + 一条语句定生死

```java
@Transactional
public long reserve(long keyId, String requestId, long estimate) {
    long bucketId = bucketId(keyId);

    try {
        jdbc.update("""
            insert into llm_quota_hold (bucket_id, request_id, amount, status, expire_at)
            values (?, ?, ?, 0, ?)
            """, bucketId, requestId, estimate,
            Timestamp.valueOf(LocalDateTime.now().plusMinutes(holdTtlMinutes)));
    } catch (DuplicateKeyException e) {
        // uk_request 挡住了重复预扣：客户端重放、网关重试都走这里。这是幂等，不是错误
        return holdIdOf(requestId);
    }

    // 整章唯一一条真正的并发防线：判断和写入在同一个语句里
    int n = jdbc.update("""
        update llm_quota_bucket
           set reserved = reserved + ?
         where id = ?
           and total_tokens - reserved - settled >= ?
        """, estimate, bucketId, estimate);

    if (n == 0) {
        throw new QuotaExceededException(keyId);   // 事务回滚，上面那张单据一起没了
    }
    return holdIdOf(requestId);
}
```

三个细节都对上了前面的结论：

- **`estimate` 是按最坏情况估的**（4.4）：`prompt_tokens + max_tokens`
- **`uk_request` 是幂等闸门**：同一个 `requestId` 第二次进来直接返回第一张单据，不会扣两次
- **`n == 0` 抛业务异常**：整笔事务回滚，单据和额度一起撤销，不会留下孤儿单据

#### 结算：先过闸门，再一次写完

```java
@Transactional
public void settle(long holdId, long actualTokens) {
    Hold hold = find(holdId);
    if (hold == null) return;

    // 幂等闸门：只有 PENDING(0) 能变 SETTLED(1)
    int claimed = jdbc.update(
        "update llm_quota_hold set status = 1 where id = ? and status = 0", holdId);

    if (claimed == 0) {
        Integer status = jdbc.queryForObject(
            "select status from llm_quota_hold where id = ?", Integer.class, holdId);
        if (status != null && status == 2) {
            // 已经被过期回收：预扣额度早还回去了，但这次调用真的花了 token。
            // 不记这一笔，账就永远差 —— 对应 4.6 里 CLIENT_ABORTED 那一类
            jdbc.update("update llm_quota_bucket set settled = settled + ? where id = ?",
                actualTokens, hold.bucketId());
        }
        return;   // status == 1：正常重放，什么都不做
    }

    // 两个字段同进同出，中间不存在能让别人看见的「额度短暂变多」
    jdbc.update("""
        update llm_quota_bucket
           set reserved = reserved - ?, settled = settled + ?
         where id = ?
        """, hold.amount(), actualTokens, hold.bucketId());
}
```

`claimed == 0` 那一支里藏着本章最容易漏的一笔账：**请求超时被回收了，但上游其实已经把 token 烧掉了。** 只做「已结算就返回」的写法，会让这部分用量从账上消失。

#### 过期回收：靠单据状态做 CAS

```java
@Scheduled(fixedDelay = 60_000)
public void expireOnce() {
    List<Long> due = jdbc.queryForList("""
        select id from llm_quota_hold
         where status = 0 and expire_at < ?
         order by id limit 200
        """, Long.class, Timestamp.valueOf(LocalDateTime.now()));

    for (Long holdId : due) {
        Hold hold = find(holdId);
        if (hold != null) expireOne(hold);   // 每条一个独立事务，任一条失败不影响其他
    }
}

@Transactional
public boolean expireOne(Hold hold) {
    // and status = 0 是全部关键：行锁保证多实例里只有一个能把 0 改成 2
    int claimed = jdbc.update(
        "update llm_quota_hold set status = 2 where id = ? and status = 0", hold.id());
    if (claimed == 0) return false;          // 别的实例抢到了，或者这条刚好结算了
    jdbc.update("update llm_quota_bucket set reserved = reserved - ? where id = ?",
        hold.amount(), hold.bucketId());
    return true;
}
```

注意这里**没有用分布式锁，也没有 `select ... for update` 锁一整批**：CAS 本身就是闸门，锁一批反而会把事务拖长。

#### 过期时间给多久

这是唯一一个拍脑袋会出错的地方：**`expire_at` 必须大于「一次调用可能持续的最长时间」。**

第 2 章的 SSE 超时给的是 300 秒。如果 `expire_at` 只留 5 分钟，那一个正好跑到 4 分 59 秒的长回答，会在还在生成时就被回收掉额度 —— 结算时 `claimed == 0`，走到上面那个补偿分支。虽然不丢账，但你会白写一条告警。

**给两倍：SSE 超时 300 秒 → `expire_at` 设 10 分钟。** 宁可晚回收（额度虚占一会儿），不要早回收（账变复杂）。

### 怎么证明写对了

代码审查看不出这类问题，只能压。示例模块把这件事做成了一个接口：

```bash
curl -s 'localhost:8080/api/selftest?parallel=50&estimate=100'
```

额度 2500、每个请求预扣 100（容量 25），50 个线程同时打：

```json
{"parallel":50,"estimatePerRequest":100,"capacity":25,
 "succeeded":25,"rejected":25,"failed":0,
 "reserved":2500,"available":0,"noOversell":true}
```

**25 个通过、25 个被拒、可用额度正好 0。** 这才是「写对了」的样子。

再看合格 / 不合格的判据：

```sql
select total_tokens, reserved, settled, total_tokens - reserved - settled as available
  from llm_quota_bucket where id = ?;
```

| 结果 | 说明 |
| --- | --- |
| `available >= 0` 且通过数 ≤ 容量 | ✅ 正常 |
| `available < 0` | ❌ 有路径绕过了那条带 `where` 的 UPDATE |
| `available` 正常但通过数超了 | ❌ 最坑的一种：有路径根本没走 CAS |

第三种最难发现，因为它看起来「账是平的」。**每次改动这块代码都跑一遍，比读十遍代码有用。**

### 什么情况下这一节整段跳过

- **单实例部署，QPS 个位数。** 一条带 `where` 的 UPDATE 就够，结算也可以只用后结算。别在这上面花时间 —— 4.4 的表格已经给了这个判断。
- **额度搬到 Redis。** 第 6 章的令牌桶就在 Redis 里。`INCRBY` 和 Lua 脚本本身是原子的，这一节的 CAS 全部不需要。代价只有一个：**Redis 和 MySQL 只能有一个是准的**，别再双写两边。「MySQL 记流水、Redis 管实时余额」听起来很稳，实际上不一致的那几分钟你根本分不清哪边对。
- **只有一把 key 的业务。** 没有多租户竞争，`update ... where` 一条语句，到此为止。

有个反例值得单独说：**别拿 `synchronized` 或者 `ReentrantLock` 顶这一节。** 单机上它确实能挡住，测起来一切正常；等哪天加到两个实例（或者运维开了双副本），它悄无声息地失效，而且没有任何告警 —— 因为你从来没写过「我以为这把锁是全局的」这句话。这类锁的时间炸弹在扩容那天才炸。

## 4.6 审计日志最小字段集

4.2 说日志要给单独的写入路径，这一节说往里写什么。

先把判据定清楚，不然会一路加字段加到表宽两屏、真出事时还是查不出来：**日志的用处只有两个 —— 出事后把一次调用还原出来，月底把账对上。** 符合这两件事的字段必须有，其余都算「留着占地方」。

### 一条日志对应一次上游调用，不是一次用户请求

这是最容易写错的一处，而且错了不会报错，只会在对账那天暴露。

用户点一次「生成」，中间可能 429 重试了一次、超时降级到了另一家厂商。**厂商那边记了三笔账，你的日志只落了一行。** 月底差出来的那几个点，通常就是这条被吞掉的调用。

所以主键是「上游调用」，不是「用户请求」。两者靠一个 `parent_request_id` 关联：

```sql
CREATE TABLE llm_call_log (
  id                BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  request_id        CHAR(32)        NOT NULL COMMENT '一次上游调用',
  parent_request_id CHAR(32)        NULL COMMENT '一次用户请求，重试/降级时多条日志共用',
  key_id            BIGINT UNSIGNED NOT NULL,
  key_prefix        VARCHAR(16)     NOT NULL,
  tenant            VARCHAR(64)     NOT NULL,
  provider          VARCHAR(32)     NOT NULL,
  model_requested   VARCHAR(64)     NOT NULL,
  model_returned    VARCHAR(64)     NULL,
  is_stream         TINYINT(1)      NOT NULL DEFAULT 0,
  retry_no          TINYINT         NOT NULL DEFAULT 0,
  prompt_tokens     INT             NULL,
  cached_tokens     INT             NULL,
  completion_tokens INT             NULL,
  finish_reason     VARCHAR(24)     NULL,
  status            VARCHAR(16)     NOT NULL COMMENT 'OK/REJECTED/UPSTREAM_ERROR/CLIENT_ABORTED',
  http_status       SMALLINT        NULL,
  error_code        VARCHAR(64)     NULL,
  queue_ms          INT             NULL COMMENT '网关内排队',
  ttfb_ms           INT             NULL COMMENT '到首字节，流式才有意义',
  total_ms          INT             NULL,
  started_at        DATETIME(3)     NOT NULL COMMENT '存 UTC，不存本地时间',
  UNIQUE KEY uk_request (request_id),
  KEY idx_tenant_time (tenant, started_at),
  KEY idx_parent (parent_request_id)
);
```

### 少了哪一列，会卡住哪种账

字段本身没什么好讲的，讲它们各自挡住什么才有用：

| 字段 | 少了它，卡在哪 |
| --- | --- |
| `parent_request_id` + `retry_no` | 日志条数比厂商账单少，差多少说不清：你不知道是漏记还是重试没记 |
| `model_returned` | 传的是别名（如 `xxx-latest`），厂商解析成了别的快照。调价或换默认版本后，你算的钱和账单对不上，且查不出为什么 |
| `cached_tokens` 单列 | 只记 `prompt_tokens` 会把缓存命中的输入按全价算，算出来永远比账单贵一截 |
| `queue_ms` / `ttfb_ms` | P95 变高时看不出是上游慢了还是你自己的线程池排队 —— 只有 `total_ms` 时这两个原因长得一模一样 |
| `status` + `error_code` | 失败的行没落，QPS 和成功率全被算高；`402` 和上游 `429` 混成一种，分不清是额度问题还是厂商问题 |
| `finish_reason` | `length` 意味着输出被截断、业务方大概率会重问一次，这次的 token 是纯损耗，复盘时找不到 |
| `key_prefix` | 只有 `key_id`，翻日志还得回表 join；出事时最缺的就是时间 |

三个容易记错的地方：

**一、失败也要落行，`status` 至少分四类。** 尤其是 `CLIENT_ABORTED`：用户关掉页面、SSE 连接断了，你的代码 catch 到异常就返回了，但**上游已经把 token 生成完、照常计费**。这一类不单独统计，账永远差。

**二、记 token，不记钱。** 厂商调价后，历史日志里的金额就成了错的。金额在查询时按当期价目表算。真要存，就多存一个 `price_version` 字段指向当时用的价目表 —— 只存金额不存版本，等于把一笔查不清的历史账钉在库里。

**三、时间存 UTC。** 服务器时区、容器时区、数据库时区三者随时会不一致，`datetime` 带本地时间的那种「差 8 小时」排查过的人都懂。

### 别存 prompt 正文

想存正文的冲动很强 —— 出问题时能直接看到用户问了什么。三条代价，按顺序考虑：

1. **合规。** 正文里什么都有：手机号、身份证、内部文档。存进来就得考虑加密、访问审计、导出限制。
2. **体积。** 一条请求几 KB，日十万请求就是几百 MB，而且索引会跟着膨胀。
3. **删不掉。** 用户要求删除某条数据时，你得在一张几十亿行的表里找出来。

正确的做法是留一个外链：日志里存 `trace_id`，正文放支持保留期和访问控制的地方（Langfuse / Loki / 对象存储都行）。日志表只负责「这次调用是谁的、花了多少、成没成」。

### 什么情况下这些字段都嫌多

- **内部工具、不对外计费。** 五个字段够：`request_id`、`tenant`、`prompt_tokens`、`completion_tokens`、`status`。老板要的是「这周花了多少」，不是「每次调用是谁的」。
- **已经在用 trace 平台。** 别再自建一套字段名。把 `request_id` 当外部 ID 回填进去，明细留在平台 —— 两边各维护一套最典型的后果是字段名不一致，对账时用 `requestId` 去 join `trace_id`，然后发现一半对不上。
- **单团队、单 key。** 直接看厂商控制台的用量页。那张表比你自己做的准，还不用维护。

## 4.7 结论：从一张表开始，别从架构图开始

这一章给了三张表、一个 Filter、一个额度引擎、一份日志字段表。**如果你照着全建，多半会后悔** —— 因为你在还没有第二个租户的时候，就付出了永久的运维成本。

所以最后说清楚落地顺序。

### 第一周：一张表 + 一个字段

线上真实需要的，往往只是「知道是谁花的」：

```sql
CREATE TABLE llm_call_log (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  request_id  CHAR(32)    NOT NULL,
  tenant      VARCHAR(64) NOT NULL,
  prompt_tokens     INT NULL,
  completion_tokens INT NULL,
  status      VARCHAR(16) NOT NULL,
  started_at  DATETIME(3) NOT NULL,
  UNIQUE KEY uk_request (request_id)
);
```

配合一把共享 key、一个从 header 取的 `tenant`，两百行以内能跑完。**别小看这一步：它已经解决了「账单按业务线拆」这个最原始的需求**，而这一步不做的项目，三个月后连「谁在烧钱」都答不上来。

### 等到出现第二个主体：再上虚拟 key

判断线在 4.1 那条：**有人要一份能对上的账，而且不止一个主体。**

这时才需要 `llm_virtual_key` 表、`key_hash` 验证、`AccessContext` 一路传。这一层的价值在「出事时能只吊销一条线」和「死循环只能烧自己那份」，安全只是顺带。

### 等到「花超了会变成事故」：才需要预扣

这是 4.4 那张表的判据。免费额度、对客户计费、`max_tokens` 开到很大的场景 —— 预扣。

**其余情况用后结算 + 低频对账。** 预扣的虚占率在短请求场景能到 90%（预扣 4000、实扣 50），十个并发的长请求就能把日额度虚占光，把并发压到接近串行。很多人是被「预扣更准」这种直觉带进去的，实际是花一倍复杂度换了一个更差的吞吐。

### 什么时候这些都不要

三条线，任何一条成立就别做这一章的东西：

- **一个团队、一条业务线、月账单几百块。** 真 key 放环境变量，厂商控制台的用量页就是你的账单。
- **内部工具，用户就是同事。** 没人需要对账，也没有第二个人来泄漏。
- **厂商已经支持 key 级配额和用量明细。** 那一节的价值只剩「统一协议」，属于第 3 章的 Provider 层。

### 这一章真正想说的三句话

如果过几个月你只记得三件事，记这三件：

1. **「请求发出去了，但不知道它是谁的」是所有成本问题的源头。** 虚拟 key 只是这句话的一个实现，你要的是那个标识能一路走到日志里。
2. **并发问题的解法不是加锁，是「让判断和写入变成同一个原子动作」。** 一条带 `where` 的 UPDATE、一次 CAS，比 `synchronized`、比分布式锁都便宜，而且它不会在你扩容那天突然失效。
3. **账不平的地方永远在缝里**：两条语句之间、两个事务之间、两个实例之间、以及「回收了但上游其实收过费」这种时间差里。上面那个 `QuotaService` 三个方法，每个 `if` 都在填一条缝。

**下一章**换个方向：如果主力模型这会儿正好挂了 —— 不是慢，是彻底不通 —— 你的降级链该在多长时间内切过去，切过去之后又会撞上什么。

→ [第 5 章 · 降级链：主备模型自动切换](/guide/05-fallback)
