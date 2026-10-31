package helloai.selection;

/**
 * 写法 B：自己封一层 —— 接口 + 实现 + 降级。
 *
 * <p>比 A 多的不是"代码量"，而是**三个可插拔的缝**：
 * <ul>
 *   <li>接口：调用方依赖 {@link Chat}，不再依赖具体类</li>
 *   <li>主/备：两个上游，主挂了自动切</li>
 *   <li>元信息：usage 一路带回来，能算钱、能归因</li>
 * </ul>
 *
 * <p>代价是多了 30 行。这 30 行值不值，取决于这个功能活多久 ——
 * 只活两周的脚本不值，活两年的系统很值。
 */
class LayeredChat implements Chat {

    private final FakeUpstream primary;
    private final FakeUpstream backup;

    LayeredChat(FakeUpstream primary) {
        this.primary = primary;
        this.backup = new FakeUpstream();
    }

    @Override
    public Reply ask(String question) {
        try {
            FakeUpstream.Raw raw = primary.call(question);
            return new Reply(raw.content(), raw.promptTokens(), raw.completionTokens(), "primary");
        } catch (RuntimeException e) {
            // 降级的全部意义：主挂了，业务还能出结果
            FakeUpstream.Raw raw = backup.call(question);
            return new Reply(raw.content(), raw.promptTokens(), raw.completionTokens(), "backup");
        }
    }
}