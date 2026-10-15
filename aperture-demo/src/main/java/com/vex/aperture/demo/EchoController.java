package com.vex.aperture.demo;

import com.vex.aperture.channel.AbstractChannel;
import com.vex.aperture.channel.AbstractChannelHandler;
import com.vex.aperture.channel.AprByteFrame;
import com.vex.aperture.channel.AprController;
import com.vex.aperture.channel.AprPayload;
import com.vex.aperture.channel.AprTextFrame;
import com.vex.aperture.channel.BinaryFrame;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 极简 echo 业务聚合 —— 演示 {@code @AprController} + {@code @AprTextFrame} + {@code @AprPayload} 用法。
 *
 * <p>客户端发 {@code {"frame":"echo","payload":{"text":"hello"}}} →
 * 服务端回 {@code {"frame":"echo.reply","payload":{"echo":"hello"}}}。
 *
 * <p>日志约定:
 * <ul>
 *     <li>所有 log 走 SLF4J {@code traceId} MDC(由 {@code TraceHandshakeInterceptor} 在握手期写入);</li>
 *     <li>业务层 {@code log.info/warn} 自动带 traceId,排查时 grep 一个 id 串起全链路;</li>
 *     <li>onOpen / onClose 在父类 {@link AbstractChannelHandler} 暴露,本类重写打印会话生命周期。</li>
 * </ul>
 *
 * @since Aperture 0.4
 */
@Component
@AprController("/ws/demo")
public class EchoController extends AbstractChannelHandler<EchoController.Session> {

    private static final Logger log = LoggerFactory.getLogger(EchoController.class);

    private final JsonMapper jsonMapper;

    public EchoController(JsonMapper jsonMapper, Validator validator) {
        super(jsonMapper, validator, List.of());   // 第一阶段空表
        this.jsonMapper = jsonMapper;
        log.info("[ws.demo] EchoController 实例创建(路由表待 @PostConstruct 填充)");
    }

    @PostConstruct
    void scanSelf() {
        log.info("[ws.demo] @PostConstruct 触发 scanSelf —— 反射注册本 controller 的方法级 @AprTextFrame / @AprByteFrame");
        super.registerSelf(this);
    }

    @Override
    protected String channel() {
        return "ws.demo";
    }

    @Override
    protected Session newChannel(WebSocketSession session) {
        Session ch = new Session(session, jsonMapper);
        log.info("[ws.demo] newChannel sid={} remote={}", session.getId(), session.getRemoteAddress());
        return ch;
    }

    @Override
    protected void onOpen(Session channel) {
        log.info("[ws.demo] onOpen sid={} ready=true (session.ready 已下发)", channel.id());
    }

    @Override
    protected void onClose(Session channel, CloseStatus status) {
        log.info("[ws.demo] onClose sid={} code={} reason={}", channel.id(), status.getCode(), status.getReason());
    }

    @AprTextFrame("echo")
    public void onEcho(Session session, @AprPayload EchoBody body) {
        log.info("[ws.demo] onEcho sid={} payload.text=\"{}\"", session.id(), body.text());
        session.send("echo.reply", com.vex.aperture.channel.Channel.payload("echo", body.text()));
        log.info("[ws.demo] onEcho sid={} 已下发 echo.reply", session.id());
    }

    /**
     * 二进制帧 0x01 —— 业务示例:把 binary 字节长度回传客户端。
     *
     * <p>框架层只取首字节作为 frameType 路由键;后续 byte[] 完全交给业务。
     * 这里业务把 payload 当 ASCII 文本回显;真实场景按业务协议自行解析。
     */
    @AprByteFrame(0x01)
    public void onBinary(Session session, BinaryFrame frame) {
        String hex = bytesHex(frame.payload());
        log.info("[ws.demo] onBinary sid={} frameType=0x{} payload ({} B) hex={}",
                session.id(), Integer.toHexString(frame.frameType()), frame.payload().length, hex);
        String text = new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8);
        session.send("binary.reply", com.vex.aperture.channel.Channel.payload(
                "frameType", frame.frameType(),
                "bytes", frame.payload().length,
                "text", text));
        log.info("[ws.demo] onBinary sid={} 已下发 binary.reply", session.id());
    }

    private static String bytesHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(空)";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < Math.min(bytes.length, 16); i++) {
            sb.append(String.format("%02x ", bytes[i] & 0xFF));
        }
        if (bytes.length > 16) sb.append("…(+").append(bytes.length - 16).append("B)");
        return sb.toString().trim();
    }

    /** 通道会话。继承 AbstractChannel 拿到通用骨架(序号、JSON 信封、同步写会话)。 */
    public static final class Session extends AbstractChannel {
        public Session(WebSocketSession session, JsonMapper jsonMapper) {
            super(session, jsonMapper);
        }
    }

    /** 入参 —— record + JSR-303 校验。 */
    public record EchoBody(@NotBlank String text) {
    }
}