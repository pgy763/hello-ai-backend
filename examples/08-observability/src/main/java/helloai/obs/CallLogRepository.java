package helloai.obs;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CallLogRepository {

    private final JdbcTemplate jdbc;

    CallLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(CallLog log) {
        jdbc.update("""
            insert into llm_call_log (trace_id, attempt, tenant, feature, provider, model, stream,
                                      prompt_tokens, cached_tokens, completion_tokens, tokens_estimated,
                                      cost_micros, ttft_ms, total_ms, status, error_code, created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            log.traceId(), log.attempt(), log.tenant(), log.feature(), log.provider(), log.model(),
            log.stream(), log.promptTokens(), log.cachedTokens(), log.completionTokens(),
            log.tokensEstimated(), log.costMicros(), log.ttftMs(), log.totalMs(),
            log.status(), log.errorCode(), Timestamp.valueOf(LocalDateTime.now()));
    }

    List<CallLog> byTrace(String traceId) {
        return jdbc.query("""
            select trace_id, attempt, tenant, feature, provider, model, stream,
                   prompt_tokens, cached_tokens, completion_tokens, tokens_estimated,
                   cost_micros, ttft_ms, total_ms, status, error_code
              from llm_call_log where trace_id = ? order by attempt
            """, (rs, i) -> map(rs), traceId);
    }

    List<Map<String, Object>> usageBy(String dimension) {
        String column = switch (dimension) {
            case "tenant" -> "tenant";
            case "model" -> "model";
            case "feature" -> "feature";
            default -> throw new IllegalArgumentException("groupBy 只能是 tenant / model / feature");
        };
        return jdbc.queryForList("""
            select %s as dimension,
                   count(*) as calls,
                   sum(prompt_tokens) as prompt_tokens,
                   sum(completion_tokens) as completion_tokens,
                   sum(cost_micros) as cost_micros
              from llm_call_log
             where status = 'OK'
             group by %s
             order by cost_micros desc
            """.formatted(column, column)).stream().map(CallLogRepository::lower).toList();
    }

    Map<String, Object> summary() {
        return lower(jdbc.queryForMap("""
            select count(*) as calls,
                   sum(case when status = 'OK' then 1 else 0 end) as ok_calls,
                   sum(case when tokens_estimated then 1 else 0 end) as estimated_calls,
                   sum(cost_micros) as cost_micros,
                   avg(case when status = 'OK' then ttft_ms end) as avg_ttft_ms,
                   max(total_ms) as max_total_ms
              from llm_call_log
            """));
    }

    /** H2 会把列名大写，统一成小写，免得接口一会儿 DIMENSION 一会儿 dimension。 */
    private static Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    void clear() {
        jdbc.update("delete from llm_call_log");
    }

    private CallLog map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CallLog(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getInt(9),
            rs.getInt(10), rs.getBoolean(11), rs.getLong(12), rs.getObject(13, Integer.class),
            rs.getObject(14, Integer.class), rs.getString(15), rs.getString(16));
    }

    record CallLog(String traceId, int attempt, String tenant, String feature, String provider,
                   String model, boolean stream, int promptTokens, int cachedTokens,
                   int completionTokens, boolean tokensEstimated, long costMicros,
                   Integer ttftMs, Integer totalMs, String status, String errorCode) {}
}