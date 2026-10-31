package helloai.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class PipelineApp {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApp.class, args);
    }
}

@RestController
class PipelineController {

    /** 用户 → 能看到的权限集合。真实系统从令牌或数据库来。 */
    private static final Map<String, Set<String>> USERS = Map.of(
        "alice", Set.of("public"),
        "bob", Set.of("public", "finance"),
        "carol", Set.of("public", "finance", "cs"));

    private final Pipeline pipeline = new Pipeline(200);

    @PostMapping("/api/docs")
    public Map<String, Object> upsert(@RequestBody Pipeline.Doc doc) {
        return Map.of("id", doc.id(), "result", pipeline.upsert(doc));
    }

    @DeleteMapping("/api/docs/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("id", id, "result", pipeline.delete(id));
    }

    @PostMapping("/api/fail/{id}")
    public Map<String, Object> failNext(@PathVariable String id) {
        pipeline.failNextTime(id);
        return Map.of("id", id, "msg", "下一次索引这个文档会失败");
    }

    @PostMapping("/api/reindex")
    public Map<String, Object> reindex() {
        return pipeline.processPending();
    }

    @PostMapping("/api/dlq/{id}/requeue")
    public Map<String, Object> requeue(@PathVariable String id) {
        return Map.of("id", id, "result", pipeline.requeue(id));
    }

    @GetMapping("/api/state")
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stats", pipeline.stats());
        m.put("docs", pipeline.states());
        return m;
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(
        @RequestParam String q,
        @RequestParam(defaultValue = "alice") String user,
        @RequestParam(defaultValue = "3") int k
    ) {
        Set<String> allowed = USERS.getOrDefault(user, Set.of("public"));
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Pipeline.Hit hit : pipeline.search(q, k, allowed)) {
            Pipeline.Chunk c = hit.chunk();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", c.docId());
            m.put("title", c.title());
            m.put("permission", c.permission());
            m.put("score", Math.round(hit.score() * 1000) / 1000.0);
            m.put("text", c.text().length() > 70 ? c.text().substring(0, 70) + "…" : c.text());
            hits.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", q);
        m.put("user", user);
        m.put("allowedPermissions", allowed);
        m.put("hits", hits);
        return m;
    }

    /** 一键灌入演示数据，省得手打四段正文。 */
    @PostMapping("/api/demo/load")
    public Map<String, Object> load() {
        List<String> results = new ArrayList<>();
        results.add(pipeline.upsert(new Pipeline.Doc("d1", "退款政策", "public", """
            支持 7 天无理由退款。退款到账时间取决于支付方式：银行卡 1-3 个工作日，支付宝微信 24 小时内。

            定制类商品不参与无理由退款，只能走质量问题通道，需在签收后 15 天内提交凭证。""")));
        results.add(pipeline.upsert(new Pipeline.Doc("d2", "发票开具", "finance", """
            电子普通发票在下单后 48 小时内开出，发送到订单预留邮箱。

            增值税专用发票需要提供公司名称、税号、开户行、账号、地址、电话，审核约 3 个工作日。""")));
        results.add(pipeline.upsert(new Pipeline.Doc("d3", "大客户合同", "cs", """
            年采购额超过 100 万可以签署年度框架协议，享受阶梯价格和优先发货。

            框架协议的账期为 60 天，从对账确认日开始计算，签署前需要财务审批。""")));
        return Map.of("results", results);
    }

    @PostMapping("/api/demo/reset")
    public Map<String, Object> reset() {
        List<String> removed = new ArrayList<>();
        for (Pipeline.IndexState st : pipeline.states()) {
            removed.add(pipeline.delete(st.docId()));
        }
        return Map.of("removed", removed);
    }
}