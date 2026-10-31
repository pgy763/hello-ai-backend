package helloai.capstone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableScheduling
public class CapApp {

    public static void main(String[] args) {
        SpringApplication.run(CapApp.class, args);
    }
}

@RestController
class CapController {

    private final TicketService tickets;
    private final LlmGateway gateway;
    private final Kb kb;

    CapController(TicketService tickets, LlmGateway gateway, Kb kb) {
        this.tickets = tickets;
        this.gateway = gateway;
        this.kb = kb;
    }

    record TicketRequest(String text) {}

    @PostMapping("/api/tickets")
    public Map<String, Object> create(@RequestBody TicketRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            return Map.of("error", "text 不能为空");
        }
        String id = tickets.create(req.text());
        return Map.of("id", id, "status", "PENDING");
    }

    @GetMapping("/api/tickets/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return tickets.view(id);
    }

    @GetMapping("/api/tickets")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "10") int limit) {
        return tickets.list(limit);
    }

    @PostMapping("/api/tickets/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        return Map.of("id", id, "result", tickets.retry(id));
    }

    /** 注入故障：ok / primary_down / all_down，用来演示降级和死信。 */
    @PostMapping("/api/fault")
    public Map<String, Object> fault(@RequestParam String mode) {
        gateway.setMode(LlmGateway.Mode.valueOf(mode.toUpperCase()));
        return Map.of("mode", gateway.mode().name());
    }

    @GetMapping("/api/kb/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "3") int k) {
        List<Map<String, Object>> hits = kb.search(q, k).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", h.chunk().docId());
            m.put("title", h.chunk().title());
            m.put("score", Math.round(h.score() * 1000) / 1000.0);
            m.put("text", h.chunk().text());
            return m;
        }).toList();
        return Map.of("query", q, "hits", hits);
    }

    @GetMapping("/api/cost")
    public Map<String, Object> cost() {
        return tickets.cost();
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        return tickets.stats();
    }

    @ExceptionHandler(LlmGateway.RateLimited.class)
    public ResponseEntity<Map<String, Object>> rateLimited(LlmGateway.RateLimited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "1")
            .body(Map.of("error", "rate_limited", "message", e.getMessage()));
    }

    @ExceptionHandler(LlmGateway.UpstreamException.class)
    public ResponseEntity<Map<String, Object>> upstream(LlmGateway.UpstreamException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "upstream_unavailable", "message", e.getMessage()));
    }
}