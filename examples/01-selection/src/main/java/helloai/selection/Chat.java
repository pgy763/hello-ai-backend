package helloai.selection;

/**
 * 三种写法共用的最小契约。
 *
 * <p>注意：**这个接口本身就是「接入层」的雏形**。裸 HTTP 写法里没有它，
 * 所以调用方直接依赖具体类；有了它，换实现、注入故障、加降级才成为可能。
 */
interface Chat {

    Reply ask(String question);
}

/**
 * 一次回复。
 *
 * <p>{@code promptTokens} 和 {@code provider} 允许为 null —— 这正是差异所在：
 * 有的写法拿不到这些信息，而拿不到就没法计量、没法排查。
 */
record Reply(String text, Integer promptTokens, Integer completionTokens, String provider) {

    static Reply textOnly(String text) {
        return new Reply(text, null, null, null);
    }
}