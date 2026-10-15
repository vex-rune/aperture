package com.xinli.aperture.channel;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// {@link AbstractChannelHandler} 形参校验闭环单测 — 模拟 handleMessage 收到 audio.opening 帧:
/// <ol>
///   <li>缺 sampleRate → 基类反射前校验失败,业务方法不被调用;</li>
///   <li>sampleRate 越界(<8000 或 >48000)→ 同上;</li>
///   <li>正常 → 业务方法被调用,形参收到 AudioOpeningRequest record;</li>
///   <li>payload=null → 走基类的 "{}" 兜底反序列化,然后校验失败。</li>
/// </ol>
class AbstractChannelHandlerAprPayloadTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static JsonMapper jsonMapper;

    @BeforeAll
    static void build() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        jsonMapper = JsonMapper.builder().build();
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.close();
        }
    }

    /// 共享记录器:业务方法调用情况 + 通道下行信息
    static class Recorder {
        boolean called;
        SampleDto received;
        final List<String> sent = new ArrayList<>();
    }

    /// 测试专用 DTO — 模拟 ASR 端 {@code AudioOpeningRequest} 的形参约束,框架层不依赖任何业务类型
    record SampleDto(@NotNull @Min(8000) @Max(48000) Integer sampleRate, String codec) {
    }

    static class CapturingChannel extends AbstractChannel {

        final Recorder recorder;

        CapturingChannel(WebSocketSession session, Recorder recorder) {
            super(session, jsonMapper);
            this.recorder = recorder;
        }

        @Override
        public void send(String type, java.util.Map<String, Object> payload) {
            recorder.sent.add(type + ":" + payload.get("message"));
        }

        @Override
        public void sendError(int code, String message, boolean fatal) {
            recorder.sent.add("error:code=" + code + ",msg=" + message);
        }
    }

    /** 业务策略 POJO;基类反射扫这类的 @AprTextFrame + @AprPayload */
    static class OpeningBean {
        final Recorder recorder;

        OpeningBean(Recorder recorder) {
            this.recorder = recorder;
        }

        @AprTextFrame("audio.opening")
        public void onOpening(CapturingChannel ch, @AprPayload SampleDto req) {
            recorder.called = true;
            recorder.received = req;
        }
    }

    /** 真正的处理器壳 — 把 OpeningBean(带 @AprTextFrame 注解)与 recorder 共享给基类 */
    static class HandlerWithRecorder extends AbstractChannelHandler<CapturingChannel> {

        final Recorder sharedRecorder;

        HandlerWithRecorder(Recorder shared) {
            super(HandlerWithRecorder.jsonMapper(), HandlerWithRecorder.validator(),
                    java.util.List.of(new OpeningBean(shared)));
            this.sharedRecorder = shared;
        }

        @Override
        protected String channel() {
            return "ws.asr.test";
        }

        @Override
        protected CapturingChannel newChannel(WebSocketSession session) {
            return new CapturingChannel(session, sharedRecorder);
        }

        private static JsonMapper jsonMapper() {
            return AbstractChannelHandlerAprPayloadTest.jsonMapper;
        }

        private static Validator validator() {
            return AbstractChannelHandlerAprPayloadTest.validator;
        }
    }

    private static WebSocketSession session(String sid) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(sid);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("缺 sampleRate → 业务方法不被调用,error{4400} 被下发")
    void missingField() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        String frame = "{\"type\":\"audio.opening\",\"sid\":\"s1\",\"seq\":1,\"ts\":1,\"payload\":{}}";
        h.handleFrame(session("s1"), new TextMessage(frame));
        assertThat(rec.called).as("业务方法不应被调用").isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("sampleRate=4000 越界(<8000)→ 业务方法不被调用,error{4400} 被下发")
    void outOfRangeLow() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        String frame = "{\"type\":\"audio.opening\",\"sid\":\"s2\",\"seq\":1,\"ts\":1,\"payload\":{\"sampleRate\":4000}}";
        h.handleFrame(session("s2"), new TextMessage(frame));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("sampleRate=192000 越界(>48000)→ 业务方法不被调用,error{4400} 被下发")
    void outOfRangeHigh() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        String frame = "{\"type\":\"audio.opening\",\"sid\":\"s3\",\"seq\":1,\"ts\":1,\"payload\":{\"sampleRate\":192000}}";
        h.handleFrame(session("s3"), new TextMessage(frame));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("正常 sampleRate=16000 → 业务方法被调用,record 字段填充正确")
    void valid() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        String frame = "{\"type\":\"audio.opening\",\"sid\":\"s4\",\"seq\":1,\"ts\":1,\"payload\":{\"sampleRate\":16000,\"codec\":\"pcm_s16le\"}}";
        h.handleFrame(session("s4"), new TextMessage(frame));
        assertThat(rec.called).as("业务方法应被调用").isTrue();
        assertThat(rec.received).isNotNull();
        assertThat(rec.received.sampleRate()).isEqualTo(16000);
        assertThat(rec.received.codec()).isEqualTo("pcm_s16le");
        assertThat(rec.sent).noneMatch(s -> s.startsWith("error"));
    }

    @Test
    @DisplayName("载荷整体缺失(payload=null)→ 业务方法不被调用,error{4400} 被下发")
    void nullPayload() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        String frame = "{\"type\":\"audio.opening\",\"sid\":\"s5\",\"seq\":1,\"ts\":1,\"payload\":null}";
        h.handleFrame(session("s5"), new TextMessage(frame));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }
}
