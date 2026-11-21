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
 * 纯规则：Media3 原生 SeekMap 是否可信到可以原样放行给播放器。
 *
 * 为什么需要：Media3 1.11.1 默认开启 FLAG_READ_MFRA_FOR_SEEK_MAP，尾部 mfra 会生成
 * MfraSeekMap。它 isSeekable()==true，但 getSeekPoints(target) 在目标早于首 tfra 时
 * 返回时间 0（SeekPoint(0, firstMediaDataPosition)）、目标晚于末 tfra 时钳到末个 moof。
 * 这种 map 一旦原样放行，ExoPlayer 就会拿着错误的 byte offset 去 open DataSource，
 * 表现为"拖到未下载位置跳回开头/落到错误点"。而真正的 sidx 索引会生成 ChunkIndex，
 * 其 times/sizes 是严肃的时间→字节映射，可以信任。
 *
 * 判据（不使用固定秒数容差，只用 map 类型 + 覆盖证据）：
 * - 只有真实 ChunkIndex（sidx）才原样放行；
 * - 其它 seekable map（典型 MfraSeekMap）一律不放行，交给 StreamX 自管的
 *   ChunkIndex / RemoteExtendedSeekMap 映射；
 * - 是否存在自管映射（本地覆盖或远端探测）决定注入哪种 map，由调用方处理。
 *
 * 无 Android / media3 依赖，可纯 JVM 单测。
 */
public final class NativeSeekMapRules {

    private NativeSeekMapRules() {
    }

    /**
     * 原生 map 是否可以原样放行。
     *
     * @param isChunkIndex  原生 map 是否为真实 ChunkIndex（sidx 产物）
     * @param isSeekable    原生 map 的 isSeekable()
     */
    public static boolean shouldPassThrough(boolean isChunkIndex, boolean isSeekable) {
        // 不可 seek 的 map 本就要走注入分支，谈不上"放行原生 map"。
        if (!isSeekable) {
            return false;
        }
        // 只有真实 sidx 的 ChunkIndex 可信；MFRA 等其它 seekable map 一律拦截。
        return isChunkIndex;
    }

    /**
     * 自管映射是否就绪（hasIndex 用）：本地覆盖或远端探测任一存在即为就绪。
     */
    public static boolean hasManagedIndex(boolean attempted, boolean hasLocal,
                                          boolean hasRemote) {
        return attempted && (hasLocal || hasRemote);
    }
}
