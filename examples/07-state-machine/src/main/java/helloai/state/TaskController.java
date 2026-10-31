package helloai.state;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

    private final TaskService tasks;
    private final FakeLlm llm;

    public TaskController(TaskService tasks, FakeLlm llm) {
        this.tasks = tasks;
        this.llm = llm;
    }

    /** 建任务：只落库，不执行。执行是另一个接口，这样才看得出「状态机在数据库里」。 */
    @PostMapping("/api/tasks")
    public Map<String, Object> create(
        @RequestParam(defaultValue = "t1") String tenant,
        @RequestParam(defaultValue = "客户说他的订单一直没发货，已经等了三天，很生气") String input
    ) {
        String id = tasks.create(tenant, input);
        return Map.of("taskId", id, "status", "PENDING");
    }

    /** 首次执行。同步跑完，结果里带每一次步骤的状态。 */
    @PostMapping("/api/tasks/{id}/run")
    public Map<String, Object> run(@PathVariable String id) {
        return report(id, tasks.run(id, false));
    }

    /** 断点续跑：从第一个没完成的步骤继续，已完成的不重跑。 */
    @PostMapping("/api/tasks/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        return report(id, tasks.run(id, true));
    }

    @GetMapping("/api/tasks/{id}")
    public TaskService.TaskView get(@PathVariable String id) {
        return tasks.describe(id);
    }

    @GetMapping("/api/tasks")
    public List<TaskService.TaskRow> list() {
        return tasks.list();
    }

    /** 演示：让下一次执行到这一步时失败（失败一次后自动清除）。 */
    @PostMapping("/api/fault")
    public Map<String, Object> fault(@RequestParam String step) {
        tasks.failOnceAt(step);
        return Map.of("failOnceAt", step);
    }

    /** 手动回收租约过期的僵尸任务（启动时也会自动跑一次）。 */
    @PostMapping("/api/recover")
    public Map<String, Object> recover() {
        return Map.of("recovered", tasks.recoverStale());
    }

    /** 这一轮跑了多少次 LLM、花了多少 token —— 续跑省下来的就是这些。 */
    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("llmCalls", llm.calls());
        m.put("tokens", llm.tokens(llm.calls()));
        return m;
    }

    @PostMapping("/api/reset")
    public Map<String, Object> reset() {
        tasks.reset();
        llm.reset();
        return Map.of("reset", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> notFound(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    private Map<String, Object> report(String id, TaskService.RunResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", id);
        m.put("result", result);
        m.put("llmCalls", llm.calls());
        m.put("tokens", llm.tokens(llm.calls()));
        m.put("steps", tasks.describe(id).steps());
        return m;
    }
}