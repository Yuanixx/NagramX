/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.telegram.ui.Components.seek;

/**
 * 原生 Media3 SeekMap 是否真的能把目标时间解析成有效字节位置的纯判定。
 *
 * 为什么需要它：Media3 1.11.1 的 MfraSeekMap.getSeekPoints(timeUs) 在
 * "目标早于 tfra 首个索引点"或"tfra 为空"时返回 SeekPoint(0, firstMediaDataPosition)，
 * 也就是把 seek 钳到时间 0 —— 这正是"拖到某个位置又跳回开头"的直接代码路径；
 * 目标超出 tfra 末点时会 stayInBounds 钳到末点，同样不是用户要的目标。
 * 仅凭 seekMap.isSeekable() 就相信它能定位任意目标是不安全的。
 *
 * 这里只用 map 自己对目标的解析结果和它声明的时长做判断，不使用任何固定秒数容差：
 * - target<=0：起点，任何 map 都能解析，放行；
 * - 目标被解析回时间 0（而目标>0）：说明 map 没真正覆盖目标，拒绝；
 * - map 声明了可信时长（!= TIME_UNSET）却目标超出：拒绝；
 * - 时长未知（TIME_UNSET，见上游 issue #3524）时，只要解析结果不是归零就放行，
 *   因为 tfra 末点低估时长并不影响它对覆盖范围内目标的字节定位。
 *
 * 该规则从 Media3 1.11.1 源码行为归纳而来，无 Android / media3 依赖，可纯 JVM 单测。
 */
public final class NativeSeekRules {

    private NativeSeekRules() {
    }

    /** Media3 C.TIME_UNSET。 */
    public static final long TIME_UNSET = Long.MIN_VALUE;

    /**
     * @param durationUs      map 声明的时长；TIME_UNSET 表示未知
     * @param resolvedTimeUs  map.getSeekPoints(targetUs).first.timeUs
     * @param targetUs        用户目标时间（微秒）
     */
    public static boolean canResolve(long durationUs, long resolvedTimeUs, long targetUs) {
        if (targetUs <= 0L) {
            return true;
        }
        // map 把正目标解析回时间 0：这就是 media3 的归零路径，绝不可信。
        if (resolvedTimeUs <= 0L && targetUs > 0L) {
            return false;
        }
        // 声明了可信时长且目标越界：不可信。
        if (durationUs != TIME_UNSET && durationUs > 0L && targetUs > durationUs) {
            return false;
        }
        return true;
    }
}
