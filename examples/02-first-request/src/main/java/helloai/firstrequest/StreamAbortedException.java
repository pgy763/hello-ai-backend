package helloai.firstrequest;

/**
 * 客户端已经走了。用它把控制流从 {@code onNext} 里抛出去，好让上游停止调用模型。
 *
 * <p>继承 {@link RuntimeException} 而不是 IOException：{@code emitter.send()} 抛的是受检异常，
 * 而中断要发生在 lambda 里 —— 受检异常穿不过去，只能包一层非受检的。
 */
public class StreamAbortedException extends RuntimeException {

    public StreamAbortedException(Throwable cause) {
        super("客户端已断开", cause);
    }
}