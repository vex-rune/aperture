package com.vex.aperture.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// 示例端点集成测试 —— 每条用例都以**真实上行报文**驱动,断言**完整下行 JSON**。
///
/// <p>帧信封:{"header":{"type","sessionId","seq"},"payload":{...}}
/// 二进制帧:首字节 = frameType,其余字节原样交付。
@SpringBootTest
class EchoControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    WebSocketHandlerMapping handlerMapping;

    // ============ 报文常量(与 EchoController 的路由一一对应)============

    private static final String ECHO_RAW = """
            {"header":{"type":"echo.raw","sessionId":"s-test","seq":1},
             "payload":{"text":"hi"}}""";

    private static final String ECHO_DTO = """
            {"header":{"type":"echo.dto","sessionId":"s-test","seq":5},
             "payload":{"text":"ab","repeat":3}}""";

    private static final String ECHO_DTO_BAD = """
            {"header":{"type":"echo.dto","sessionId":"s-test","seq":6},
             "payload":{"text":"","repeat":3}}""";

    private static final String ECHO_UNKNOWN_TYPE = """
            {"header":{"type":"echo.not-exist"},"payload":{}}""";

    private static final String ECHO_NO_HEADER = """
            {"payload":{"text":"hi"}}""";

    // ============ 端点注册 ============

    @Test
    @DisplayName("端点注册 —— @AprController(\"/ws/echo\") 出现在 WebSocketHandlerMapping")
    void endpointRegistered() {
        assertThat(handlerMapping.getUrlMap()).containsKey("/ws/echo");
    }

    // ============ 文本路由:echo.raw ============

    @Test
    @DisplayName("echo.raw —— payload 原样回显在 received 字段")
    void echoRaw() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_RAW);

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"echo.raw.done","sessionId":"s-test","seq":1},"payload":{"received":"{\\"text\\":\\"hi\\"}"}}""");
    }

    // ============ 文本路由:echo.dto ============

    @Test
    @DisplayName("echo.dto —— DTO 校验通过,text 按 repeat 重复,并回带 sid / seq")
    void echoDto() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_DTO);

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"echo.dto.done","sessionId":"s-test","seq":1},"payload":{"sid":"s-test","seq":5,"text":"ababab"}}""");
    }

    @Test
    @DisplayName("echo.dto 校验失败 —— text 为空触发 @NotBlank,回 error{4400}")
    void echoDtoValidationFailure() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_DTO_BAD);

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4400,"message":"text 不能为空","fatal":false}}""");
    }

    @Test
    @DisplayName("echo.dto 校验失败 —— repeat 越界(>100)回 error{4400}")
    void echoDtoRepeatOutOfRange() throws Exception {
        Fixture f = routedFixture();
        f.sendText("""
                {"header":{"type":"echo.dto"},"payload":{"text":"ab","repeat":999}}""");

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4400,"message":"repeat 最大不能超过100","fatal":false}}""");
    }

    // ============ 二进制路由 ============

    @Test
    @DisplayName("二进制帧 —— 首字节 0x01 作 frameType,剩余字节透传")
    void binaryFrame() throws Exception {
        Fixture f = routedFixture();
        f.sendBinary(new byte[]{0x01, 0x7F, 0x00, (byte) 0x80});

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"echo.binary.done","sessionId":"s-test","seq":1},"payload":{"frameType":1,"bytes":3,"sessionId":"s-test"}}""");
    }

    // ============ 异常路由 ============

    @Test
    @DisplayName("未知报文类型 → error{4401}")
    void unknownType() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_UNKNOWN_TYPE);

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4401,"message":"未知报文类型 echo.not-exist","fatal":false}}""");
    }

    @Test
    @DisplayName("header 缺失 → 无法取路由键,error{4400}")
    void missingHeader() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_NO_HEADER);

        assertThat(f.sentJson()).isEqualTo("""
                {"header":{"type":"error","sessionId":"s-test","seq":1},"payload":{"code":4400,"message":"JSON 解析失败","fatal":false}}""");
    }

    // ============ 建连 ============

    @Test
    @DisplayName("建连即下发 —— echo.hello 带 sessionId 与 path")
    void onOpenSendsHello() throws Exception {
        Fixture f = routedFixture();
        f.sendOpen();

        // afterEstablished 先 onOpen 后 sendReady,hello 在前、ready 在后
        assertThat(f.sentJsonOf("echo.hello")).isEqualTo("""
                {"header":{"type":"echo.hello","sessionId":"s-test","seq":1},"payload":{"sessionId":"s-test","path":"/ws/echo"}}""");
        assertThat(f.sentJsonOf("session.ready")).contains("\"type\":\"session.ready\"");
    }

    // ============ 夹具 ============

    /// 走完整 {@code handleFrame} 分派链(而非直调业务方法),确保路由表已注册
    private record Fixture(WebSocketSession session, EchoController controller) {

        void sendText(String frame) {
            controller.handleFrame(session, new TextMessage(frame));
        }

        void sendBinary(byte[] bytes) {
            controller.handleFrame(session, new BinaryMessage(bytes));
        }

        void sendOpen() {
            try {
                controller.afterEstablished(session);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        /// 抓取最后一条下行报文
        String sentJson() throws Exception {
            return last();
        }

        /// 抓取指定 {@code header.type} 的下行报文
        String sentJsonOf(String type) throws Exception {
            for (String json : all()) {
                if (json.contains("\"type\":\"" + type + "\"")) {
                    return json;
                }
            }
            throw new AssertionError("未找到 type=" + type + " 的下行报文: " + all());
        }

        private List<String> all() throws Exception {
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeastOnce()).sendMessage(captor.capture());
            return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
        }

        private String last() throws Exception {
            List<String> sent = all();
            return sent.get(sent.size() - 1);
        }
    }

    private static Fixture routedFixture() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            EchoController controller = new EchoController(MAPPER, factory.getValidator());
            controller.registerRoutes();

            WebSocketSession s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn("s-test");
            when(s.isOpen()).thenReturn(true);
            when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>());
            return new Fixture(s, controller);
        }
    }
}