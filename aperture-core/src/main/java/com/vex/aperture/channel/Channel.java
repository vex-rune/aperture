package com.vex.aperture.channel;

import org.springframework.web.socket.CloseStatus;

import java.util.Map;

/**
 * 通道上下文接口;每条通道一个专用实现(ASR → {@code AsrChannel},Agent → {@code AgentChannel}, Device → {@code DeviceChannel}
 * 等),不在多通道间共享"通用"实现 —— 业务下行方法只属于 各自的专用 channel,不强转、不混入。
 *
 * <p>下行提供三种方式:{@link #sendObject} / {@link #sendText} / {@link #sendBinary}。
 */
public interface Channel {

    /**
     * 组装 payload Map
     *
     * @param keyValues 键值对(交替)
     *
     * @return 有序 Map
     */
    static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 会话 id
     *
     * @return sid
     */
    String id();

    /**
     * 读会话属性
     *
     * @param key 属性键
     *
     * @return 属性值;不存在返回 null
     */
    Object attr(String key);

    /**
     * 写会话属性
     *
     * @param key   属性键
     * @param value 属性值
     */
    void setAttr(String key, Object value);

    /**
     * 会话是否仍打开
     *
     * @return true=打开
     */
    boolean open();

    /**
     * 关闭会话
     *
     * @param status 关闭状态
     */
    void close(CloseStatus status);

    /**
     * 下发一帧 JSON —— {@code type + "\n" + 载荷序列化后的 JSON}。
     *
     * <p>载荷可以是任何能被 Jackson 序列化的对象(record / POJO / Map / 列表),不必手工拼 Map。
     *
     * @param type    报文类型(首行路由键)
     * @param payload 载荷对象,序列化为 JSON 跟在首行之后;为 null 时下发 {@code {}}
     */
    void sendObject(String type, Object payload);

    /**
     * 下发一帧文本 —— {@code type + "\n" + text}。
     *
     * <p>用于载荷已经是字符串的场景(如纯文本、Base64),省去手动拼 JSON 的引号转义。
     *
     * @param type 报文类型(首行路由键)
     * @param text 载荷文本,<b>不做 JSON 转义</b>
     */
    void sendText(String type, String text);

    /**
     * 下发一帧二进制 —— 首字节作 {@code frameType},其余字节原样发送。
     *
     * <p>音频 / 文件等大块数据的零开销通道:不走 JSON、不做 Base64。
     *
     * @param frameType 帧类型(0~255),对应业务侧 {@code @AprByteFrame(N)}
     * @param payload   业务字节,可为 null / 空(此时只发首字节)
     */
    void sendBinary(int frameType, byte[] payload);
}
