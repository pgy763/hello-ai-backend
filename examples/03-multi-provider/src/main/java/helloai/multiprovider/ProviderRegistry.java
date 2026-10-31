package helloai.multiprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 注册表：把 yaml 里的每个 provider key 变成一个可用的 {@link LlmProvider}。
 *
 * <p>这里是本章和「每家写一个 @Component」最大的不同：**兼容协议的厂商一行 Java 都不用写。**
 * 只有当某家的协议不兼容时，才需要写一个自己的 {@code LlmProvider} Bean（名字与配置的 key 相同），
 * 注册表会优先用它 —— 见 {@link #pickOverride}。
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, LlmProvider> providers;

    public ProviderRegistry(
        LlmProperties props,
        ObjectMapper json,
        HttpClient http,
        org.springframework.beans.factory.ObjectProvider<LlmProvider> customBeans
    ) {
        Map<String, LlmProvider> built = new LinkedHashMap<>();

        props.providers().forEach((name, cfg) -> {
            LlmProvider custom = pickOverride(customBeans, name);
            built.put(name, custom != null ? custom : new OpenAiCompatProvider(name, cfg, http, json));
            if (custom != null) {
                log.info("provider [{}] 使用自定义实现 {}", name, custom.getClass().getSimpleName());
            } else {
                log.info("provider [{}] 使用 OpenAiCompatProvider，model={}", name, cfg.model());
            }
        });

        this.providers = Map.copyOf(built);
        validateRouting(props);
    }

    /** 配置里写了、容器里却没有同名 Bean 时返回 null（走默认适配器） */
    private static LlmProvider pickOverride(
        org.springframework.beans.factory.ObjectProvider<LlmProvider> customBeans,
        String name
    ) {
        for (LlmProvider bean : customBeans) {
            if (name.equals(bean.name())) {
                return bean;
            }
        }
        return null;
    }

    /**
     * 启动期对账：yaml 里配的路由指向一个不存在的 provider，立刻炸。
     *
     * <p>不留到第一次业务请求 —— 否则线上表现是「只有分类任务挂、其他都正常」，看起来像偶发。
     */
    private void validateRouting(LlmProperties props) {
        LlmProperties.RoutingConfig routing = props.routing();
        require(routing.defaultProvider(), "routing.default-provider");
        if (routing.byTask() != null) {
            routing.byTask().forEach((task, provider) ->
                require(provider, "routing.by-task." + task)
            );
        }
    }

    private void require(String name, String where) {
        if (name == null || !providers.containsKey(name)) {
            throw new IllegalStateException(
                where + " 指向了未注册的 provider: " + name + "，已注册: " + providers.keySet()
            );
        }
    }

    public LlmProvider get(String name) {
        LlmProvider p = providers.get(name);
        if (p == null) {
            throw new IllegalStateException("未注册的 provider: " + name + "，已有: " + providers.keySet());
        }
        return p;
    }

    public Set<String> names() {
        return providers.keySet();
    }
}