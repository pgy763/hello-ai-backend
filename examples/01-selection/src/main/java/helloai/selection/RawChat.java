package helloai.selection;

import java.util.List;
import java.util.Map;

/**
 * 写法 A：裸 HTTP —— 直接拼 JSON、直接解析，没有接口，没有抽象。
 *
 * <p>这就是第 1 章正文里那二十行。它能跑，而且一开始看起来最省事：
 * 换厂商就是改一个 baseUrl，出问题看堆栈一眼就知道在哪。
 *
 * <p>但它有两个后果，只有跑到生产才会显现：
 * <ol>
 *   <li><b>只取 content 字段</b> —— usage 在响应里，但你不需要它，于是永远不会拿到。
 *       没有 token 数，就没有成本核算（第 8 章）</li>
 *   <li><b>没有可替换点</b> —— 调用方 {@code new} 的是具体类，想加降级只能改这个类本身</li>
 * </ol>
 */
class RawChat implements Chat {

    private final FakeUpstream upstream;

    RawChat(FakeUpstream upstream) {
        this.upstream = upstream;
    }

    @Override
    public Reply ask(String question) {
        // 手拼请求体：这就是「拼 JSON」那一步，框架帮你做的就是这件事
        Map<String, Object> body = Map.of(
            "model", "deepseek-chat",
            "messages", List.of(Map.of("role", "user", "content", question))
        );
        body.hashCode();   // 只是让编译器别抱怨，真实写法这里是序列化后发出去

        // 只取了自己关心的那个字段
        FakeUpstream.Raw raw = upstream.call(question);
        return Reply.textOnly(raw.content());
    }
}