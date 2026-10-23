package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.ApertureChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 服务端心跳插件 —— 空闲会话发 WebSocket Ping,超时关闭。
 *
 * <p>与 {@link AprClientHeartbeatPlugin} <b>完全独立</b>:本插件只看"最近一次入站帧",
 * 无论那帧是业务报文还是客户端心跳。因此客户端不发心跳时,服务端仍照发 Ping、照判超时。
 *
 * <p>关闭码 1001(端点离开),语义为"服务端认为客户端已离开"。
 *
 * @since Aperture 0.5
 */
@Slf4j
public class AprServerHeartbeatPlugin implements AprChannelPlugin {

    /// 会话属性键 —— 本轮是否已发过 Ping(避免每轮重复打扰)
    public static final String ATTR_PINGED = "apr.pinged";

    /// 超时关闭码 —— 1001(端点离开);注意 {@code CloseStatus.SESSION_NOT_RELIABLE} 是 Spring 私有码 4500,不是 1001
    public static final CloseStatus TIMEOUT_STATUS = new CloseStatus(1001, "server heartbeat timeout");

    private final ApertureChannelProperties props;

    public AprServerHeartbeatPlugin(ApertureChannelProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "server-heartbeat";
    }

    @Override
    public boolean enabled() {
        return props.getServerHeartbeat().isEnabled();
    }

    @Override
    public void onOpen(WebSocketSession session, Object channel) {
        if (!enabled()) {
            return;
        }
        session.getAttributes().putIfAbsent(ATTR_PINGED, Boolean.FALSE);
    }

    @Override
    public boolean onInbound(WebSocketSession session, Object channel,
                              org.springframework.web.socket.WebSocketMessage<?> message) {
        if (!enabled()) {
            return true;
        }
        // Ping/Pong 是容器层控制帧,不代表业务侧活跃;只业务帧刷新计时
        if (message instanceof org.springframework.web.socket.TextMessage
                || message instanceof org.springframework.web.socket.BinaryMessage) {
            touch(session);
        }
        return true;
    }

    /// 收到一帧业务报文 —— 空闲计时由调度器统一维护,这里只清掉"已发过 Ping"标记
    public void touch(WebSocketSession session) {
        session.getAttributes().put(ATTR_PINGED, Boolean.FALSE);
    }

    @Override
    public void onIdle(WebSocketSession session, Object channel, long idleMs) {
        if (!enabled()) {
            return;
        }
        long timeout = props.getServerHeartbeat().getTimeoutMs();
        if (timeout > 0 && idleMs >= timeout) {
            closeQuietly(session);
            return;
        }
        long interval = props.getServerHeartbeat().getIntervalMs();
        if (interval <= 0 || idleMs < interval) {
            return;
        }
        if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_PINGED))) {
            return;
        }
        session.getAttributes().put(ATTR_PINGED, Boolean.TRUE);
        pingQuietly(session);
    }

    private void pingQuietly(WebSocketSession s) {
        try {
            synchronized (s) {
                if (s.isOpen()) {
                    s.sendMessage(new PingMessage());
                }
            }
        } catch (Exception ex) {
            // Ping 发送失败不代表会话已死 —— 记日志即可,超时判定仍交给下一轮
            log.warn("[aperture] 服务端心跳 Ping 发送失败 sid={} : {}", s.getId(), ex.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession s) {
        try {
            if (s.isOpen()) {
                s.close(TIMEOUT_STATUS);
            }
        } catch (Exception ignored) {
            // 会话可能已被对端关闭,无需再处理
        }
    }
}