package com.vex.aperture.channel;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aperture 通道处理器基类;类比 Spring {@code @RestController} —— 一个 handler 实例绑定一条
 * WebSocket URL,业务方法用 {@link AprTextFrame} / {@link AprByteFrame} <b>方法级</b>注解声明路由。
 *
 * <p>不再直接 {@code implements WebSocketHandler};由 {@link com.vex.aperture.channel.spi.AprWebSocketAdapter}
 * 转发 Spring 协议回调到本类的 {@link #afterEstablished}/{@link #handleFrame}/{@link #afterClosed},
 * 配合 {@link com.vex.aperture.channel.spi.AprControllerRegistry} 做 {@code @AprController}
 * 自动路由注册。
 *
 * <p>业务方法签名(除首参 {@code Channel} 外,其余形参任意组合):
 * <ul>
 *   <li>{@code void onXxx(Channel c, String payloadJson)} — 原始 JSON 载荷;</li>
 *   <li>{@code void onXxx(Channel c, @AprPayload SomeRecord req)} — 经 Jackson 反序列化 + Bean Validation;</li>
 *   <li>{@code void onXxx(Channel c, @AprHeader("sessionId") String sid)} — 协议头单字段({@code type} / {@code sessionId} / {@code seq});</li>
 *   <li>{@code void onXxx(Channel c, @AprHeaders AprFrameHeader header)} — 整个协议头;</li>
 *   <li>{@code void onXxx(Channel c, BinaryFrame frame)} — 仅二进制帧路由。</li>
 * </ul>
 *
 * <p>启动期反射建路由表;同一 {@code @AprTextFrame("type")} / {@code @AprByteFrame(N)} 不可重复,
 * 重复直接 {@code IllegalStateException} 拒启动。
 *
 * @since Aperture 0.2
 */
@Slf4j
public abstract class AbstractChannelHandler<C extends Channel> {

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
        Parameter[] params = m.getParameters();
        if (p.length < 2 || !Channel.class.isAssignableFrom(p[0])) {
            throw new IllegalStateException("@AprTextFrame 方法形参 0 必须是 Channel,实际: "
                    + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
        }
        int rawCount = 0;
        int dtoCount = 0;
        for (int i = 1; i < p.length; i++) {
            AprHeader single = params[i].getAnnotation(AprHeader.class);
            AprHeaders whole = params[i].getAnnotation(AprHeaders.class);
            if (single != null && whole != null) {
                throw new IllegalStateException("@AprHeader 与 @AprHeaders 不能标注在同一形参: "
                        + m.getDeclaringClass().getName() + "#" + m.getName());
            }
            if (whole != null) {
                if (p[i] != AprFrameHeader.class) {
                    throw new IllegalStateException("@AprHeaders 形参类型必须是 AprFrameHeader,实际: "
                            + m.getDeclaringClass().getName() + "#" + m.getName() + " -> " + p[i].getName());
                }
                continue;
            }
            if (single != null) {
                String field = single.value();
                if (!"type".equals(field) && !"sessionId".equals(field) && !"seq".equals(field)) {
                    throw new IllegalStateException("@AprHeader(\"" + field + "\") 不是合法协议头字段(可选 type / sessionId / seq): "
                            + m.getDeclaringClass().getName() + "#" + m.getName());
                }
                if ("seq".equals(field) && p[i] != Long.class && p[i] != long.class) {
                    throw new IllegalStateException("@AprHeader(\"seq\") 形参类型必须是 Long,实际: "
                            + m.getDeclaringClass().getName() + "#" + m.getName() + " -> " + p[i].getName());
                }
                if (!"seq".equals(field) && p[i] != String.class) {
                    throw new IllegalStateException("@AprHeader(\"" + field + "\") 形参类型必须是 String,实际: "
                            + m.getDeclaringClass().getName() + "#" + m.getName() + " -> " + p[i].getName());
                }
                continue;
            }
            // 未标注的形参:只能是原始 String 载荷 或 @AprPayload DTO,两者各至多一个
            if (p[i] == String.class) {
                rawCount++;
                continue;
            }
            if (params[i].isAnnotationPresent(AprPayload.class)) {
                dtoCount++;
                continue;
            }
            throw new IllegalStateException("@AprTextFrame 方法未标注形参 " + i + " 必须是 String 或 @AprPayload 标注的非 String,实际: "
                    + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
        }
        if (rawCount + dtoCount > 1) {
            throw new IllegalStateException("@AprTextFrame 方法的载荷形参(原始 String / @AprPayload)至多一个,实际: "
                    + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
        }
    }

    private static void validateByteMethod(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != 2 || !Channel.class.isAssignableFrom(p[0]) || p[1] != BinaryFrame.class) {
            throw new IllegalStateException("@AprByteFrame 方法签名必须是 (Channel, BinaryFrame),实际: "
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
        final Parameter[] params;
        /// 原始载荷形参下标;无则 -1
        final int rawPayloadIndex;
        /// @AprPayload DTO 形参下标;无则 -1
        final int dtoIndex;
        /// @AprHeader 形参下标;无则 -1
        final int[] singleHeaderIdx;
        /// 与 singleHeaderIdx 一一对应的字段名
        final String[] singleHeaderFields;
        /// @AprHeaders 形参下标;无则 -1
        final int wholeHeaderIndex;
        final JsonMapper jsonMapper;
        final Validator validator;

        TextInvocation(Object target, Method method, JsonMapper jsonMapper, Validator validator) {
            this.target = target;
            this.method = method;
            this.params = method.getParameters();
            this.jsonMapper = jsonMapper;
            this.validator = validator;
            int raw = -1;
            int dto = -1;
            int whole = -1;
            List<Integer> singles = new ArrayList<>();
            List<String> fields = new ArrayList<>();
            for (int i = 1; i < params.length; i++) {
                AprHeader h = params[i].getAnnotation(AprHeader.class);
                if (h != null) {
                    singles.add(i);
                    fields.add(h.value());
                    continue;
                }
                if (params[i].isAnnotationPresent(AprHeaders.class)) {
                    whole = i;
                    continue;
                }
                if (params[i].getType() == String.class) {
                    raw = i;
                } else {
                    dto = i;
                }
            }
            this.rawPayloadIndex = raw;
            this.dtoIndex = dto;
            this.singleHeaderIdx = singles.stream().mapToInt(Integer::intValue).toArray();
            this.singleHeaderFields = fields.toArray(new String[0]);
            this.wholeHeaderIndex = whole;
        }

        /**
         * 分发文本帧 —— 按形参声明顺序组装入参:
         * {@code Channel} → {@code @AprHeader} / {@code @AprHeaders} → 原始 String 载荷 / {@code @AprPayload} DTO
         *
         * @param ch       通道
         * @param frameJson 完整帧 JSON
         *
         * @throws PayloadValidationException 载荷解析失败 / Bean Validation 不通过
         */
        void invoke(Channel ch, String frameJson) throws Exception {
            Map<?, ?> envelope = parseEnvelope(frameJson, jsonMapper);
            AprFrameHeader header = AprFrameHeader.from(envelope.get("header"));

            String rawJson = null;
            Object dto = null;
            if (dtoIndex >= 0) {
                // @AprPayload 路径:从信封抽出 payload 字段,再用 Jackson 反序列化到 dto 类。
                // payload 字段为 null / 缺失,视作 {}。
                Class<?> dtoType = params[dtoIndex].getType();
                String inner = writePayload(envelope.get("payload"), jsonMapper);
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
            } else if (rawPayloadIndex >= 0) {
                rawJson = writePayload(envelope.get("payload"), jsonMapper);
            }

            Object[] args = new Object[params.length];
            args[0] = ch;
            for (int i = 1; i < params.length; i++) {
                int singleAt = indexOf(singleHeaderIdx, i);
                if (singleAt >= 0) {
                    String field = singleHeaderFields[singleAt];
                    args[i] = (header == null) ? null : switch (field) {
                        case "type" -> header.type();
                        case "sessionId" -> header.sessionId();
                        default -> header.seq();
                    };
                } else if (i == wholeHeaderIndex) {
                    args[i] = header;
                } else if (i == dtoIndex) {
                    args[i] = dto;
                } else {
                    args[i] = rawJson;
                }
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

    /// 在下标数组中找形参位置;不存在返回 -1
    private static int indexOf(int[] idx, int target) {
        for (int i = 0; i < idx.length; i++) {
            if (idx[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private void handleBinary(C channel, ByteBuffer payload) {
        // 框架层不做任何 magic / 头部结构校验 —— 仅取首字节作为路由键,
        // 剩余字节原样交给业务 controller。这样:
        //   1. 框架不耦合具体协议布局(协议头是业务的事);
        //   2. 业务可以自己定义完整 binary 协议(magic / flags / seq ...),
        //      框架负责把 byte[] 路由到正确的 @AprByteFrame(N) 方法。
        if (payload == null || payload.remaining() < 1) {
            channel.sendError(4400, "二进制帧为空", false);
            return;
        }
        ByteBuffer dup = payload.duplicate();
        int frameType = dup.get() & 0xFF;
        byte[] rest = new byte[dup.remaining()];
        dup.get(rest);
        BinaryFrame frame = new BinaryFrame(frameType, rest);

        ByteInvocation inv = byteRoutingTable.get(frame.frameType());
        if (inv == null) {
            log.info("[{}] 未匹配二进制帧策略 sid={} frameType={}", channel(), channel.id(), frame.frameType());
            channel.sendError(4400, "不支持的帧类型 " + frame.frameType(), false);
            return;
        }
        invokeByte(channel, inv, frame);
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
        String type = readType(payloadJson, jsonMapper);
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

    private void invokeByte(C channel, ByteInvocation inv, BinaryFrame frame) {
        try {
            inv.invoke(channel, frame);
        } catch (Exception ex) {
            log.warn("[{}] 二进制帧处理器异常 sid={} frameType={} : {}",
                    channel(), channel.id(), frame.frameType(), ex.getMessage());
            channel.sendError(4400, "处理失败: " + ex.getMessage(), false);
        }
    }

    private void invokeText(C channel, TextInvocation inv, String payload) {
        try {
            inv.invoke(channel, payload);
        } catch (PayloadValidationException ex) {
            log.warn("[{}] 形参校验失败 sid={} type={} : {}", channel(), channel.id(), readType(payload, jsonMapper), ex.getMessage());
            channel.sendError(4400, ex.getMessage(), false);
        } catch (Exception ex) {
            log.warn("[{}] 文本帧处理器异常 sid={} type={} : {}", channel(), channel.id(), readType(payload, jsonMapper), ex.getMessage());
            channel.sendError(4400, "处理失败: " + ex.getMessage(), false);
        }
    }

    private record ByteInvocation(Object target, Method method) {
        void invoke(Channel ch, BinaryFrame frame) throws Exception {
            try {
                method.invoke(target, ch, frame);
            } catch (InvocationTargetException e) {
                throw (e.getCause() instanceof Exception ex) ? ex : new RuntimeException(e.getCause());
            }
        }
    }

    // ============ 帧头解析 ============
    // Aperture 0.5 起,框架不再解析任何协议头 —— 二进制路由仅取首字节作为 frameType,
    // 业务 controller 自决 magic / flags / seq 等字段布局。

    /**
     * 解析文本帧信封为顶层 Map;JSON 非法返回空 Map
     *
     * @param frameJson  完整帧 JSON
     * @param jsonMapper Jackson
     *
     * @return 信封顶层字段;解析失败返回空 Map
     */
    private static Map<?, ?> parseEnvelope(String frameJson, JsonMapper jsonMapper) {
        if (frameJson == null || frameJson.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(frameJson, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /**
     * 读 {@code header.type} 作为文本帧路由键
     *
     * @param frameJson  完整帧 JSON
     * @param jsonMapper Jackson
     *
     * @return 报文类型;信封非法 / header 缺失 / type 为空返回 null
     */
    private static String readType(String frameJson, JsonMapper jsonMapper) {
        AprFrameHeader header = AprFrameHeader.from(parseEnvelope(frameJson, jsonMapper).get("header"));
        return header == null ? null : header.type();
    }

    /**
     * 把 {@code payload} 字段转回 JSON 字符串(顺序保持)。
     *
     * @param payload    payload 段原始值
     * @param jsonMapper Jackson
     *
     * @return payload 字段的 JSON 字符串;字段为 null / 缺失返回 null(调用方退到 "{}")
     */
    private static String writePayload(Object payload, JsonMapper jsonMapper) {
        if (payload == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new RuntimeException("payload 序列化失败: " + ex.getMessage(), ex);
        }
    }
}
