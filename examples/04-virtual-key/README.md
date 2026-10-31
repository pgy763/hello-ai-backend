# 04-virtual-key · 额度引擎：预扣 / 结算 / 过期回收

第 4 章 4.4–4.5 的完整可运行版本。**用 H2 内存库代替 MySQL**，不用装数据库就能看到「并发下不超卖」到底长什么样。

## 演示什么

一个 `QuotaService`，三个方法，每个 `if` 都对应正文里一个坑：

| 方法 | 挡住什么 | 对应正文 |
| --- | --- | --- |
| `reserve` | 并发超卖、请求重放重复扣、额度不够时事务回滚 | 4.5 一、二 |
| `settle` | 重复结算、回收之后才到达的用量 | 4.5 三 |
| `expireOne` | 多实例各归还一次 | 4.5 五 |

三件事必须同时成立，少一件账就不平：

1. **放行判断和扣减在同一条 UPDATE 里** —— 中间不能有任何窗口
2. **结算有幂等闸门** —— 只有 `PENDING` 能变 `SETTLED`
3. **过期回收用单据状态做 CAS** —— 两个实例不会各还一次

## 怎么跑

```bash
cd examples/04-virtual-key
mvn spring-boot:run
```

不需要 MySQL、不需要 Redis、不需要 key。启动时自动建表（`schema.sql`）并建一个日额度 2500 的配额桶。

## 预期看到什么

**1. 并发自检 —— 这个模块存在的理由**

```bash
curl -s 'localhost:8080/api/selftest?parallel=50&estimate=100'
```

额度 2500、每个请求预扣 100（容量 25），50 个线程同时打：

```json
{"parallel":50,"estimatePerRequest":100,"capacity":25,
 "succeeded":25,"rejected":25,"failed":0,
 "reserved":2500,"available":0,"noOversell":true}
```

**25 个通过、25 个被拒、可用额度正好 0、零超卖。**

如果把 `reserve` 里那条语句拆成「先 select 判余额、再 update 扣减」，同样的测试会给出 `succeeded > 25` —— 这就是 4.5 第一节讲的那个错法。

**2. 幂等：同一个 requestId 打两次**

```bash
curl -sX POST 'localhost:8080/api/reserve?requestId=req-A&estimate=300'
curl -sX POST 'localhost:8080/api/reserve?requestId=req-A&estimate=300'
curl -s localhost:8080/api/bucket
```

两次返回同一个 `holdId`，桶里 `reserved` 只加了一次 300。

**3. 结算：按实际用量退差额**

```bash
curl -sX POST 'localhost:8080/api/settle?holdId=1&actual=120'
```

`reserved` 从 300 归零，`settled` 变成 120 —— 剩下的 180 回到可用额度。**再调一次同一个接口，数字不变**（幂等闸门挡住了）。

**4. 过期回收 + 迟到用量的补偿**

```bash
# 造一张已经过期的单据（模拟请求超时、进程被 kill）
curl -sX POST 'localhost:8080/api/reserve-expired?requestId=req-B&estimate=400'
curl -s localhost:8080/api/bucket          # reserved 多了 400

# 回收
curl -sX POST 'localhost:8080/api/expire'  # {"due":1,"reclaimed":1,...}
curl -s localhost:8080/api/bucket          # reserved 还回去了

# 回收之后用量才报上来（上游其实收过费）—— 仍然要记账
curl -sX POST 'localhost:8080/api/settle?holdId=3&actual=90'
curl -s localhost:8080/api/bucket          # settled 增加了 90
```

最后一步是很多人漏掉的：**额度还回去了，但 token 真的花了。** 不补这一笔，账永远差。

## 加锁顺序

全局统一「**先 `llm_quota_hold`、后 `llm_quota_bucket`**」：

- 预扣：insert 单据 → update 额度
- 结算：CAS 改单据状态 → update 额度
- 回收：CAS 改单据状态 → update 额度

三条路径同序，所以不会互等。正文 4.5 第四节解释了这个方向为什么比反过来更自然。

## 和正文 DDL 的差别

`schema.sql` 去掉了 `BIGINT UNSIGNED` 和 `COMMENT` —— H2 不认，MySQL 认。列名、索引、唯一键完全一致，换 MySQL 时把这两处补回去即可。

连接串里 `MODE=MySQL` 是让 H2 接受 MySQL 那套语法。

## 已经验证过的

| 项 | 结果 |
| --- | --- |
| `mvn package` | 通过（JDK 21 编译，target 17） |
| 并发自检 | 50 线程 / 容量 25 → `succeeded=25, rejected=25, available=0, noOversell=true` |
| 幂等预扣 | 同一 requestId 两次 → 同一 holdId，`reserved` 只加一次 |
| 结算 | `reserved` 归零，`settled` 按实际用量累加 |
| 重复结算 | 桶状态不变 |
| 过期回收 | `reclaimed=1`，`reserved` 归还 |
| 回收后结算 | `settled` 仍然累加（补偿路径） |

## 这个示例没做的

- **4.3 的鉴权 Filter**：需要 key 表和 `AccessContext`，属于另一条链路，正文有完整代码
- **4.6 的调用日志入库**：示例只打日志，不落库
- **真 MySQL / Redis**：并发结论在 H2 上成立（依赖行锁 + 当前读），换 MySQL 的注意事项见正文 4.5