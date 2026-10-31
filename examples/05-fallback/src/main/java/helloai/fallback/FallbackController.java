package helloai.fallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    private final FallbackChain chain;
    private final FallbackProperties props;
    private final FakeUpstream upstream;

    public FallbackController(FallbackChain chain, FallbackProperties props, FakeUpstream upstream) {
        this.chain = chain;
        this.props = props;
        this.upstream = upstream;
    }

    /** 正常问一句；降级发生了就体现在 {@code degraded} 和 {@code attempts} 里。 */
    @GetMapping("/api/ask")
    public FallbackChain.Answer ask(@RequestParam(defaultValue = "用一句话解释什么是降级链") String q) {
        return chain.ask(q);
    }

    /** 所有路都不通：把每一次尝试都带出去，否则线上没法定位卡在哪一跳。 */
    @ExceptionHandler(FallbackChain.AllTargetsFailedException.class)
    public ResponseEntity<Map<String, Object>> onAllFailed(FallbackChain.AllTargetsFailedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "all_targets_failed");
        body.put("message", e.getMessage());
        body.put("totalMs", e.totalMs());
        body.put("attempts", e.attempts());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** 看看这条链现在长什么样、总预算多少。 */
    @GetMapping("/api/targets")
    public Map<String, Object> targets() {
        return Map.of(
            "totalBudgetMs", props.totalBudgetMs(),
            "chain", props.targets().stream()
                .map(t -> t.name() + "(" + t.model() + ", " + t.timeoutMs() + "ms)")
                .toList()
        );
    }

    /** 一次把所有目标打成同一种故障，方便观察「全挂了」长什么样。 */
    @GetMapping("/api/all-down")
    public List<String> allDown(@RequestParam String fault) {
        FakeUpstream.Fault f = FakeUpstream.Fault.valueOf(fault);
        props.targets().forEach(t -> upstream.markFault(vendorOf(t), f));
        return props.targets().stream().map(FallbackProperties.TargetConfig::name).toList();
    }

    /** url 形如 http://127.0.0.1:8080/fake/primary/chat/completions，vendor 就是 primary。 */
    static String vendorOf(FallbackProperties.TargetConfig target) {
        return target.url().split("/")[4];
    }
}