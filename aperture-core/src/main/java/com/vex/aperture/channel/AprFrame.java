package com.vex.aperture.channel;

import java.util.Map;

/**
 * 文本帧信封 —— {@code {"header": {...}, "payload": {...}}}。
 *
 * <p>下行由 {@link Channel#send} 组装;上行由 {@link AbstractChannelHandler} 解析,
 * {@code header.type} 作为路由键,{@code payload} 交给 {@link AprPayload} 形参。
 *
 * @param header  协议头 —— {@code type} / {@code sessionId} / {@code seq}
 * @param payload 业务载荷;为 {@code null} 时序列化为 {@code null}
 *
 * @since Aperture 0.5
 */
public record AprFrame(
        AprFrameHeader header,
        Map<String, Object> payload
) {
}