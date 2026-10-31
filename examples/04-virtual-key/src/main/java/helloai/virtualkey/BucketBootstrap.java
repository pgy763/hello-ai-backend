package helloai.virtualkey;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 建桶。真实系统里这一步由运营后台「开通租户」触发，示例在启动时自动建一个。
 *
 * <p>注意是**一行一个周期、到点插新行**，不是一列来回重置 —— 重置会把历史抹掉。
 */
@Component
public class BucketBootstrap {

    private static final Logger log = LoggerFactory.getLogger(BucketBootstrap.class);

    /** 示例固定用 keyId=1 */
    public static final long DEMO_KEY_ID = 1L;

    private final JdbcTemplate jdbc;
    private final long dailyTokens;

    public BucketBootstrap(JdbcTemplate jdbc, @Value("${quota.daily-tokens}") long dailyTokens) {
        this.jdbc = jdbc;
        this.dailyTokens = dailyTokens;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        ensureBucket(dailyTokens);
        log.info("配额桶就绪 keyId={} 日额度={}", DEMO_KEY_ID, dailyTokens);
    }

    public void ensureBucket(long total) {
        Date today = Date.valueOf(LocalDate.now(ZoneOffset.UTC));
        Integer exists = jdbc.queryForObject(
            "select count(*) from llm_quota_bucket where key_id = ? and period = 'day' and period_start = ?",
            Integer.class,
            DEMO_KEY_ID,
            today
        );
        if (exists != null && exists > 0) {
            jdbc.update(
                "update llm_quota_bucket set total_tokens = ?, reserved = 0, settled = 0 where key_id = ? and period = 'day' and period_start = ?",
                total,
                DEMO_KEY_ID,
                today
            );
        } else {
            jdbc.update(
                "insert into llm_quota_bucket (key_id, period, period_start, total_tokens, reserved, settled) values (?, 'day', ?, ?, 0, 0)",
                DEMO_KEY_ID,
                today,
                total
            );
        }
    }

    /** 自检前清场：删单据 + 重置桶 */
    public void reset(long total) {
        jdbc.update("delete from llm_quota_hold");
        ensureBucket(total);
    }
}