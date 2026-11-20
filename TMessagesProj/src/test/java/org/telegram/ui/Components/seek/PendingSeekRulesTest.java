/**
 * ZH-COMMENT-BY-AGENT
 * 文件：PendingSeekRulesTest.java
 * 模块：TMessagesProj/src/test/java/org/telegram/ui/Components/seek
 * 类型：PendingSeekRulesTest（PendingSeekRulesTest）
 * 主要成员：Object（对象）、main（main）、testDeferGate（testDeferGate）、testFullIdentityBinding（testFullIdentityBinding）、testConsecutiveRequests（testConsecutiveRequests）、testReplayIsOneShotByCaller（testReplay是否OneShotByCaller）、println（println）、canConsume（能否Consume）、check（检查）、AssertionError（Assertion错误）
 * 说明：本文件由中文注释引擎自动生成结构化说明；业务语义详见各成员处注释。
 */
package org.telegram.ui.Components.seek;

/**
 * 纯 JVM 测试：延迟 seek 的来源绑定与一次性语义。
 * 这里验证的是“旧回调不能完成新请求”，不是简单检查一个布尔返回值。
 */
public final class PendingSeekRulesTest {

    private static int passed;
    private static final Object PLAYER_A = new Object();
    private static final Object PLAYER_B = new Object();

    public static void main(String[] args) {
        testDeferGate();
        testFullIdentityBinding();
        testConsecutiveRequests();
        testReplayIsOneShotByCaller();
        System.out.println("PENDING SEEK: " + passed + " checks passed");
    }

    private static void testDeferGate() {
        // 只有“流式 + 目标有效 + 当前映射不覆盖”才需要延迟重建。
        check(PendingSeekRules.shouldDefer(true, 1000L, false), "未覆盖的流式目标应延迟");
        check(!PendingSeekRules.shouldDefer(true, 1000L, true), "已覆盖目标不应延迟");
        check(!PendingSeekRules.shouldDefer(false, 1000L, false), "本地视频不应延迟");
        check(!PendingSeekRules.shouldDefer(true, 0L, false), "零目标不应延迟");
    }

    private static void testFullIdentityBinding() {
        // 五项全对才允许消费。
        check(PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 3, 3, 7L, 7L, 11L, 11L),
                "同实例+同代次+同文档+同请求才消费");
        check(!PendingSeekRules.canConsume(false, PLAYER_A, PLAYER_A, 3, 3, 7L, 7L, 11L, 11L),
                "重建尚未投递时不能消费");
        check(!PendingSeekRules.canConsume(true, null, PLAYER_A, 3, 3, 7L, 7L, 11L, 11L),
                "没有 owner 时不能消费");
        check(!PendingSeekRules.canConsume(true, PLAYER_B, PLAYER_A, 3, 3, 7L, 7L, 11L, 11L),
                "旧播放器实例不能消费");
        check(!PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 2, 3, 7L, 7L, 11L, 11L),
                "回调产生时的代次不匹配不能消费");
        check(!PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 3, 3, 8L, 7L, 11L, 11L),
                "跨文档不能消费");
        check(!PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 3, 3, 7L, 7L, 10L, 11L),
                "旧请求 ID 不能消费新目标");
    }

    private static void testConsecutiveRequests() {
        // /B/C 连续拖动：只有 C 的 (instance, generation, requestId) 能通过校验。
        boolean aReady = PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 1, 1, 1L, 1L, 1L, 1L);
        boolean bReady = PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 2, 1, 1L, 1L, 2L, 1L);
        boolean cReady = PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 3, 3, 1L, 1L, 3L, 3L);
        check(aReady, "A 自己的 READY 可以消费 A");
        check(!bReady, "B 到来后，A 的旧代次 READY 不能再消费当前 pending");
        check(cReady, "C 的 READY 消费 C");
    }

    private static void testReplayIsOneShotByCaller() {
        // 规则本身是纯函数，不负责清理状态；调用方必须在交付 seek 后清理。
        // 这条断言让“把规则改成有副作用”这类回归立刻暴露。
        check(PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 1, 1, 1L, 1L, 1L, 1L),
                "第一次校验允许");
        check(PendingSeekRules.canConsume(true, PLAYER_A, PLAYER_A, 1, 1, 1L, 1L, 1L, 1L),
                "规则自身不改变状态，一次性由调用方保证");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
