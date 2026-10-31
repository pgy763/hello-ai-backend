package helloai.multiprovider;

import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 业务层唯一该调用的东西。
 *
 * <p>注意第一个参数是**任务名**，不是模型名。差别在换模型那天暴露：
 * 前者改 yaml，后者改所有调用点。
 */
@Service
public class ModelRouter {

    private final LlmProperties props;
    private final ProviderRegistry registry;

    public ModelRouter(LlmProperties props, ProviderRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    public ChatResponse chat(String task, ChatRequest req) {
        return providerFor(task).chat(req);
    }

    public Stream<String> stream(String task, ChatRequest req) {
        return providerFor(task).stream(req);
    }

    private LlmProvider providerFor(String task) {
        return registry.get(props.routing().providerFor(task));
    }

    /** 这个任务会走哪个 provider —— 演示和排查都用得上 */
    public String providerNameFor(String task) {
        return props.routing().providerFor(task);
    }

    /** 给 /api/routing 用：一眼看清「什么任务走谁」 */
    public Map<String, String> routingTable() {
        LlmProperties.RoutingConfig r = props.routing();
        Map<String, String> table = new java.util.LinkedHashMap<>();
        if (r.byTask() != null) {
            table.putAll(r.byTask());
        }
        table.put("*", r.defaultProvider());
        return table;
    }
}