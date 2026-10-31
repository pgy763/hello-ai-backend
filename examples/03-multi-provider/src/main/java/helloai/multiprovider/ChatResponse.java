package helloai.multiprovider;

/**
 * 统一后的响应。只声明我们真的会读的字段 —— 厂商每加一个字段就跟着改 POJO 是纯消耗。
 */
public record ChatResponse(String content, String finishReason, Usage usage) {

    public record Usage(int promptTokens, int completionTokens) {

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}