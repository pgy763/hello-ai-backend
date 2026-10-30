package helloai.firstrequest;

/**
 * 流式回调。三件事都必须有位置可放：下一段、说完了、出错了。
 *
 * <p>为什么不用 {@code Stream<String>}：流中途失败时，Stream 只能抛异常，
 * 而异常一抛，前面已经吐出去的内容在调用方手里就是断的。回调有 onError 这个明确的落点。
 */
public interface StreamHandler {

    void onNext(String token);

    void onComplete();

    void onError(Throwable t);
}