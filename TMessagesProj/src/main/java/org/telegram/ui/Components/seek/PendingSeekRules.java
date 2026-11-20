/**
 * ZH-COMMENT-BY-AGENT
 * 文件：PendingSeekRules.java
 * 模块：TMessagesProj/src/main/java/org/telegram/ui/Components/seek
 * 类型：PendingSeekRules（PendingSeekRules）
 * 主要成员：PendingSeekRules（PendingSeekRules）、shouldDefer（是否应Defer）、canConsume（能否Consume）
 * 说明：本文件由中文注释引擎自动生成结构化说明；业务语义详见各成员处注释。
 */
package org.telegram.ui.Components.seek;

/**
 * 延迟 seek 的纯规则：一次性、按请求绑定来源。
 *
 * 一个待重放目标必须同时绑定五样东西：文档、请求 ID、播放器实例、
 * 产生回调时的 prepare generation、以及重建标志。少任何一样都可能让
 * 旧回调完成新请求，或者让新目标被旧探测结果覆盖。
 */
public final class PendingSeekRules {

    private PendingSeekRules() {
    }

    /**
     * 是否应该延迟这次 seek。
     *
     * 只有“流式 + 目标有效 + 当前映射无法覆盖”才延迟；
     * 本地 able 或已探测覆盖时直接走正常 seek。
     */
    public static boolean shouldDefer(boolean streaming, long targetMs, boolean covered) {
        return streaming && targetMs > 0L && !covered;
    }

    /**
     * 回调能否消费待重放目标。
     *
     * @param rebuilt           重建是否已真正投递（不是“打算重建”）
     * @param owner             重建后捕获的播放器实例；空值 表示还没有 owner
     * @param current           当前回调携带的播放器实例
     * @param ownerGeneration   重建后捕获的 prepare 代次
     * @param callbackGeneration 回调产生时随回调传来的 prepare 代次（不是消费时现读）
     * @param pendingDocId      待重放目标所属文档
     * @param currentDocId      回调时当前文档
     * @param pendingRequestId  待重放请求的单调递增 ID
     * @param callbackRequestId 回调所属请求 ID，防止旧请求的 READY 消费新目标
     */
    public static boolean canConsume(boolean rebuilt, Object owner, Object current,
                                     int ownerGeneration, int callbackGeneration,
                                     long pendingDocId, long currentDocId,
                                     long pendingRequestId, long callbackRequestId) {
        return rebuilt
                && owner != null && owner == current
                && ownerGeneration >= 0 && ownerGeneration == callbackGeneration
                && pendingDocId == currentDocId
                && pendingRequestId == callbackRequestId;
    }
}
