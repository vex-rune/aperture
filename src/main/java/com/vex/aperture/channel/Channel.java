package com.vex.aperture.channel;

import org.springframework.web.socket.CloseStatus;

import java.util.Map;

/**
 * 通道上下文接口;每条通道一个专用实现(ASR → {@code AsrChannel},Agent → {@code AgentChannel}, Device → {@code DeviceChannel}
 * 等),不在多通道间共享"通用"实现 —— 业务下行方法只属于 各自的专用 channel,不强转、不混入。
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
     * 下发一帧 JSON
     *
     * @param type    报文类型
     * @param payload 载荷
     */
    void send(String type, Map<String, Object> payload);

    /**
     * 下发 session.ready
     */
    void sendReady();

    /**
     * 下发 session.reject
     *
     * @param code    错误码
     * @param message 提示
     */
    void sendReject(int code, String message);

    /**
     * 下发 error
     *
     * @param code    错误码
     * @param message 提示
     * @param fatal   是否致命
     */
    void sendError(int code, String message, boolean fatal);
}
