package com.xinli.aperture.channel;

/**
 * 解析后的二进制音频帧;见协议文档 §3「二进制音频帧头(16 字节)」。
 *
 * @param version   协议版本
 * @param frameType 帧类型;0x01=上行输入音频 0x02=下行输出音频
 * @param flags     标志位;bit0=LAST bit1=DISCARDABLE bit2=ENCRYPTED
 * @param seq       帧序号;单调递增
 * @param tsMs      相对会话起点的毫秒
 * @param streamId  音频流 id;0=主上行输入流
 * @param payload   PCM / Opus 字节
 */
public record AudioFrame(
        int version,
        int frameType,
        int flags,
        long seq,
        long tsMs,
        int streamId,
        byte[] payload
) {

    /**
     * 帧类型 — 上行输入音频
     */
    public static final int TYPE_INPUT = 0x01;

    /**
     * 帧类型 — 下行输出音频
     */
    public static final int TYPE_OUTPUT = 0x02;

    /**
     * flags bit0 — 本流最后一帧
     *
     * @return true=最后一帧
     */
    public boolean last() {
        return (flags & 0x01) != 0;
    }

    /**
     * flags bit1 — 可丢弃
     *
     * @return true=拥塞时可丢
     */
    public boolean discardable() {
        return (flags & 0x02) != 0;
    }
}
