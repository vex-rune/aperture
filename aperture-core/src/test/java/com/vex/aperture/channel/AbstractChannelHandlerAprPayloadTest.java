package com.vex.aperture.channel;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// {@link AbstractChannelHandler} 形参校验闭环单测 — 模拟收到 {@code audio.opening\n{json}} 帧:
/// <ol>
///   <li>缺 sampleRate → 基类反射前校验失败,业务方法不被调用;</li>
///   <li>sampleRate 越界(<8000 或 >48000)→ 同上;</li>
///   <li>正常 → 业务方法被调用,形参收到 AudioOpeningRequest record;</li>
///   <li>载荷为空 → 走基类的 "{}" 兜底反序列化,然后校验失败。</li>
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
        String rawJson;
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
        public void sendObject(String type, Object payload) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) payload;
            Object code = m.get("code");
            recorder.sent.add(code == null ? type : type + ",code=" + code + ",msg=" + m.get("message"));
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

    /** 原始载荷形参 —— 验证拿到的是首行之后的 JSON 原文 */
    static class RawBean {
        final Recorder recorder;

        RawBean(Recorder recorder) {
            this.recorder = recorder;
        }

        @AprTextFrame("audio.raw")
        public void onRaw(CapturingChannel ch, String payloadJson) {
            recorder.called = true;
            recorder.rawJson = payloadJson;
        }
    }

    /** 真正的处理器壳 — 把业务 Bean 与 recorder 共享给基类 */
    static class HandlerWithRecorder extends AbstractChannelHandler<CapturingChannel> {

        final Recorder sharedRecorder;

        HandlerWithRecorder(Recorder shared) {
            super(HandlerWithRecorder.jsonMapper(), HandlerWithRecorder.validator(),
                    java.util.List.of(new OpeningBean(shared), new RawBean(shared)));
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
        h.handleFrame(session("s1"), new TextMessage("audio.opening\n{}"));
        assertThat(rec.called).as("业务方法不应被调用").isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("sampleRate=4000 越界(<8000)→ 业务方法不被调用,error{4400} 被下发")
    void outOfRangeLow() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s2"), new TextMessage("audio.opening\n{\"sampleRate\":4000}"));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("sampleRate=192000 越界(>48000)→ 业务方法不被调用,error{4400} 被下发")
    void outOfRangeHigh() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s3"), new TextMessage("audio.opening\n{\"sampleRate\":192000}"));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("正常 sampleRate=16000 → 业务方法被调用,record 字段填充正确")
    void valid() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s4"), new TextMessage("audio.opening\n{\"sampleRate\":16000,\"codec\":\"pcm_s16le\"}"));
        assertThat(rec.called).as("业务方法应被调用").isTrue();
        assertThat(rec.received).isNotNull();
        assertThat(rec.received.sampleRate()).isEqualTo(16000);
        assertThat(rec.received.codec()).isEqualTo("pcm_s16le");
        assertThat(rec.sent).noneMatch(s -> s.startsWith("error"));
    }

    @Test
    @DisplayName("载荷缺失(只有 type\\n)→ 走 {} 兜底反序列化后校验失败")
    void emptyPayload() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s5"), new TextMessage("audio.opening\n"));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("sampleRate"));
    }

    @Test
    @DisplayName("String 形参 → 拿到首行之后的载荷 JSON 原文")
    void rawPayload() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s6"), new TextMessage("audio.raw\n{\"a\":1,\"b\":\"x\"}"));
        assertThat(rec.called).as("业务方法应被调用").isTrue();
        assertThat(rec.rawJson).isEqualTo("{\"a\":1,\"b\":\"x\"}");
    }

    @Test
    @DisplayName("载荷含换行(格式化 JSON)→ 只切第一个换行,其余全归载荷")
    void multiLinePayload() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s7"), new TextMessage("audio.raw\n{\n  \"a\": 1\n}"));
        assertThat(rec.called).isTrue();
        assertThat(rec.rawJson).isEqualTo("{\n  \"a\": 1\n}");
    }

    @Test
    @DisplayName("缺换行分隔符 → 4400「缺少换行分隔符或报文类型为空」")
    void missingSeparator() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s8"), new TextMessage("{\"sampleRate\":16000}"));
        assertThat(rec.called).as("无分隔符无法路由,不应被调用").isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("缺少换行分隔符"));
    }

    @Test
    @DisplayName("首行为空串 → 4400「缺少换行分隔符或报文类型为空」")
    void typeBlank() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s9"), new TextMessage("\n{\"sampleRate\":16000}"));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("缺少换行分隔符"));
    }

    @Test
    @DisplayName("载荷非法 JSON → 4400「载荷解析失败」")
    void malformedJson() {
        Recorder rec = new Recorder();
        HandlerWithRecorder h = new HandlerWithRecorder(rec);
        h.handleFrame(session("s10"), new TextMessage("audio.opening\n{not-json}"));
        assertThat(rec.called).isFalse();
        assertThat(rec.sent).anyMatch(s -> s.startsWith("error") && s.contains("载荷解析失败"));
    }

    @Test
    @DisplayName("启动期 —— @AprTextFrame(\"\") 拒启动")
    void blankFrameTypeRejected() {
        assertThatThrownBy(() -> new AbstractChannelHandler<CapturingChannel>(jsonMapper, validator,
                java.util.List.of(new BlankTypeBean())) {
            @Override
            protected String channel() {
                return "ws.blank";
            }

            @Override
            protected CapturingChannel newChannel(WebSocketSession session) {
                return new CapturingChannel(session, new Recorder());
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@AprTextFrame 的 value 不能为空");
    }

    @Test
    @DisplayName("启动期 —— 缺载荷形参拒启动")
    void missingPayloadParamRejected() {
        assertThatThrownBy(() -> new AbstractChannelHandler<CapturingChannel>(jsonMapper, validator,
                java.util.List.of(new NoPayloadBean())) {
            @Override
            protected String channel() {
                return "ws.nopayload";
            }

            @Override
            protected CapturingChannel newChannel(WebSocketSession session) {
                return new CapturingChannel(session, new Recorder());
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须恰有一个载荷形参");
    }

    @Test
    @DisplayName("启动期 —— 两个载荷形参拒启动")
    void twoPayloadParamsRejected() {
        assertThatThrownBy(() -> new AbstractChannelHandler<CapturingChannel>(jsonMapper, validator,
                java.util.List.of(new TwoPayloadBean())) {
            @Override
            protected String channel() {
                return "ws.twopayload";
            }

            @Override
            protected CapturingChannel newChannel(WebSocketSession session) {
                return new CapturingChannel(session, new Recorder());
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须恰有一个载荷形参");
    }

    /** @AprTextFrame value 为空白 —— 应在启动期被拒 */
    static class BlankTypeBean {
        @AprTextFrame("")
        public void onBlank(CapturingChannel ch, @AprPayload SampleDto req) {
        }
    }

    /** 只有 Channel 形参、没有载荷形参 —— 应在启动期被拒 */
    static class NoPayloadBean {
        @AprTextFrame("audio.nopayload")
        public void onNoPayload(CapturingChannel ch) {
        }
    }

    /** 同时声明 String 与 @AprPayload 两个载荷形参 —— 应在启动期被拒 */
    static class TwoPayloadBean {
        @AprTextFrame("audio.twopayload")
        public void onTwo(CapturingChannel ch, String raw, @AprPayload SampleDto dto) {
        }
    }
}