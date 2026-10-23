package com.vex.aperture.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// {@link AbstractChannel#send} 下行信封单测 —— 校验 header 段结构与 seq 自增。
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
        // 真实 Spring session 的 attributes 是可变 ConcurrentHashMap,序号计数器要写进去
        when(s.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        return s;
    }

    @Test
    @DisplayName("下行信封为 {header:{type,sessionId,seq}, payload} —— 无 v / ts 字段")
    void envelopeShape() throws Exception {
        WebSocketSession session = openSession("s1");
        new TestChannel(session).send("audio.opening", Channel.payload("sampleRate", 16000));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();

        assertThat(json).contains("\"header\"").contains("\"payload\"");
        assertThat(json).doesNotContain("\"v\":").doesNotContain("\"ts\":");
        assertThat(MAPPER.readTree(json).get("header").get("type").asString()).isEqualTo("audio.opening");
        assertThat(MAPPER.readTree(json).get("header").get("sessionId").asString()).isEqualTo("s1");
        assertThat(MAPPER.readTree(json).get("header").get("seq").asLong()).isEqualTo(1L);
        assertThat(MAPPER.readTree(json).get("payload").get("sampleRate").asInt()).isEqualTo(16000);
    }

    @Test
    @DisplayName("下行 seq 逐帧自增,且递增顺序 == 发送顺序")
    void seqIncrementsInSendOrder() throws Exception {
        WebSocketSession session = openSession("s2");
        TestChannel ch = new TestChannel(session);
        ch.send("a", Channel.payload());
        ch.send("b", Channel.payload());
        ch.send("c", Channel.payload());

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues()).hasSize(3);
        assertThat(captor.getAllValues().stream()
                .map(m -> MAPPER.readTree(m.getPayload()).get("header").get("seq").asLong())
                .toList())
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("会话已关闭 → 不发送,也不消耗序号")
    void closedSessionSkipsSend() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s3");
        when(session.isOpen()).thenReturn(false);
        new TestChannel(session).send("audio.opening", Channel.payload());

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.never()).sendMessage(captor.capture());
    }
}