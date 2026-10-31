package helloai.multiprovider;

import java.util.stream.Stream;

/**
 * 业务层看到的唯一接口。
 *
 * <p>为什么 {@code chat} 和 {@code stream} 是两个方法而不是一个返回 {@code Object} 的：
 * 流式的失败发生在序列**中间**，返回值必须能承载「已经吐了一半才失败」这件事。
 * 塞进一个「要么字符串要么流」的签名里，调用方每次都得 instanceof 再强转。
 */
public interface LlmProvider {
    ChatResponse chat(ChatRequest req);

    Stream<String> stream(ChatRequest req);

    /** provider 名，等于 yaml 里那段配置的 key，日志和对账要用 */
    String name();
}