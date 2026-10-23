package com.vex.aperture.example;

import com.vex.aperture.channel.AbstractChannel;
import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.AprByteFrame;
import com.vex.aperture.channel.AprController;
import com.vex.aperture.channel.AprPayload;
import com.vex.aperture.channel.AprTextFrame;
import com.vex.aperture.channel.ApertureChannelProperties;
import com.vex.aperture.channel.Channel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 回声端点 —— 一条 {@code @AprController} 端点演示框架全部能力。
 *
 * <p>文本帧格式:{@code type + "\n" + json}(首行为路由键,其余为载荷)。
 * 连接建立后服务端先下发 {@code session.ready},随后按首行路由。
 */
@Component
@AprController("/ws/echo")
public class EchoController extends AbstractChannelHandler<Channel> {

    /// 载荷 DTO —— 反射前自动反序列化 + JSR-303 校验,失败回 error{4400}
    public record EchoRequest(@NotBlank String text,
                              @Min(1) @Max(100) Integer repeat) {
    }

    /// 框架自带的默认通道实现 —— 业务无需自定义,直接复用AbstractChannel
    public static class EchoChannel extends AbstractChannel {
        public EchoChannel(org.springframework.web.socket.WebSocketSession session, JsonMapper jsonMapper) {
            super(session, jsonMapper);
        }
    }

    public EchoController(JsonMapper jsonMapper, jakarta.validation.Validator validator,
                         ApertureChannelProperties props) {
        super(jsonMapper, validator, java.util.List.of(), props);
    }

    /// 把本类上的 @AprTextFrame / @AprByteFrame 方法纳入路由表。
    /// 构造期无法传入 {@code this},故须在 @PostConstruct 阶段自我注册 ——
    /// 漏掉这行路由表为空,所有报文都收不到。
    @jakarta.annotation.PostConstruct
    void registerRoutes() {
        registerSelf(this);
    }

    @Override
    protected String channel() {
        return "ws.echo";
    }

    @Override
    protected Channel newChannel(org.springframework.web.socket.WebSocketSession session) {
        return new EchoChannel(session, jsonMapper);
    }

    /// 文本路由 —— 最简形态:原始载荷 JSON
    @AprTextFrame("echo.raw")
    public void onRaw(Channel c, String payloadJson) {
        c.sendObject("echo.raw.done", Channel.payload("received", payloadJson));
    }

    /// 文本路由 —— DTO 载荷
    @AprTextFrame("echo.dto")
    public void onDto(Channel c, @AprPayload EchoRequest req) {
        int repeat = req.repeat() == null ? 1 : req.repeat();
        String joined = String.valueOf(req.text()).repeat(repeat);
        c.sendObject("echo.dto.done", Channel.payload(
                "sessionId", c.id(),
                "text", joined));
    }

    /// 二进制路由 —— 首字节 frameType 由框架取走,其余原样交付
    @AprByteFrame(0x01)
    public void onBinary(Channel c, com.vex.aperture.channel.BinaryFrame frame) {
        c.sendObject("echo.binary.done", Channel.payload(
                "frameType", frame.frameType(),
                "bytes", frame.payload().length,
                "sessionId", c.id()));
    }

    @Override
    protected void onOpen(Channel c) {
        c.sendObject("echo.hello", Channel.payload("sessionId", c.id(), "path", "/ws/echo"));
    }
}