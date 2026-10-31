package helloai.capstone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单流水线：落库 → 抢占 → 分类 → 检索 → 生成 → 记账。
 *
 * <p>第 7 章的状态机 + 第 9/10 章的 RAG，在这里合成一条完整的业务流程。
 */
@Service
class TicketService {

    private final JdbcTemplate jdbc;
    private final LlmGateway gateway;
    private final Kb kb;
    private final int maxAttempts;

    TicketService(JdbcTemplate jdbc, LlmGateway gateway, Kb kb,
                  @Value("${capstone.retry.max-attempts}") int maxAttempts) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.kb = kb;
        this.maxAttempts = maxAttempts;
    }

    // ---------------------------------------------------------------- 写

    String create(String text) {
        String id = "t-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into ticket (id, text, status, attempt, cost_micros, created_at, updated_at)
            values (?, ?, 'PENDING', 0, 0, current_timestamp, current_timestamp)
            """, id, text);
        return id;
    }

    /** 定时捞一条待处理的。多实例部署时这里要加 CAS（见第 7 章）。 */
    @Scheduled(fixedDelay = 200)
    @Transactional
    public void tick() {
        // 重试要退避：500ms、1s、2s……否则上游一挂，重试会把配额瞬间打光
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
            "select id, attempt, updated_at from ticket where status = 'PENDING' "
                + "order by created_at limit 5");
        String id = null;
        for (java.util.Map<String, Object> row : rows) {
            int attempt = ((Number) row.get("ATTEMPT")).intValue();
            long backoffMs = 500L << Math.min(attempt, 4);
            java.sql.Timestamp updatedAt = (java.sql.Timestamp) row.get("UPDATED_AT");
            if (System.currentTimeMillis() - updatedAt.getTime() >= backoffMs) {
                id = String.valueOf(row.get("ID"));
                break;
            }
        }
        if (id == null) {
            return;
        }
        int claimed = jdbc.update("update ticket set status = 'RUNNING', updated_at = current_timestamp "
            + "where id = ? and status = 'PENDING'", id);
        if (claimed == 0) {
            return;
        }
        process(id);
    }

    void process(String id) {
        String text = jdbc.queryForObject("select text from ticket where id = ?", String.class, id);
        try {
            // ① 分类：便宜的小请求
            // 注意：指令里不要出现类别名本身，否则模型会把指令当成工单内容来分类
            LlmGateway.Reply classified = gateway.complete(id, "classify",
                "为下面的工单选一个类别，只输出类别名称：\n" + text);
            String category = classified.text().strip();

            // ② 检索：不花模型的钱
            List<Kb.Hit> hits = kb.search(text, 2);
            StringBuilder prompt = new StringBuilder();
            prompt.append("只根据下面的资料回答工单。资料里没有的信息，回答\"资料中没有提到\"。\n");
            prompt.append("回答时用 [编号] 标注来源。\n\n");
            for (int i = 0; i < hits.size(); i++) {
                Kb.Chunk c = hits.get(i).chunk();
                prompt.append('[').append(i + 1).append("] 《").append(c.title()).append("》 ")
                    .append(c.text()).append('\n');
            }
            prompt.append("\n工单：").append(text);

            // ③ 生成：真正花钱的那次
            LlmGateway.Reply answered = gateway.complete(id, "answer", prompt.toString());

            String citations = hits.stream()
                .map(h -> h.chunk().docId() + "/" + h.chunk().seq())
                .reduce((a, b) -> a + "," + b).orElse("");

            jdbc.update("""
                update ticket set status = 'ANSWERED', category = ?, answer = ?, citations = ?,
                       error = null, updated_at = current_timestamp
                where id = ?
                """, category, answered.text(), citations, id);

        } catch (RuntimeException e) {
            int attempt = jdbc.queryForObject("select attempt from ticket where id = ?", Integer.class, id) + 1;
            boolean dead = attempt >= maxAttempts;
            jdbc.update("""
                update ticket set status = ?, attempt = ?, error = ?, updated_at = current_timestamp
                where id = ?
                """, dead ? "DEAD" : "PENDING", attempt, brief(e.getMessage()), id);
        }
    }

    /** 死信重新入队。 */
    String retry(String id) {
        int n = jdbc.update("update ticket set status = 'PENDING', attempt = 0, error = null, "
            + "updated_at = current_timestamp where id = ? and status = 'DEAD'", id);
        return n == 1 ? "已重新入队" : "只有 DEAD 状态需要重新入队";
    }

    // ---------------------------------------------------------------- 读

    Map<String, Object> view(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select id, text, status, attempt, category, answer, citations, error, cost_micros,
                   created_at, updated_at
            from ticket where id = ?
            """, id);
        if (rows.isEmpty()) {
            return Map.of("error", "ticket not found", "id", id);
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        long micros = ((Number) row.get("COST_MICROS")).longValue();
        row.put("costYuan", Math.round(micros / 100.0) / 10000.0);
        return lowerKeys(row);
    }

    List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList("""
            select id, status, category, attempt, cost_micros, created_at
            from ticket order by created_at desc limit ?
            """, limit).stream().map(this::lowerKeys).toList();
    }

    Map<String, Object> stats() {
        List<Map<String, Object>> byStatus = jdbc.queryForList(
            "select status, count(*) as n from ticket group by status");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tickets", byStatus.stream().collect(java.util.stream.Collectors.toMap(
            r -> String.valueOf(r.get("STATUS")), r -> ((Number) r.get("N")).longValue())));
        m.put("kbChunks", kb.size());
        m.put("gatewayMode", gateway.mode().name());
        return m;
    }

    Map<String, Object> cost() {
        Map<String, Object> m = new LinkedHashMap<>();
        Long total = jdbc.queryForObject("select coalesce(sum(cost_micros), 0) from llm_call", Long.class);
        Long calls = jdbc.queryForObject("select count(*) from llm_call", Long.class);
        m.put("calls", calls);
        m.put("totalMicros", total);
        m.put("totalYuan", Math.round(total / 100.0) / 10000.0);
        m.put("avgMicrosPerCall", calls == 0 ? 0 : total / calls);
        m.put("byProvider", jdbc.queryForList("""
            select provider, purpose, count(*) as calls, sum(cost_micros) as micros
            from llm_call group by provider, purpose order by micros desc
            """).stream().map(this::lowerKeys).toList());
        m.put("byTicket", jdbc.queryForList("""
            select ticket_id, count(*) as calls, sum(cost_micros) as micros
            from llm_call group by ticket_id order by micros desc limit 5
            """).stream().map(this::lowerKeys).toList());
        return m;
    }

    private Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k.toLowerCase(), v instanceof java.sql.Clob ? readClob(v) : v));
        return m;
    }

    private static String readClob(Object v) {
        try {
            java.sql.Clob clob = (java.sql.Clob) v;
            return clob.getSubString(1, (int) clob.length());
        } catch (Exception e) {
            return "";
        }
    }

    private static String brief(String msg) {
        if (msg == null) {
            return "unknown";
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}