package com.vex.aperture.example;

import com.vex.aperture.channel.ApertureChannelProperties;
import com.vex.aperture.channel.plugin.AprChannelPlugin;
import com.vex.aperture.channel.plugin.AprClientHeartbeatPlugin;
import com.vex.aperture.channel.plugin.AprFrameLimitPlugin;
import com.vex.aperture.channel.plugin.AprPluginScheduler;
import com.vex.aperture.channel.plugin.AprServerHeartbeatPlugin;
import com.vex.aperture.channel.spi.AprWebSocketAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
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

/// 示例端点集成测试 —— 每条用例都以**真实上行报文**驱动,断言**完整下行报文**。
///
/// <p>文本帧:{@code type + "\n" + json}(首行为路由键,其余为载荷)。
/// 二进制帧:首字节 = frameType,其余字节原样交付。
///
/// <p>用例走完整链路 {@code AprWebSocketAdapter → 插件链 → 业务路由},
/// 因此帧大小限制等插件行为与真实运行一致。
@SpringBootTest
class EchoControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    WebSocketHandlerMapping handlerMapping;

    @Autowired
    AprPluginScheduler pluginScheduler;

    @Autowired
    AprFrameLimitPlugin frameLimitPlugin;

    @Autowired
    AprServerHeartbeatPlugin serverHeartbeatPlugin;

    @Autowired
    AprClientHeartbeatPlugin clientHeartbeatPlugin;

    // ============ 报文常量(与 EchoController 的路由一一对应)============

    private static final String ECHO_RAW = """
            echo.raw
            {"text":"hi"}""";

    private static final String ECHO_DTO = """
            echo.dto
            {"text":"ab","repeat":3}""";

    private static final String ECHO_DTO_BAD = """
            echo.dto
            {"text":"","repeat":3}""";

    private static final String ECHO_UNKNOWN_TYPE = """
            echo.not-exist
            {}""";

    private static final String ECHO_NO_SEPARATOR = """
            {"text":"hi"}""";

    // ============ 端点注册 ============

    @Test
    @DisplayName("插件装配 —— 自定义插件(@Order 5)排最前,内置按配置独立开关")
    void pluginsAreWired() {
        // token-auth 来自 ReadmeSampleCompileTest(README 示例代码,也是容器里的自定义插件),
        // @Order(5) 排在内置帧限制(10)之前 —— 顺带验证自定义插件的接入与排序
        assertThat(pluginScheduler.enabledNames())
                .as("默认:token-auth + 帧限制 + 服务端心跳开,客户端心跳关")
                .isEqualTo("token-auth,frame-limit,server-heartbeat");
        assertThat(frameLimitPlugin.enabled()).isTrue();
        assertThat(serverHeartbeatPlugin.enabled()).isTrue();
        assertThat(clientHeartbeatPlugin.enabled()).isFalse();
    }

    @Test
    @DisplayName("端点注册 —— @AprController(\"/ws/echo\") 出现在 WebSocketHandlerMapping")
    void endpointRegistered() {
        assertThat(handlerMapping.getUrlMap()).containsKey("/ws/echo");
    }

    // ============ 文本路由:echo.raw ============

    @Test
    @DisplayName("echo.raw —— 载荷 JSON 原样回显在 received 字段")
    void echoRaw() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_RAW);

        assertThat(f.sentFrame()).isEqualTo("""
                echo.raw.done
                {"received":"{\\"text\\":\\"hi\\"}"}""");
    }

    // ============ 文本路由:echo.dto ============

    @Test
    @DisplayName("echo.dto —— DTO 校验通过,text 按 repeat 重复")
    void echoDto() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_DTO);

        assertThat(f.sentFrame()).isEqualTo("""
                echo.dto.done
                {"sessionId":"s-test","text":"ababab"}""");
    }

    @Test
    @DisplayName("echo.dto 校验失败 —— text 为空触发 @NotBlank,回 error{4400}")
    void echoDtoValidationFailure() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_DTO_BAD);

        assertThat(f.sentFrame()).isEqualTo("""
                error
                {"code":4400,"message":"text 不能为空","fatal":false}""");
    }

    @Test
    @DisplayName("echo.dto 校验失败 —— repeat 越界(>100)回 error{4400}")
    void echoDtoRepeatOutOfRange() throws Exception {
        Fixture f = routedFixture();
        f.sendText("""
                echo.dto
                {"text":"ab","repeat":999}""");

        assertThat(f.sentFrame()).isEqualTo("""
                error
                {"code":4400,"message":"repeat 最大不能超过100","fatal":false}""");
    }

    // ============ 二进制路由 ============

    @Test
    @DisplayName("二进制帧 —— 首字节 0x01 作 frameType,剩余字节透传")
    void binaryFrame() throws Exception {
        Fixture f = routedFixture();
        f.sendBinary(new byte[]{0x01, 0x7F, 0x00, (byte) 0x80});

        assertThat(f.sentFrame()).isEqualTo("""
                echo.binary.done
                {"frameType":1,"bytes":3,"sessionId":"s-test"}""");
    }

    // ============ 异常路由 ============

    @Test
    @DisplayName("未知报文类型 → error{4401}")
    void unknownType() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_UNKNOWN_TYPE);

        assertThat(f.sentFrame()).isEqualTo("""
                error
                {"code":4401,"message":"未知报文类型 echo.not-exist","fatal":false}""");
    }

    @Test
    @DisplayName("缺换行分隔符 → 4400「缺少换行分隔符或报文类型为空」")
    void missingSeparator() throws Exception {
        Fixture f = routedFixture();
        f.sendText(ECHO_NO_SEPARATOR);

        assertThat(f.sentFrame()).isEqualTo("""
                error
                {"code":4400,"message":"缺少换行分隔符或报文类型为空","fatal":false}""");
    }

    @Test
    @DisplayName("首行为空串 → 4400「缺少换行分隔符或报文类型为空」")
    void blankType() throws Exception {
        Fixture f = routedFixture();
        f.sendText("\n{\"text\":\"hi\"}");

        assertThat(f.sentFrame()).isEqualTo("""
                error
                {"code":4400,"message":"缺少换行分隔符或报文类型为空","fatal":false}""");
    }

    // ============ 帧大小限制插件 ============

    @Test
    @DisplayName("文本帧超限 → error{4409} 并以 1009 关闭连接")
    void textFrameTooLarge() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(32);
        Fixture f = routedFixture(props);

        f.sendText("echo.raw\n{\"text\":\"" + "x".repeat(100) + "\"}");

        assertThat(f.sentFrame()).contains("\"code\":4409").contains("文本帧超限");
        assertThat(f.closedWith()).isNotNull();
        assertThat(f.closedWith().getCode()).isEqualTo(1009);
    }

    @Test
    @DisplayName("二进制帧超限 → error{4409} 并以 1009 关闭连接")
    void binaryFrameTooLarge() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxBinaryBytes(4);
        Fixture f = routedFixture(props);

        f.sendBinary(new byte[]{0x01, 0x7F, 0x00, (byte) 0x80, 0x01, 0x02});

        assertThat(f.sentFrame()).contains("\"code\":4409").contains("二进制帧超限");
        assertThat(f.closedWith().getCode()).isEqualTo(1009);
    }

    @Test
    @DisplayName("帧大小上限配成 0 → 不限制,超长帧照常处理")
    void zeroLimitDisablesCheck() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(0);
        Fixture f = routedFixture(props);

        f.sendText("echo.raw\n{\"text\":\"" + "x".repeat(500) + "\"}");

        assertThat(f.sentFrame()).startsWith("echo.raw.done\n");
        assertThat(f.closedWith()).as("不应关闭连接").isNull();
    }

    @Test
    @DisplayName("帧大小限制插件关闭 → 超长帧照常处理(插件独立开关)")
    void frameLimitDisabled() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setEnabled(false);
        props.getFrameLimit().setMaxTextBytes(8);
        Fixture f = routedFixture(props);

        f.sendText("echo.raw\n{\"text\":\"" + "x".repeat(100) + "\"}");

        assertThat(f.sentFrame()).startsWith("echo.raw.done\n");
        assertThat(f.closedWith()).as("插件关闭,不应关闭连接").isNull();
    }

    // ============ session.ready ============

    @Test
    @DisplayName("session.ready 上报实际生效的上限,配置为 0 的项不出现")
    void readyReportsEffectiveLimits() throws Exception {
        ApertureChannelProperties props = new ApertureChannelProperties();
        props.getFrameLimit().setMaxTextBytes(128);
        props.getFrameLimit().setMaxBinaryBytes(0);
        props.getServerHeartbeat().setEnabled(false);
        props.getClientHeartbeat().setEnabled(false);
        Fixture f = routedFixture(props);

        f.sendOpen();

        assertThat(f.sentFrameOf("session.ready")).isEqualTo("""
                session.ready
                {"serverCaps":["text_frame_v1","binary_first_byte_v1","frame_limit_v1"],"limits":{"maxTextFrameBytes":128}}""");
    }

    // ============ 建连 ============

    @Test
    @DisplayName("建连即下发 —— echo.hello 带 sessionId 与 path")
    void onOpenSendsHello() throws Exception {
        Fixture f = routedFixture();
        f.sendOpen();

        // afterEstablished 先 onOpen 后 sendReady,hello 在前、ready 在后
        assertThat(f.sentFrameOf("echo.hello")).isEqualTo("""
                echo.hello
                {"sessionId":"s-test","path":"/ws/echo"}""");
        assertThat(f.sentFrameOf("session.ready")).isEqualTo("""
                session.ready
                {"serverCaps":["text_frame_v1","binary_first_byte_v1","heartbeat_v1","frame_limit_v1"],"serverHeartbeat":{"intervalMs":30000,"timeoutMs":90000},"limits":{"maxTextFrameBytes":65536,"maxBinaryFrameBytes":1048576}}""");
    }

    // ============ 夹具 ============

    /// 走完整链路(适配层 → 插件链 → 业务路由),确保插件行为与真实运行一致
    private record Fixture(WebSocketSession session,
                           AprWebSocketAdapter<com.vex.aperture.channel.Channel> adapter) {

        void sendText(String frame) {
            adapter.handleMessage(session, new TextMessage(frame));
        }

        void sendBinary(byte[] bytes) {
            adapter.handleMessage(session, new BinaryMessage(bytes));
        }

        void sendOpen() {
            try {
                adapter.afterConnectionEstablished(session);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        /// 抓取最后一条下行报文
        String sentFrame() throws Exception {
            return last();
        }

        /// 会话被关闭时的CloseStatus;未关闭返回 null
        CloseStatus closedWith() throws Exception {
            var captor = ArgumentCaptor.forClass(CloseStatus.class);
            verify(session, org.mockito.Mockito.atLeast(0)).close(captor.capture());
            var all = captor.getAllValues();
            return all.isEmpty() ? null : all.get(all.size() - 1);
        }

        /// 抓取指定首行类型(报文类型)的下行报文
        String sentFrameOf(String type) throws Exception {
            for (String frame : all()) {
                if (frame.startsWith(type + "\n")) {
                    return frame;
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
        return routedFixture(new ApertureChannelProperties());
    }

    private static Fixture routedFixture(ApertureChannelProperties props) {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            EchoController controller = new EchoController(MAPPER, factory.getValidator(), props);
            controller.registerRoutes();

            List<AprChannelPlugin> plugins = List.of(
                    new AprFrameLimitPlugin(props),
                    new AprServerHeartbeatPlugin(props),
                    new AprClientHeartbeatPlugin(props));
            AprPluginScheduler scheduler = new AprPluginScheduler(plugins, props);

            WebSocketSession s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn("s-test");
            when(s.isOpen()).thenReturn(true);
            when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>());
            return new Fixture(s, new AprWebSocketAdapter<>(controller, scheduler));
        }
    }
}
