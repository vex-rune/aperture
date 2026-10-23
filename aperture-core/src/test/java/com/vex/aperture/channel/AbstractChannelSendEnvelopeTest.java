package com.vex.aperture.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// {@link AbstractChannel} 三种下发方式单测 —— sendObject / sendText / sendBinary。
class AbstractChannelSendEnvelopeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static class TestChannel extends AbstractChannel {
        TestChannel(WebSocketSession session) {
            super(session, MAPPER);
        }
    }

    private static WebSocketSession openSession(String sid) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(sid);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        return s;
    }

    // ============ sendObject ============

    @Test
    @DisplayName("sendObject —— 下行为 type\\n{json},首行是类型,其余是载荷")
    void sendObjectShape() throws Exception {
        WebSocketSession session = openSession("s1");
        new TestChannel(session).sendObject("audio.opening", Channel.payload("sampleRate", 16000));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        String frame = captor.getValue().getPayload();

        assertThat(frame).isEqualTo("audio.opening\n{\"sampleRate\":16000}");
        int nl = frame.indexOf('\n');
        assertThat(MAPPER.readTree(frame.substring(nl + 1)).get("sampleRate").asInt()).isEqualTo(16000);
    }

    @Test
    @DisplayName("sendObject —— 入参可为任意可序列化对象(record),不必手工拼 Map")
    void sendObjectAcceptsRecord() throws Exception {
        WebSocketSession session = openSession("s2");
        new TestChannel(session).sendObject("audio.opening", new Opening(16000, "pcm_s16le"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        assertThat(captor.getValue().getPayload())
                .isEqualTo("audio.opening\n{\"sampleRate\":16000,\"codec\":\"pcm_s16le\"}");
    }

    @Test
    @DisplayName("sendObject —— 载荷为 null → 下发 type\\n{}")
    void sendObjectNullPayload() throws Exception {
        WebSocketSession session = openSession("s3");
        new TestChannel(session).sendObject("ping", null);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        assertThat(captor.getValue().getPayload()).isEqualTo("ping\n{}");
    }

    @Test
    @DisplayName("sendObject —— Map 载荷顺序按插入序保留")
    void sendObjectKeepsOrder() throws Exception {
        WebSocketSession session = openSession("s4");
        new TestChannel(session).sendObject("t", Channel.payload("z", 1, "a", 2, "m", 3));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        assertThat(captor.getValue().getPayload()).isEqualTo("t\n{\"z\":1,\"a\":2,\"m\":3}");
    }

    /// 载荷 record —— 证明 sendObject 不限于 Map
    record Opening(int sampleRate, String codec) {
    }

    // ============ sendText ============

    @Test
    @DisplayName("sendText —— 载荷原样拼接,不做 JSON 转义")
    void sendTextRaw() throws Exception {
        WebSocketSession session = openSession("s5");
        new TestChannel(session).sendText("audio.text", "hello world");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        assertThat(captor.getValue().getPayload()).isEqualTo("audio.text\nhello world");
    }

    @Test
    @DisplayName("sendText —— 载荷含换行时全部保留(只由首行定路由)")
    void sendTextMultiLine() throws Exception {
        WebSocketSession session = openSession("s6");
        new TestChannel(session).sendText("t", "line1\nline2");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        assertThat(captor.getValue().getPayload()).isEqualTo("t\nline1\nline2");
    }

    @Test
    @DisplayName("sendText —— 类型非法(空 / 空白 / 含换行)→ 丢弃该帧")
    void sendTextIllegalTypeDropped() throws Exception {
        WebSocketSession session = openSession("s7");
        TestChannel ch = new TestChannel(session);
        ch.sendText("", "x");
        ch.sendText("  ", "x");
        ch.sendText("bad\ntype", "x");

        verify(session, never()).sendMessage(ArgumentCaptor.forClass(TextMessage.class).capture());
    }

    // ============ sendBinary ============

    @Test
    @DisplayName("sendBinary —— 首字节作 frameType,其余字节原样")
    void sendBinaryShape() throws Exception {
        WebSocketSession session = openSession("s8");
        new TestChannel(session).sendBinary(0x01, new byte[]{0x7F, 0x00, (byte) 0x80});

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        ByteBuffer buf = captor.getValue().getPayload();

        assertThat(buf.remaining()).isEqualTo(4);
        assertThat(buf.get(0) & 0xFF).isEqualTo(0x01);
        assertThat(new byte[]{buf.get(1), buf.get(2), buf.get(3)}).isEqualTo(new byte[]{0x7F, 0x00, (byte) 0x80});
    }

    @Test
    @DisplayName("sendBinary —— 载荷为 null / 空 → 只发首字节")
    void sendBinaryNoPayload() throws Exception {
        WebSocketSession session = openSession("s9");
        TestChannel ch = new TestChannel(session);
        ch.sendBinary(0x02, null);
        ch.sendBinary(0x03, new byte[0]);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(captor.capture());
        for (BinaryMessage m : captor.getAllValues()) {
            assertThat(m.getPayload().remaining()).isEqualTo(1);
        }
        assertThat(captor.getAllValues().get(0).getPayload().get(0) & 0xFF).isEqualTo(0x02);
        assertThat(captor.getAllValues().get(1).getPayload().get(0) & 0xFF).isEqualTo(0x03);
    }

    @Test
    @DisplayName("sendBinary —— frameType 越界(负数 / >255)→ 丢弃该帧")
    void sendBinaryFrameTypeOutOfRange() throws Exception {
        WebSocketSession session = openSession("s10");
        TestChannel ch = new TestChannel(session);
        ch.sendBinary(-1, new byte[]{1});
        ch.sendBinary(256, new byte[]{1});

        verify(session, never()).sendMessage(ArgumentCaptor.forClass(BinaryMessage.class).capture());
    }

    // ============ 会话已关闭 ============

    @Test
    @DisplayName("会话已关闭 → 三种方式都不发送")
    void closedSessionSkipsSend() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s11");
        when(session.isOpen()).thenReturn(false);
        TestChannel ch = new TestChannel(session);
        ch.sendObject("a", Channel.payload());
        ch.sendText("a", "x");
        ch.sendBinary(0x01, new byte[]{1});

        verify(session, never()).sendMessage(ArgumentCaptor
                .forClass(org.springframework.web.socket.WebSocketMessage.class).capture());
    }
}