package com.vex.aperture.channel;

/**
 * 文本帧协议头 —— 信封的 {@code header} 段。
 *
 * <p>下行由 {@link Channel#send} 组装;上行由 {@link AbstractChannelHandler} 从帧 JSON 的
 * {@code header} 字段解析,业务方法可通过 {@link AprHeader} / {@link AprHeaders} 形参拿到。
 *
 * @param type      报文类型 —— 对应 {@link AprTextFrame#value()},上下行路由键
 * @param sessionId 会话 id —— 服务端 {@code WebSocketSession#getId()},与连接一一对应
 * @param seq       帧序号 —— 下行由框架自增(锁内递增,发送顺序即序号顺序);上行由客户端自填,可为 {@code null}
 *
 * @since Aperture 0.5
 */
public record AprFrameHeader(
        String type,
        String sessionId,
        Long seq
) {

    /**
     * 解析信封的 {@code header} 字段;缺失 / null / 类型不符返回 {@code null}
     *
     * @param header header 段原始值
     *
     * @return 解析结果;无法解析返回 null
     */
    static AprFrameHeader from(Object header) {
        if (!(header instanceof java.util.Map<?, ?> map)) {
            return null;
        }
        Object type = map.get("type");
        Object sid = map.get("sessionId");
        Object seq = map.get("seq");
        Long seqVal = (seq instanceof Number n) ? n.longValue() : null;
        return new AprFrameHeader(
                type == null ? null : type.toString(),
                sid == null ? null : sid.toString(),
                seqVal);
    }
}