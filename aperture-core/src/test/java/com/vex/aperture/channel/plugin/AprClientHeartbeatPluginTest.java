package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.AbstractChannel;
import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.ApertureChannelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// 客户端心跳插件单测 —— 只认 heartbeat.ping;超时回 reject 并以 1001 关闭。
class AprClientHeartbeatPluginTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /// 记录下行报文的测试通道
    static class CapturingChannel extends AbstractChannel {

        final List<String> sent = new ArrayList<>();

        CapturingChannel(WebSocketSession session) {
            super(session, MAPPER);
        }

        @Override
        public void sendObject(String type, Object payload) {
            sent.add(type + "\n" + MAPPER.writeValueAsString(payload));
        }
    }

    private static WebSocketSession openSession(String sid) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(sid);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return s;
    }

    // ============ 计时 ============

    @Test
    @DisplayName("收到 heartbeat.ping → 刷新计时,不超时")
    void pingRefreshesTimer() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getClientHeartbeat().setEnabled(true);
        props.getClientHeartbeat().setTimeoutMs(60_000);
        AprClientHeartbeatPlugin plugin = new AprClientHeartbeatPlugin(props);

        WebSocketSession s = openSession("s1");
        CapturingChannel ch = new CapturingChannel(s);
        plugin.onOpen(s, ch);

        plugin.onInbound(s, ch, new TextMessage(AbstractChannelHandler.HEARTBEAT_PING + "\n{}"));
        plugin.onIdle(s, ch, 1000);

        verify(s, never()).close(org.mockito.ArgumentCaptor.forClass(CloseStatus.class).capture());
    }

    @Test
    @DisplayName("收到业务报文(非 heartbeat.ping)→ 不刷新客户端心跳计时")
    void businessFrameDoesNotRefreshTimer() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getClientHeartbeat().setEnabled(true);
        props.getClientHeartbeat().setTimeoutMs(60_000);
        AprClientHeartbeatPlugin plugin = new AprClientHeartbeatPlugin(props);

        WebSocketSession s = openSession("s2");
        CapturingChannel ch = new CapturingChannel(s);
        plugin.onOpen(s, ch);

        plugin.onInbound(s, ch, new TextMessage("echo.raw\n{}"));
        Long last = (Long) s.getAttributes().get(AprClientHeartbeatPlugin.ATTR_LAST_PING);
        assertThat(last).isNotNull();

        // 把计时基准改到很久以前,模拟"客户端很久没发心跳"
        s.getAttributes().put(AprClientHeartbeatPlugin.ATTR_LAST_PING,
                System.currentTimeMillis() - 120_000);
        plugin.onIdle(s, ch, 1000);

        verify(s, atLeastOnce()).close(org.mockito.ArgumentCaptor.forClass(CloseStatus.class).capture());
    }

    // ============ 超时 ============

    @Test
    @DisplayName("超时未收到 heartbeat.ping → 回 session.reject{heartbeat_timeout} 并以 1001 关闭")
    void timeoutRejectsAndCloses() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getClientHeartbeat().setEnabled(true);
        props.getClientHeartbeat().setTimeoutMs(1000);
        AprClientHeartbeatPlugin plugin = new AprClientHeartbeatPlugin(props);

        WebSocketSession s = openSession("s3");
        CapturingChannel ch = new CapturingChannel(s);
        plugin.onOpen(s, ch);

        s.getAttributes().put(AprClientHeartbeatPlugin.ATTR_LAST_PING,
                System.currentTimeMillis() - 5000);
        plugin.onIdle(s, ch, 5000);

        assertThat(ch.sent).hasSize(1);
        assertThat(ch.sent.get(0)).startsWith("session.reject\n").contains("\"code\":\"heartbeat_timeout\"");
        verify(s, atLeastOnce()).close(org.mockito.ArgumentCaptor
                .forClass(CloseStatus.class).capture());
    }

    @Test
    @DisplayName("timeout 配成 0 → 只记录不断连")
    void zeroTimeoutNeverCloses() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getClientHeartbeat().setEnabled(true);
        props.getClientHeartbeat().setTimeoutMs(0);
        AprClientHeartbeatPlugin plugin = new AprClientHeartbeatPlugin(props);

        WebSocketSession s = openSession("s4");
        CapturingChannel ch = new CapturingChannel(s);
        plugin.onOpen(s, ch);

        plugin.onIdle(s, ch, 999_999);

        verify(s, never()).close(org.mockito.ArgumentCaptor.forClass(CloseStatus.class).capture());
    }

    // ============ 独立开关 ============

    @Test
    @DisplayName("插件关闭 → enabled()=false,客户端发心跳也不计时")
    void disabledPluginDoesNothing() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getClientHeartbeat().setEnabled(false);
        AprClientHeartbeatPlugin plugin = new AprClientHeartbeatPlugin(props);

        WebSocketSession s = openSession("s5");
        CapturingChannel ch = new CapturingChannel(s);

        assertThat(plugin.enabled()).isFalse();
        plugin.onOpen(s, ch);
        plugin.onIdle(s, ch, 999_999);

        verify(s, never()).close(org.mockito.ArgumentCaptor.forClass(CloseStatus.class).capture());
    }
}
