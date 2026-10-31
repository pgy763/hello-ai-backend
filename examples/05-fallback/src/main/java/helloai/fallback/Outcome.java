package helloai.fallback;

/**
 * 一次调用的结局。这里最重要的不是「成功/失败」，是
 * <b>这次失败换一家厂商还有没有用</b>。
 *
 * <p>chapter 5 的全部判断都落在 {@link #shouldFallback()} 这一个方法上。
 */
public enum Outcome {
    /** 拿到可用答案 */
    OK,
    /** 超时预算用完 */
    TIMEOUT,
    /** 连接层失败：DNS、拒绝连接、TLS */
    NETWORK,
    /** 5xx / 429：对方这会儿不行，换一家有用 */
    SERVER_ERROR,
    /** 400 参数错：同样的请求换一家还是错，不降级 */
    CLIENT_ERROR,
    /** 这家没额度了：换一家还有额度，降级有用 */
    NO_QUOTA,
    /** 401 / 403：我们自己的 key 或配置坏了，换一家也一样，不降级 */
    AUTH,
    /** HTTP 200，但内容不能用 —— 最坑的一种，状态码骗过了所有监控 */
    MALFORMED,
    /** 预算已经不够启动这一跳，主动跳过 */
    SKIPPED;

    /**
     * 换下一家有没有意义。
     *
     * <p>注意 {@code CLIENT_ERROR} 和 {@code AUTH} 是 false：这两种是「我们自己写错了」，
     * 降级只会把问题藏起来，让你在备路上再撞一次同样的错。
     */
    public boolean shouldFallback() {
        return switch (this) {
            case TIMEOUT, NETWORK, SERVER_ERROR, NO_QUOTA, MALFORMED -> true;
            case OK, CLIENT_ERROR, AUTH, SKIPPED -> false;
        };
    }
}