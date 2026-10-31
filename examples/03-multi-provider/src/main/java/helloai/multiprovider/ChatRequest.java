package helloai.multiprovider;

import java.util.List;

/**
 * 对话请求。用对象传参而不是一长串方法参数 —— 大模型的参数只会越来越多。
 *
 * <p>注意这里没有 {@code model} 字段：模型属于「哪家厂商」，由 provider 的配置决定，
 * 调用方只说「我要做分类」，不该关心具体是哪个模型。
 */
public record ChatRequest(List<Message> messages, double temperature, Integer maxTokens) {

    public record Message(String role, String content) {

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }
    }

    public static ChatRequest of(String system, String user) {
        return new ChatRequest(List.of(Message.system(system), Message.user(user)), 0.7, null);
    }

    public ChatRequest withTemperature(double t) {
        return new ChatRequest(messages, t, maxTokens);
    }
}