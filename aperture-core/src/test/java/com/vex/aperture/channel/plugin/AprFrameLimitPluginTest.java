package com.vex.aperture.channel.plugin;

import com.vex.aperture.channel.AbstractChannel;
import com.vex.aperture.channel.ApertureChannelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// 帧大小限制插件单测 —— 超限即拒(先判大小再进业务链),插件关闭时完全放行。
class AprFrameLimitPluginTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /// 记录下行报文与关闭状态的测试通道
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

    // ============ 超限拦截 ============

    @Test
    @DisplayName("文本帧超限 → 拦截该帧 + error{4409} + 以 1009 关闭")
    void textTooLargeRejected() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(16);
        AprFrameLimitPlugin plugin = new AprFrameLimitPlugin(props);

        WebSocketSession s = openSession("s1");
        CapturingChannel ch = new CapturingChannel(s);

        boolean pass = plugin.onInbound(s, ch, new TextMessage("x".repeat(100)));

        assertThat(pass).as("超限帧应被拦截").isFalse();
        assertThat(ch.sent).hasSize(1);
        assertThat(ch.sent.get(0)).startsWith("error\n").contains("\"code\":4409").contains("文本帧超限");
        assertThat(ch.sent.get(0)).contains("\"fatal\":true");
        verify(s, atLeastOnce()).close(AprFrameLimitPlugin.TOO_BIG);
    }

    @Test
    @DisplayName("二进制帧超限 → 拦截该帧 + error{4409} + 以 1009 关闭")
    void binaryTooLargeRejected() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxBinaryBytes(4);
        AprFrameLimitPlugin plugin = new AprFrameLimitPlugin(props);

        WebSocketSession s = openSession("s2");
        CapturingChannel ch = new CapturingChannel(s);

        boolean pass = plugin.onInbound(s, ch, new BinaryMessage(new byte[32]));

        assertThat(pass).isFalse();
        assertThat(ch.sent.get(0)).contains("\"code\":4409").contains("二进制帧超限");
        verify(s, atLeastOnce()).close(AprFrameLimitPlugin.TOO_BIG);
    }

    // ============ 未超限 ============

    @Test
    @DisplayName("未超限 → 放行,不发任何报文,不关闭连接")
    void withinLimitPasses() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(1024);
        AprFrameLimitPlugin plugin = new AprFrameLimitPlugin(props);

        WebSocketSession s = openSession("s3");
        CapturingChannel ch = new CapturingChannel(s);

        boolean pass = plugin.onInbound(s, ch, new TextMessage("short"));

        assertThat(pass).isTrue();
        assertThat(ch.sent).isEmpty();
        verify(s, never()).close(org.mockito.ArgumentCaptor.forClass(CloseStatus.class).capture());
    }

    @Test
    @DisplayName("上限配成 0 → 不限制,超长帧照常放行")
    void zeroLimitMeansUnlimited() {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(0);
        AprFrameLimitPlugin plugin = new AprFrameLimitPlugin(props);

        WebSocketSession s = openSession("s4");
        CapturingChannel ch = new CapturingChannel(s);

        assertThat(plugin.onInbound(s, ch, new TextMessage("x".repeat(10_000)))).isTrue();
        assertThat(ch.sent).isEmpty();
    }

    // ============ 独立开关 ============

    @Test
    @DisplayName("插件关闭 → enabled()=false,超长帧照常放行")
    void disabledPluginPassesEverything() {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setEnabled(false);
        props.getFrameLimit().setMaxTextBytes(4);
        AprFrameLimitPlugin plugin = new AprFrameLimitPlugin(props);

        WebSocketSession s = openSession("s5");
        CapturingChannel ch = new CapturingChannel(s);

        assertThat(plugin.enabled()).isFalse();
        assertThat(plugin.onInbound(s, ch, new TextMessage("x".repeat(1000)))).isTrue();
        assertThat(ch.sent).isEmpty();
    }

    // ============ 非文本 / 非二进制帧 ============

    @Test
    @DisplayName("控制帧(Ping 等)→ 不参与大小判定,直接放行")
    void controlFrameIgnored() {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(1);
        AprFrameLimitPlugin plugin = new AprFrameLimitPlugin(props);

        WebSocketSession s = openSession("s6");
        CapturingChannel ch = new CapturingChannel(s);

        assertThat(plugin.onInbound(s, ch, new org.springframework.web.socket.PingMessage())).isTrue();
        assertThat(ch.sent).isEmpty();
    }
}
