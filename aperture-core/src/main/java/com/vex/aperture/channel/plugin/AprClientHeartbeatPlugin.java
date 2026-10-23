package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.ApertureChannelProperties;
import com.vex.aperture.channel.Channel;
import com.vex.aperture.channel.Response;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 客户端心跳插件 —— 客户端主动发 {@code heartbeat.ping},服务端回 {@code heartbeat.pong}。
 *
 * <p>与 {@link AprServerHeartbeatPlugin} <b>完全独立</b>:本插件只认 {@code heartbeat.ping}
 * 这一种报文。客户端从不发时,服务端心跳插件仍照发 Ping、照判超时;本插件关闭时,
 * 客户端照发也不计时。
 *
 * <p>回执由框架内置路由完成(见 {@code AbstractChannelHandler.invokeBuiltin}),
 * 不占用业务路由表 —— 所以本插件的 {@link #onInbound} 只负责**计时**。
 *
 * @since Aperture 0.5
 */
public class AprClientHeartbeatPlugin implements AprChannelPlugin {

    /// 会话属性键 —— 最近一次收到 heartbeat.ping 的时间(epoch millis)
    public static final String ATTR_LAST_PING = "apr.lastClientPingAt";

    /// 超时关闭码 —— 1001(端点离开)
    public static final CloseStatus TIMEOUT_STATUS = new CloseStatus(1001, "client heartbeat timeout");

    private final ApertureChannelProperties props;

    public AprClientHeartbeatPlugin(ApertureChannelProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "client-heartbeat";
    }

    @Override
    public boolean enabled() {
        return props.getClientHeartbeat().isEnabled();
    }

    @Override
    public void onOpen(WebSocketSession session, Object channel) {
        if (!enabled()) {
            return;
        }
        session.getAttributes().putIfAbsent(ATTR_LAST_PING, System.currentTimeMillis());
    }

    @Override
    public boolean onInbound(WebSocketSession session, Object channel, WebSocketMessage<?> message) {
        if (!enabled()) {
            return true;
        }
        if (message instanceof TextMessage text
                && isHeartbeatPing(text.getPayload())) {
            session.getAttributes().put(ATTR_LAST_PING, System.currentTimeMillis());
        }
        return true;
    }

    @Override
    public void onIdle(WebSocketSession session, Object channel, long idleMs) {
        if (!enabled()) {
            return;
        }
        long timeout = props.getClientHeartbeat().getTimeoutMs();
        if (timeout <= 0) {
            return;
        }
        Long last = (Long) session.getAttributes().get(ATTR_LAST_PING);
        if (last == null) {
            return;
        }
        long sincePing = System.currentTimeMillis() - last;
        if (sincePing < timeout) {
            return;
        }
        // 客户端心跳断线 —— 先回 reject 说明原因,再关闭
        try {
            if (channel instanceof Channel ch) {
                ch.sendObject("session.reject", Response.error(
                        Response.HEARTBEAT_TIMEOUT,
                        "客户端心跳超时: " + sincePing + "ms 未收到 heartbeat.ping"));
            }
            if (session.isOpen()) {
                session.close(TIMEOUT_STATUS);
            }
        } catch (Exception ignored) {
            // 连接可能已被对端关闭,交给调度器清理
        }
    }

    /// 首行是否为 heartbeat.ping
    private static boolean isHeartbeatPing(String payload) {
        int nl = payload.indexOf('\n');
        String type = (nl < 0) ? payload : payload.substring(0, nl);
        return AbstractChannelHandler.HEARTBEAT_PING.equals(type);
    }
}