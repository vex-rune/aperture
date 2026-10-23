package com.vex.aperture.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Aperture 框架 — 声明处理 WebSocket 二进制帧的路由;按 16 字节帧头的 frameType 字段路由。
///
/// <p>用法同 {@link AprTextFrame},支持类级 / 方法级两种风格。
///
/// @since Aperture 0.1
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AprByteFrame {
    /// 16 字节帧头里的 frameType 字段(0x01 = 上行输入音频, 0x02 = 下行输出音频, ...)
    int value();
}
