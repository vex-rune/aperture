package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.ApertureChannelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// 服务端心跳插件单测 —— 空闲发 Ping、超时关闭;与客户端心跳完全独立。
class AprServerHeartbeatPluginTest {

    private static WebSocketSession openSession(String sid) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(sid);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return s;
    }

    // ============ 空闲发 Ping ============

    @Test
    @DisplayName("空闲达到 interval → 下发一个 Ping 帧")
    void pingWhenIdleReachesInterval() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(1000);
        props.getServerHeartbeat().setTimeoutMs(60_000);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s1");
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 1500);

        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(s, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PingMessage.class);
    }

    @Test
    @DisplayName("空闲不足 interval → 不发 Ping")
    void noPingBeforeInterval() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(30_000);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s2");
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 500);

        verify(s, never()).sendMessage(ArgumentCaptor.forClass(WebSocketMessage.class).capture());
    }

    @Test
    @DisplayName("已发过 Ping 且仍空闲 → 本轮不重复打扰")
    void noRepeatPingWithinSameIdleWindow() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(1000);
        props.getServerHeartbeat().setTimeoutMs(60_000);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s3");
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 1500);
        plugin.onIdle(s, null, 2000);

        verify(s, org.mockito.Mockito.times(1)).sendMessage(ArgumentCaptor.forClass(WebSocketMessage.class).capture());
    }

    @Test
    @DisplayName("收到业务帧 → 清掉「已发过 Ping」标记,下次空闲可再发")
    void inboundResetsPingFlag() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(1000);
        props.getServerHeartbeat().setTimeoutMs(60_000);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s4");
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 1500);
        plugin.onInbound(s, null, new TextMessage("t\n{}"));
        plugin.onIdle(s, null, 1600);

        verify(s, org.mockito.Mockito.times(2)).sendMessage(ArgumentCaptor.forClass(WebSocketMessage.class).capture());
    }

    // ============ 超时关闭 ============

    @Test
    @DisplayName("空闲超过 timeout → 以 1001 关闭连接")
    void closeOnTimeout() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(1000);
        props.getServerHeartbeat().setTimeoutMs(2000);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s5");
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 2500);

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(s, atLeastOnce()).close(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo(1001);
    }

    @Test
    @DisplayName("timeout 配成 0 → 只发 Ping,永不因空闲断连")
    void zeroTimeoutNeverCloses() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setIntervalMs(1000);
        props.getServerHeartbeat().setTimeoutMs(0);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s6");
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 999_999);

        verify(s, never()).close(ArgumentCaptor.forClass(CloseStatus.class).capture());
    }

    // ============ 独立开关 ============

    @Test
    @DisplayName("插件关闭 → enabled()=false,且不参与任何动作")
    void disabledPluginDoesNothing() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getServerHeartbeat().setEnabled(false);
        AprServerHeartbeatPlugin plugin = new AprServerHeartbeatPlugin(props);

        WebSocketSession s = openSession("s7");

        assertThat(plugin.enabled()).isFalse();
        plugin.onOpen(s, null);
        plugin.onIdle(s, null, 999_999);

        verify(s, never()).sendMessage(ArgumentCaptor.forClass(WebSocketMessage.class).capture());
        verify(s, never()).close(ArgumentCaptor.forClass(CloseStatus.class).capture());
    }
}
