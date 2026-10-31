package helloai.multiprovider;

/**
 * 出错时该干什么。这个枚举是 3.5 第四组的落点：
 * 光看 HTTP 状态码分不清「限流」和「余额不足」，而两者的处理方式完全相反。
 */
public enum ErrorAction {
    /** 退避重试：限流、上游 5xx */
    RETRY,
    /** 告警转人工：余额不足、账号被停 —— 重试一百次也没用，还白烧钱 */
    BILLING,
    /** 直接拒绝用户：内容被安全策略拦下、参数非法 */
    REJECT,
    /** 没匹配到规则时的兜底 */
    FAIL
}