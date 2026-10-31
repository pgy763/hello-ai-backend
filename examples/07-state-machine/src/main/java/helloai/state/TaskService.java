package helloai.state;

import helloai.state.StateMachineApp.TaskContext;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 状态机的全部实现。
 *
 * <p>三条不变量，破了任何一条都会出现「钱白花了」或「任务跑两遍」：
 * <ol>
 *   <li>推进任务前先用一条 {@code UPDATE ... WHERE status = ?} 抢到它（CAS）</li>
 *   <li>已 SUCCEEDED 的步骤永远跳过，且它的产物从库里读，不重算</li>
 *   <li>跑着的任务要有租约，进程死了租约过期，别的进程才能接手</li>
 * </ol>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final JdbcTemplate jdbc;
    private final List<Step> steps;
    private final long leaseSeconds;

    /** 本进程正在跑的任务：心跳只给自己名下的任务续租。 */
    private final Map<String, Boolean> owned = new ConcurrentHashMap<>();

    /** 演示用：下一次执行到这一步时让它失败一次。 */
    private volatile String failOnceAtStep;

    public TaskService(JdbcTemplate jdbc, List<Step> steps, @Value("${demo.lease-seconds:6}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.steps = List.copyOf(steps);
        this.leaseSeconds = leaseSeconds;
    }

    // ---------------------------------------------------------------- 创建

    public String create(String tenant, String input) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        jdbc.update("""
            insert into llm_task (id, tenant, kind, input, status, created_at, updated_at)
            values (?, ?, 'ticket-analysis', ?, 'PENDING', ?, ?)
            """, id, tenant, input, now, now);

        int seq = 0;
        for (Step step : steps) {
            jdbc.update("""
                insert into llm_task_step (task_id, step_name, seq, status, attempt)
                values (?, ?, ?, 'PENDING', 0)
                """, id, step.name(), ++seq);
        }
        log.info("任务创建 task={} 共 {} 步", id, steps.size());
        return id;
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 从头或从断点跑一遍。同步执行 —— 示例里这样最好观察，真实系统放进线程池或 MQ。
     *
     * @return 最终状态
     */
    public RunResult run(String taskId, boolean resume) {
        if (!claim(taskId, resume)) {
            TaskView current = describe(taskId);
            return new RunResult(current.task().status(), current.task().currentStep(), false,
                "任务当前状态为 " + current.task().status() + "，不能在这里启动");
        }

        owned.put(taskId, Boolean.TRUE);
        try {
            // 输入从库里读：进程重启过也还在，这是「重启后续跑」成立的前提
            String input = jdbc.queryForObject("select input from llm_task where id = ?", String.class, taskId);
            if (input == null) {
                input = "(无输入)";
            }

            for (Step step : steps) {
                StepRow row = loadStep(taskId, step.name());
                if ("SUCCEEDED".equals(row.status())) {
                    continue;   // ← 断点续跑的全部秘密就是这一行
                }

                executeStep(taskId, step, new TaskContext(taskId, input, outputs(taskId)));
                renewLease(taskId);
            }

            finish(taskId, "SUCCEEDED", null);
            return new RunResult("SUCCEEDED", null, true, "全部步骤完成");
        } catch (StepFailedException e) {
            finish(taskId, "FAILED", e.getMessage());
            log.warn("任务失败 task={} step={} : {}", taskId, e.stepName(), e.getMessage());
            return new RunResult("FAILED", e.stepName(), true, e.getMessage());
        } catch (RuntimeException e) {
            finish(taskId, "FAILED", e.toString());
            return new RunResult("FAILED", null, true, e.toString());
        } finally {
            owned.remove(taskId);
        }
    }

    private void executeStep(String taskId, Step step, TaskContext ctx) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("""
            update llm_task_step
               set status = 'RUNNING', attempt = attempt + 1, started_at = ?
             where task_id = ? and step_name = ? and status <> 'SUCCEEDED'
            """, now, taskId, step.name());

        long t0 = System.nanoTime();
        try {
            if (step.name().equals(failOnceAtStep)) {
                failOnceAtStep = null;
                throw new IllegalStateException("演示：这一步被注入了故障");
            }
            String output = step.run(ctx);
            jdbc.update("""
                update llm_task_step
                   set status = 'SUCCEEDED', output = ?, finished_at = ?
                 where task_id = ? and step_name = ?
                """, output, Timestamp.valueOf(LocalDateTime.now()), taskId, step.name());
            jdbc.update("update llm_task set current_step = ?, updated_at = ? where id = ?",
                step.name(), Timestamp.valueOf(LocalDateTime.now()), taskId);

            log.info("步骤完成 task={} step={} 耗时={}ms", taskId, step.name(),
                (System.nanoTime() - t0) / 1_000_000L);
        } catch (RuntimeException e) {
            jdbc.update("""
                update llm_task_step
                   set status = 'FAILED', error = ?, finished_at = ?
                 where task_id = ? and step_name = ?
                """, String.valueOf(e.getMessage()), Timestamp.valueOf(LocalDateTime.now()), taskId, step.name());
            throw new StepFailedException(step.name(), e.getMessage());
        }
    }

    /**
     * 抢占任务。这是防「用户连点两次」和「两个实例同时续跑」的唯一防线。
     *
     * @param resume true 允许从 FAILED 重新开始（续跑），false 只允许从 PENDING 开始
     */
    private boolean claim(String taskId, boolean resume) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Timestamp lease = Timestamp.valueOf(LocalDateTime.now().plusSeconds(leaseSeconds));

        String sql = resume
            ? "update llm_task set status = 'RUNNING', lease_until = ?, updated_at = ? where id = ? and status in ('PENDING','FAILED')"
            : "update llm_task set status = 'RUNNING', lease_until = ?, updated_at = ? where id = ? and status = 'PENDING'";

        boolean claimed = jdbc.update(sql, lease, now, taskId) == 1;
        if (!claimed) {
            log.warn("抢占失败 task={} resume={}（可能已经在跑）", taskId, resume);
        }
        return claimed;
    }

    private void finish(String taskId, String status, String error) {
        jdbc.update("update llm_task set status = ?, error = ?, lease_until = null, updated_at = ? where id = ?",
            status, error, Timestamp.valueOf(LocalDateTime.now()), taskId);
    }

    // ---------------------------------------------------------------- 心跳与僵尸

    /** 给自己名下正在跑的任务续租。进程死了，续租停止，租约到期。 */
    @Scheduled(fixedDelay = 2000)
    public void heartbeat() {
        if (owned.isEmpty()) {
            return;
        }
        Timestamp lease = Timestamp.valueOf(LocalDateTime.now().plusSeconds(leaseSeconds));
        owned.keySet().forEach(taskId ->
            jdbc.update("update llm_task set lease_until = ? where id = ? and status = 'RUNNING'", lease, taskId));
    }

    private void renewLease(String taskId) {
        heartbeat();
    }

    /**
     * 启动时先扫一遍僵尸任务：上一个进程如果被 kill 掉，状态会停在 RUNNING，
     * 租约到期之后再没人接手，这些任务就永远卡住了。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() {
        recoverStale();
    }

    /**
     * 回收僵尸任务：状态还停在 RUNNING，但租约早过期了 —— 说明跑它的进程没了。
     *
     * <p>把它们改成 FAILED，下一次 resume 就能接着跑。
     */
    public int recoverStale() {
        int n = jdbc.update("""
            update llm_task
               set status = 'FAILED', error = 'runner lost: 租约过期', updated_at = ?
             where status = 'RUNNING' and lease_until < ?
            """, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
        if (n > 0) {
            log.warn("回收僵尸任务 {} 个", n);
        }
        return n;
    }

    // ---------------------------------------------------------------- 查询

    public TaskView describe(String taskId) {
        List<TaskRow> tasks = jdbc.query("""
            select id, tenant, kind, status, current_step, error, created_at from llm_task where id = ?
            """, (rs, i) -> new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime().toString()), taskId);

        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        List<StepRow> rows = jdbc.query("""
            select step_name, seq, status, attempt, output, error, started_at, finished_at
              from llm_task_step where task_id = ? order by seq
            """, (rs, i) -> {
            Timestamp started = rs.getTimestamp(7);
            Timestamp finished = rs.getTimestamp(8);
            Long ms = (started == null || finished == null) ? null
                : finished.getTime() - started.getTime();
            return new StepRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), ms);
        }, taskId);

        return new TaskView(tasks.get(0), rows);
    }

    public List<TaskRow> list() {
        return jdbc.query("""
            select id, tenant, kind, status, current_step, error, created_at
              from llm_task order by created_at desc limit 20
            """, (rs, i) -> new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime().toString()));
    }

    private StepRow loadStep(String taskId, String stepName) {
        return jdbc.queryForObject("""
            select step_name, seq, status, attempt, output, error,
                   cast(null as bigint) as ms
              from llm_task_step where task_id = ? and step_name = ?
            """, (rs, i) -> new StepRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4),
            rs.getString(5), rs.getString(6), rs.getObject(7) == null ? null : rs.getLong(7)), taskId, stepName);
    }

    private Map<String, String> outputs(String taskId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("select step_name, output from llm_task_step where task_id = ? order by seq",
            rs -> {
                out.put(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2));
            }, taskId);
        return out;
    }

    // ---------------------------------------------------------------- 演示开关

    public void failOnceAt(String stepName) {
        this.failOnceAtStep = stepName;
    }

    public void reset() {
        jdbc.update("delete from llm_task_step");
        jdbc.update("delete from llm_task");
        failOnceAtStep = null;
    }

    // ---------------------------------------------------------------- DTO

    public record RunResult(String status, String failedStep, boolean claimed, String message) {}

    public record TaskRow(String id, String tenant, String kind, String status, String currentStep,
                          String error, String createdAt) {}

    public record StepRow(String name, int seq, String status, int attempt, String output,
                          String error, Long ms) {}

    public record TaskView(TaskRow task, List<StepRow> steps) {}

    public static class StepFailedException extends RuntimeException {

        private final String stepName;

        StepFailedException(String stepName, String message) {
            super(message);
            this.stepName = stepName;
        }

        String stepName() {
            return stepName;
        }
    }
}