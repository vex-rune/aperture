package com.vex.aperture.channel;

/**
 * 二进制帧 —— Aperture 不再解析具体协议头。
 *
 * <p>框架层<b>只</b>做一件事:取 binary payload 的第 1 字节作为 {@code frameType},
 * 用来匹配业务 controller 上的 {@link AprByteFrame#value()} 注解值;其余字节原样作为 {@code payload} 交付业务。{@code frameType} 怎么用、第 1
 * 字节之后是否有协议头、flags / seq / tsMs 这些字段怎么布局,完全由业务 controller + 协议服务自己约束;Aperture <b>不</b>做 magic / 长度 / 头部结构校验 ——
 * 这是"框架不耦合具体协议"的体现。
 *
 * @param frameType 路由键 —— {@code payload[0] & 0xFF},与业务 {@link AprByteFrame#value()} 匹配
 * @param payload   binary payload 二进制;首字节已被取走作为 frameType
 *
 * @since Aperture 0.5
 */
public record BinaryFrame(
        int frameType,
        byte[] payload
) {

    /**
     * 帧类型 — 上行输入音频(业务约定)
     */
    public static final int TYPE_INPUT = 0x01;

    /**
     * 帧类型 — 下行输出音频(业务约定)
     */
    public static final int TYPE_OUTPUT = 0x02;
}