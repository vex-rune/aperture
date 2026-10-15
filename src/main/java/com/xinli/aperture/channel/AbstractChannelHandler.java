package com.xinli.aperture.channel;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aperture 通道处理器基类;类比 Spring {@code @RestController} —— 一个 handler 实例绑定一条
 * WebSocket URL,业务方法用 {@link AprTextFrame} / {@link AprByteFrame} <b>方法级</b>注解声明路由。
 *
 * <p>不再直接 {@code implements WebSocketHandler};由 {@link com.xinli.aperture.channel.spi.AprWebSocketAdapter}
 * 转发 Spring 协议回调到本类的 {@link #afterEstablished}/{@link #handleFrame}/{@link #afterClosed},
 * 配合 {@link com.xinli.aperture.channel.spi.AprControllerRegistry} 做 {@code @AprController}
 * 自动路由注册。
 *
 * <p>业务方法签名(任选其一):
 * <ul>
 *   <li>{@code void onXxx(Channel c, String payloadJson)} — 原始 JSON 载荷;</li>
 *   <li>{@code void onXxx(Channel c, @AprPayload SomeRecord req)} — 经 Jackson 反序列化 + Bean Validation;</li>
 *   <li>{@code void onXxx(Channel c, AudioFrame frame)} — 仅二进制帧路由;</li>
 * </ul>
 *
 * <p>启动期反射建路由表;同一 {@code @AprTextFrame("type")} / {@code @AprByteFrame(N)} 不可重复,
 * 重复直接 {@code IllegalStateException} 拒启动。
 *
 * @since Aperture 0.2
 */
@Slf4j
public abstract class AbstractChannelHandler<C extends Channel> {

    protected static final int FRAME_HEADER_BYTES = 16;
    protected static final int FRAME_MAGIC = 0xA1;

    protected final JsonMapper jsonMapper;
    private final Validator validator;

    /// 路由入口(type / frameType → 调用单元);由 {@link #registerSelf} 在构造后填充
    private final Map<String, TextInvocation> textRoutingTable = new LinkedHashMap<>();
    private final Map<Integer, ByteInvocation> byteRoutingTable = new LinkedHashMap<>();

    /**
     * 构造通道处理器
     *
     * @param jsonMapper JSON 序列化器
     * @param validator  Bean Validation 校验器(Spring 容器提供;{@code @AprPayload} 形参的入参校验用它)
     * @param handlers   任意类型 handler bean 集合 —— 只要带方法级 {@link AprTextFrame} / {@link AprByteFrame}
     *                   注解即可被扫描注册;基类按方法签名识别路由键
     */
    protected AbstractChannelHandler(JsonMapper jsonMapper, Validator validator, List<?> handlers) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
        for (Object h : handlers) {
            scanOne(h);
        }
        logRoutingTable();
    }

    /**
     * 把指定实例上的方法级注解纳入路由表。<b>仅允许在 {@code @PostConstruct} 阶段调用</b>,
     * 用于 controller 类"自我注册"。包内同时继承类可见。
     *
     * @param self 通常是 {@code this}
     */
    protected final void registerSelf(Object self) {
        scanOne(self);
        logRoutingTable();
    }

    private synchronized void scanOne(Object handler) {
        Builder b = new Builder(textRoutingTable, byteRoutingTable);
        b.scan(handler);
    }

    private void logRoutingTable() {
        log.info("[{}] 路由表就绪: text={} byte={}",
                channel(), textRoutingTable.size(), byteRoutingTable.size());
    }

    /// 路由表构建器(单次扫一个 handler;复用基类共享 Map)
    private final class Builder {
        private final Map<String, TextInvocation> textRoutingTable;
        private final Map<Integer, ByteInvocation> byteRoutingTable;

        Builder(Map<String, TextInvocation> textRoutingTable,
                Map<Integer, ByteInvocation> byteRoutingTable) {
            this.textRoutingTable = textRoutingTable;
            this.byteRoutingTable = byteRoutingTable;
        }

        void scan(Object handler) {
            Class<?> cls = handler.getClass();
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                AprTextFrame mt = m.getAnnotation(AprTextFrame.class);
                AprByteFrame mb = m.getAnnotation(AprByteFrame.class);
                if (mt != null) {
                    validateTextMethod(m);
                    m.setAccessible(true);
                    putText(mt.value(), new TextInvocation(handler, m, jsonMapper, validator));
                }
                if (mb != null) {
                    validateByteMethod(m);
                    m.setAccessible(true);
                    putByte(mb.value(), new ByteInvocation(handler, m));
                }
            }
        }

        private void putText(String type, TextInvocation inv) {
            TextInvocation old = textRoutingTable.put(type, inv);
            if (old != null) {
                throw new IllegalStateException("重复的 @AprTextFrame(\"" + type + "\"): "
                        + describe(old) + " 与 " + describe(inv));
            }
        }

        private void putByte(int frameType, ByteInvocation inv) {
            ByteInvocation old = byteRoutingTable.put(frameType, inv);
            if (old != null) {
                throw new IllegalStateException("重复的 @AprByteFrame(" + frameType + "): "
                        + describe(old) + " 与 " + describe(inv));
            }
        }
    }

    /// 业务方法形参校验
    private static void validateTextMethod(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != 2 || !Channel.class.isAssignableFrom(p[0])) {
            throw new IllegalStateException("@AprTextFrame 方法形参 0 必须是 Channel,实际: "
                    + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
        }
        Parameter[] params = m.getParameters();
        Class<?> second = p[1];
        if (second == String.class) {
            return; // 签名:Channel + 原始 String payload
        }
        if (params[1].isAnnotationPresent(AprPayload.class)) {
            return; // 签名:Channel + @AprPayload SomeRecord
        }
        throw new IllegalStateException("@AprTextFrame 方法形参 1 必须是 String 或 @AprPayload 标注的非 String,实际: "
                + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
    }

    private static void validateByteMethod(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != 2 || !Channel.class.isAssignableFrom(p[0]) || p[1] != AudioFrame.class) {
            throw new IllegalStateException("@AprByteFrame 方法签名必须是 (Channel, AudioFrame),实际: "
                    + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
        }
    }

    private static String describe(TextInvocation inv) {
        return inv.target.getClass().getName() + "#" + inv.method.getName();
    }

    private static String describe(ByteInvocation inv) {
        return inv.target.getClass().getName() + "#" + inv.method.getName();
    }

    /// 文本帧调用单元
    private static final class TextInvocation {
        final Object target;
        final Method method;
        final Parameter payloadParam; // 第二个形参(必非空)
        final JsonMapper jsonMapper;
        final Validator validator;

        TextInvocation(Object target, Method method, JsonMapper jsonMapper, Validator validator) {
            this.target = target;
            this.method = method;
            this.payloadParam = method.getParameters()[1];
            this.jsonMapper = jsonMapper;
            this.validator = validator;
        }

        /**
         * 分发文本帧
         *
         * @param ch      通道
         * @param payload 完整帧 JSON
         *
         * @throws PayloadValidationException 形参校验失败(载荷解析失败 / Bean Validation 不通过)
         */
        void invoke(Channel ch, String payload) throws Exception {
            Object[] args;
            if (payloadParam.getType() == String.class) {
                args = new Object[]{ch, payload};
            } else {
                // @AprPayload 路径:从外层信封 {type,sid,...,payload:{...}} 抽出 payload 字段,
                // 再用 Jackson 反序列化到 dto 类。payload 字段为 null / 缺失,视作 {}。
                Class<?> dtoType = payloadParam.getType();
                String inner = extractPayloadJson(payload, jsonMapper);
                Object dto;
                try {
                    dto = (inner == null) ? jsonMapper.readValue("{}", dtoType) : jsonMapper.readValue(inner, dtoType);
                } catch (Exception ex) {
                    throw new PayloadValidationException("载荷解析失败: " + ex.getMessage());
                }
                Set<? extends ConstraintViolation<Object>> violations = validator.validate(dto);
                if (!violations.isEmpty()) {
                    String detail = violations.stream()
                            .map(v -> {
                                String path = v.getPropertyPath().toString();
                                return (path == null || path.isEmpty()) ? v.getMessage() : path + " " + v.getMessage();
                            })
                            .collect(Collectors.joining("; "));
                    throw new PayloadValidationException(detail);
                }
                args = new Object[]{ch, dto};
            }
            try {
                method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw (e.getCause() instanceof Exception ex) ? ex : new RuntimeException(e.getCause());
            }
        }
    }

    /// 形参校验失败的受检异常,把消息透传给 dispatcher 让它发 error{4400}
    static final class PayloadValidationException extends Exception {
        PayloadValidationException(String message) {
            super(message);
        }
    }

    private record ByteInvocation(Object target, Method method) {
        void invoke(Channel ch, AudioFrame frame) throws Exception {
            try {
                method.invoke(target, ch, frame);
            } catch (InvocationTargetException e) {
                throw (e.getCause() instanceof Exception ex) ? ex : new RuntimeException(e.getCause());
            }
        }
    }

    // ============ 抽象 API ============

    protected abstract String channel();

    protected abstract C newChannel(WebSocketSession session);

    protected void onOpen(C channel) {
    }

    protected void onClose(C channel, CloseStatus status) {
    }

    // ============ 由 AprWebSocketAdapter 调用 ============

    public final void afterEstablished(WebSocketSession session) throws Exception {
        C channel = newChannel(session);
        onOpen(channel);
        if (channel.open()) {
            channel.sendReady();
            log.info("[{}] 连接建立 sid={}", channel(), channel.id());
        }
    }

    public final void handleFrame(WebSocketSession session, WebSocketMessage<?> message) {
        C channel = newChannel(session);
        if (message instanceof TextMessage text) {
            handleText(channel, text.getPayload());
        } else if (message instanceof BinaryMessage binary) {
            handleBinary(channel, binary.getPayload());
        }
    }

    public final void afterClosed(WebSocketSession session, CloseStatus status) {
        onClose(newChannel(session), status);
        log.info("[{}] 连接关闭 sid={} status={}", channel(), session.getId(), status);
    }

    public final void handleTransportError(WebSocketSession session, Throwable error) {
        log.warn("[{}] 传输异常 sid={} : {}", channel(), session.getId(), error.getMessage());
    }

    // ============ 帧分派 ============

    private void handleText(C channel, String payloadJson) {
        String type = readType(payloadJson);
        if (type == null) {
            channel.sendError(4400, "JSON 解析失败", false);
            return;
        }
        TextInvocation inv = textRoutingTable.get(type);
        if (inv == null) {
            log.info("[{}] 未匹配文本帧策略 sid={} type={}", channel(), channel.id(), type);
            channel.sendError(4401, "未知报文类型 " + type, false);
            return;
        }
        invokeText(channel, inv, payloadJson);
    }

    private void handleBinary(C channel, ByteBuffer payload) {
        Optional<AudioFrame> parsed = parseAudioFrame(payload);
        if (parsed.isEmpty()) {
            channel.sendError(4400, "二进制帧非法(magic / payloadLen 不匹配)", false);
            return;
        }
        AudioFrame frame = parsed.get();
        ByteInvocation inv = byteRoutingTable.get(frame.frameType());
        if (inv == null) {
            log.info("[{}] 未匹配二进制帧策略 sid={} frameType={}", channel(), channel.id(), frame.frameType());
            channel.sendError(4400, "不支持的帧类型 " + frame.frameType(), false);
            return;
        }
        invokeByte(channel, inv, frame);
    }

    private void invokeText(C channel, TextInvocation inv, String payload) {
        try {
            inv.invoke(channel, payload);
        } catch (PayloadValidationException ex) {
            log.warn("[{}] 形参校验失败 sid={} type={} : {}", channel(), channel.id(), readType(payload), ex.getMessage());
            channel.sendError(4400, ex.getMessage(), false);
        } catch (Exception ex) {
            log.warn("[{}] 文本帧处理器异常 sid={} type={} : {}", channel(), channel.id(), readType(payload), ex.getMessage());
            channel.sendError(4400, "处理失败: " + ex.getMessage(), false);
        }
    }

    private void invokeByte(C channel, ByteInvocation inv, AudioFrame frame) {
        try {
            inv.invoke(channel, frame);
        } catch (Exception ex) {
            log.warn("[{}] 二进制帧处理器异常 sid={} frameType={} : {}",
                    channel(), channel.id(), frame.frameType(), ex.getMessage());
            channel.sendError(4400, "处理失败: " + ex.getMessage(), false);
        }
    }

    // ============ 帧头解析 ============

    private Optional<AudioFrame> parseAudioFrame(ByteBuffer payload) {
        if (payload == null || payload.remaining() < FRAME_HEADER_BYTES) {
            return Optional.empty();
        }
        ByteBuffer buffer = payload.duplicate().order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.get() & 0xFF;
        if (magic != FRAME_MAGIC) {
            return Optional.empty();
        }
        int version = buffer.get() & 0xFF;
        int frameType = buffer.get() & 0xFF;
        int flags = buffer.get() & 0xFF;
        long seq = buffer.getInt() & 0xFFFFFFFFL;
        long tsMs = buffer.getInt() & 0xFFFFFFFFL;
        int streamId = buffer.getShort() & 0xFFFF;
        int payloadLen = buffer.getShort() & 0xFFFF;
        if (payloadLen != buffer.remaining()) {
            return Optional.empty();
        }
        byte[] pcm = new byte[payloadLen];
        buffer.get(pcm);
        return Optional.of(new AudioFrame(version, frameType, flags, seq, tsMs, streamId, pcm));
    }

    private String readType(String json) {
        try {
            Map<?, ?> body = jsonMapper.readValue(json, Map.class);
            Object type = body.get("type");
            return type == null ? null : type.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 从完整帧 JSON 中提取 {@code payload} 字段,转回 JSON 字符串(顺序保持)。
     *
     * @param frameJson  完整帧 JSON({@code null} 或 blank 视为空帧)
     * @param jsonMapper Jackson(显式传入以便静态 {@link TextInvocation} 使用)
     *
     * @return payload 字段的 JSON 字符串;字段为 null / 缺失返回 null(调用方退到 "{}")
     */
    private static String extractPayloadJson(String frameJson, JsonMapper jsonMapper) {
        if (frameJson == null || frameJson.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> body = jsonMapper.readValue(frameJson, Map.class);
            Object inner = body.get("payload");
            if (inner == null) {
                return null;
            }
            return jsonMapper.writeValueAsString(inner);
        } catch (Exception ex) {
            throw new RuntimeException("frame envelope 解析失败: " + ex.getMessage(), ex);
        }
    }
}
