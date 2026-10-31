package helloai.virtualkey;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 额度引擎：预扣 → 结算 → 过期回收。第 4.4 / 4.5 两节的全部结论都落在这个类里。
 *
 * <p>三件事必须同时成立，少一件账就不平：
 * <ol>
 *   <li><b>放行判断和扣减在同一条 UPDATE 里</b> —— 中间不能有任何窗口</li>
 *   <li><b>结算有幂等闸门</b> —— 只有 PENDING 能变 SETTLED，重放不会重复扣</li>
 *   <li><b>过期回收用单据状态做 CAS</b> —— 两个实例不会各还一次</li>
 * </ol>
 *
 * <p>加锁顺序全局统一为「先 hold、后 bucket」：单据表达意图，额度是大家抢的资源，
 * 先拿意图再动资源，预扣和回收两条路径天然同序。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    static final int PENDING = 0;
    static final int SETTLED = 1;
    static final int EXPIRED = 2;

    private final JdbcTemplate jdbc;
    private final int holdTtlMinutes;

    public QuotaService(JdbcTemplate jdbc, @Value("${quota.hold-ttl-minutes}") int holdTtlMinutes) {
        this.jdbc = jdbc;
        this.holdTtlMinutes = holdTtlMinutes;
    }

    // ---------------------------------------------------------------- 预扣

    /**
     * 预扣一块额度，返回单据 id。
     *
     * <p>同一个 {@code requestId} 重复进来（客户端重放、网关重试）不会重复扣 —— 直接返回第一张单据。
     */
    @Transactional
    public long reserve(long keyId, String requestId, long estimate) {
        long bucketId = bucketId(keyId);

        try {
            jdbc.update(
                "insert into llm_quota_hold (bucket_id, request_id, amount, status, expire_at) values (?, ?, ?, 0, ?)",
                bucketId,
                requestId,
                estimate,
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(holdTtlMinutes))
            );
        } catch (DuplicateKeyException e) {
            // uk_request 挡住了重复预扣。这是幂等，不是错误
            return holdIdOf(requestId);
        }

        // 整章唯一一条真正的并发防线：判断和写入在同一个语句里
        int n = jdbc.update(
            """
            update llm_quota_bucket
               set reserved = reserved + ?
             where id = ?
               and total_tokens - reserved - settled >= ?
            """,
            estimate,
            bucketId,
            estimate
        );

        if (n == 0) {
            // 业务分支，不是异常告警：额度跑满一天撞几百次，包成异常会刷满日志
            throw new QuotaExceededException(keyId);
        }
        return holdIdOf(requestId);
    }

    // ---------------------------------------------------------------- 结算

    /**
     * 按实际用量结算。重复调用是安全的。
     *
     * @param actualTokens 上游返回的 usage.total_tokens
     */
    @Transactional
    public void settle(long holdId, long actualTokens) {
        Hold hold = find(holdId);
        if (hold == null) {
            return;
        }

        int claimed = jdbc.update(
            "update llm_quota_hold set status = 1 where id = ? and status = 0",
            holdId
        );

        if (claimed == 0) {
            Integer status = jdbc.queryForObject(
                "select status from llm_quota_hold where id = ?",
                Integer.class,
                holdId
            );
            if (status != null && status == EXPIRED) {
                // 已经被过期回收：预扣额度早就还回去了，但这次调用真的花了 token。
                // 不记这一笔，账就永远差 —— 对应 4.6 里 CLIENT_ABORTED 那一类。
                jdbc.update(
                    "update llm_quota_bucket set settled = settled + ? where id = ?",
                    actualTokens,
                    hold.bucketId()
                );
            }
            return; // status == SETTLED：正常重放，什么都不做
        }

        // 两个字段同进同出：中间不存在能让别人看见的「额度短暂变多」
        jdbc.update(
            """
            update llm_quota_bucket
               set reserved = reserved - ?, settled = settled + ?
             where id = ?
            """,
            hold.amount(),
            actualTokens,
            hold.bucketId()
        );
    }

    // ---------------------------------------------------------------- 过期回收

    /** 每分钟扫一次。每条单独一个事务，任一失败不影响其他条。 */
    @Scheduled(fixedDelay = 60_000)
    public void expireOnce() {
        List<Long> due = jdbc.queryForList(
            """
            select id from llm_quota_hold
             where status = 0 and expire_at < ?
             order by id limit 200
            """,
            Long.class,
            Timestamp.valueOf(LocalDateTime.now())
        );

        int reclaimed = 0;
        for (Long holdId : due) {
            Hold hold = find(holdId);
            if (hold != null && expireOne(hold)) {
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            log.info("过期回收 {} 条预扣单据", reclaimed);
        }
    }

    /**
     * 归还一条过期单据。返回是否由本线程抢到。
     *
     * <p>{@code and status = 0} 是全部关键：行锁保证多实例只有一个能把 0 改成 2。
     */
    @Transactional
    public boolean expireOne(Hold hold) {
        int claimed = jdbc.update(
            "update llm_quota_hold set status = 2 where id = ? and status = 0",
            hold.id()
        );
        if (claimed == 0) {
            return false; // 别的实例先抢到了，或者这条刚好被结算了
        }
        jdbc.update(
            "update llm_quota_bucket set reserved = reserved - ? where id = ?",
            hold.amount(),
            hold.bucketId()
        );
        return true;
    }

    // ---------------------------------------------------------------- 查询

    public long bucketId(long keyId) {
        List<Long> ids = jdbc.queryForList(
            "select id from llm_quota_bucket where key_id = ? and period = 'day' and period_start = ?",
            Long.class,
            keyId,
            java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC))
        );
        if (ids.isEmpty()) {
            throw new IllegalStateException("配额桶不存在，启动阶段应该建好: keyId=" + keyId);
        }
        return ids.get(0);
    }

    public Bucket bucket(long keyId) {
        return jdbc.queryForObject(
            """
            select total_tokens, reserved, settled from llm_quota_bucket
             where key_id = ? and period = 'day' and period_start = ?
            """,
            (rs, i) -> new Bucket(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            keyId,
            java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC))
        );
    }

    public Hold find(long holdId) {
        List<Hold> rows = jdbc.query(
            "select id, bucket_id, amount from llm_quota_hold where id = ?",
            (rs, i) -> new Hold(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            holdId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Hold> findDue(int limit) {
        return jdbc.query(
            """
            select id, bucket_id, amount from llm_quota_hold
             where status = 0 and expire_at < ?
             order by id limit ?
            """,
            (rs, i) -> new Hold(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            Timestamp.valueOf(LocalDateTime.now()),
            limit
        );
    }

    /** 演示用：把单据的过期时间挪到过去，用来触发回收。生产代码里不会有这个方法。 */
    public void forceExpireAt(long holdId) {
        jdbc.update(
            "update llm_quota_hold set expire_at = ? where id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
            holdId
        );
    }

    private long holdIdOf(String requestId) {
        Long id = jdbc.queryForObject(
            "select id from llm_quota_hold where request_id = ?",
            Long.class,
            requestId
        );
        return id == null ? -1 : id;
    }

    public record Hold(long id, long bucketId, long amount) {}

    public record Bucket(long total, long reserved, long settled) {

        /** 可用 = 总额 - 预占 - 已结 */
        public long available() {
            return total - reserved - settled;
        }
    }
}