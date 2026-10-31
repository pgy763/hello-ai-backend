package helloai.selection;

/**
 * 写法 C：用托管网关 —— 你只拿到一个黑盒方法。
 *
 * <p>现实里这类方案长这样：{@code gateway.send(prompt)} 返回字符串，参数在控制台配，
 * 降级、限流、审计都在别人那边。
 *
 * <p>它的优点是**上手最快**；缺点是三件事你说了不算：
 * <ol>
 *   <li>拿不到 usage —— 成本只能看账单总额，拆不到单次请求</li>
 *   <li>故障时没有自己的降级链 —— 只能等对方恢复，或者整条链路一起挂</li>
 *   <li>测不了 —— 没有可注入的桩，离线环境跑不了</li>
 * </ol>
 *
 * <p>这个类只有 10 行，但它代表的是「控制权换开发速度」这个交易。
 * 交易本身没有对错，**前提是你知道自己在交易什么**。
 */
class GatewayChat implements Chat {

    private final FakeUpstream remote;

    GatewayChat(FakeUpstream remote) {
        this.remote = remote;
    }

    @Override
    public Reply ask(String question) {
        // 只有文本。没有 usage，没有 provider，没有降级点
        return Reply.textOnly(remote.call(question).content());
    }
}