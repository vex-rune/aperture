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
 * <p>文本帧协议为 {@code type + "\n" + json}:首行是路由键,换行之后是载荷,上下行同格式。
 *
 * <p>业务方法签名(除首参 {@code Channel} 外,<b>恰有一个</b>载荷形参,二选一):
 * <ul>
 *   <li>{@code void onXxx(Channel c, String payloadJson)} — 载荷 JSON 原文;</li>
 *   <li>{@code void onXxx(Channel c, @AprPayload SomeRecord req)} — 经 Jackson 反序列化 + Bean Validation。</li>
 * </ul>
 * 二进制帧路由方法签名固定为 {@code void onXxx(Channel c, BinaryFrame frame)}。
 *
 * <p>启动期反射建路由表;同一 {@code @AprTextFrame("type")} / {@code @AprByteFrame(N)} 不可重复,
 * 重复直接 {@code IllegalStateException} 拒启动。
 *
 * @since Aperture 0.2
 */
@Slf4j
public abstract class AbstractChannelHandler<C extends Channel> {

    /// 能力位 —— 文本帧 {@code type\n{json}}
    public static final String TEXT_FRAME_V1 = "text_frame_v1";

    /// 能力位 —— 二进制帧首字节作frameType
    public static final String BINARY_FRAME_V1 = "binary_first_byte_v1";

    /// 能力位 —— 服务端心跳探测 + 超时关闭
    public static final String HEARTBEAT_V1 = "heartbeat_v1";

    /// 能力位 —— 客户端主动心跳(上行 heartbeat.ping,下行 heartbeat.pong)
    public static final String CLIENT_HEARTBEAT_V1 = "client_heartbeat_v1";

    /// 能力位 —— 帧大小限制
    public static final String FRAME_LIMIT_V1 = "frame_limit_v1";

    /// 框架内置报文类型 —— 客户端心跳请求
    public static final String HEARTBEAT_PING = "heartbeat.ping";

    /// 框架内置报文类型 —— 客户端心跳回执
    public static final String HEARTBEAT_PONG = "heartbeat.pong";

    /// 会话属性键 —— 缓存通道实例(每会话只建一次,插件与业务共用同一实例)
    private static final String ATTR_CHANNEL = "apr.channel";

    protected final JsonMapper jsonMapper;
    private final Validator validator;

    /// 路由入口(type / frameType → 调用单元);由 {@link #registerSelf} 在构造后填充
    private final Map<String, TextInvocation> textRoutingTable = new LinkedHashMap<>();
    private final Map<Integer, ByteInvocation> byteRoutingTable = new LinkedHashMap<>();

    /// 帧大小上限;未通过 4 参构造器注入时用默认值
    private ApertureChannelProperties props = new ApertureChannelProperties();

    /**
     * 构造通道处理器(不启用帧大小限制之外的配置注入,取默认上限)
     *
     * @param jsonMapper JSON 序列化器
     * @param validator  Bean Validation 校验器(Spring 容器提供)
     * @param handlers   任意类型 handler bean 集合
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
     * 构造通道处理器 —— Spring bean 推荐用本重载,把 {@link ApertureChannelProperties}
     * 注入进来以启用真实的帧大小上限。
     *
     * @param jsonMapper JSON 序列化器
     * @param validator  Bean Validation 校验器
     * @param handlers   任意类型 handler bean 集合
     * @param props      通道配置(心跳间隔 / 帧大小上限)
     */
    protected AbstractChannelHandler(JsonMapper jsonMapper, Validator validator, List<?> handlers,
                                    ApertureChannelProperties props) {
        this(jsonMapper, validator, handlers);
        if (props != null) {
            this.props = props;
        }
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
            // 空/空白 type 会让上行"未知报文类型 " 变成带尾空格的无效提示,启动期直接拒
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("@AprTextFrame 的 value 不能为空: " + describe(inv));
            }
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
        if (p.length < 1 || !Channel.class.isAssignableFrom(p[0])) {
            throw new IllegalStateException("@AprTextFrame 方法形参 0 必须是 Channel,实际: "
                    + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
        }
        // 除首参外有且仅有一个载荷形参:原始 String JSON 或 @AprPayload DTO
        int rawCount = 0;
        int dtoCount = 0;
        for (int i = 1; i < p.length; i++) {
            if (params[i].getType() == String.class) {
                rawCount++;
            } else if (params[i].isAnnotationPresent(AprPayload.class)) {
                dtoCount++;
            } else {
                throw new IllegalStateException("@AprTextFrame 方法形参 " + i
                        + " 必须是 String(原始 JSON)或 @AprPayload 标注的非 String DTO,实际: "
                        + m.getDeclaringClass().getName() + "#" + m.getName() + Arrays.toString(p));
            }
        }
        if (rawCount + dtoCount != 1) {
            throw new IllegalStateException("@AprTextFrame 方法必须恰有一个载荷形参"
                    + "(String 原始 JSON 或 @AprPayload DTO 二选一),实际: "
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
            for (int i = 1; i < params.length; i++) {
                if (params[i].getType() == String.class) {
                    raw = i;
                } else {
                    dto = i;
                }
            }
            this.rawPayloadIndex = raw;
            this.dtoIndex = dto;
        }

        /**
         * 分发文本帧 —— 按形参声明顺序组装入参:
         * {@code Channel} → 原始 String 载荷(帧内 JSON 原文) / {@code @AprPayload} DTO
         *
         * @param ch        通道
         * @param frameJson 帧载荷 JSON 原文(已剥离 {@code type\n} 前缀)
         *
         * @throws PayloadValidationException 载荷解析失败 / Bean Validation 不通过
         */
        void invoke(Channel ch, String frameJson) throws Exception {
            String rawJson = null;
            Object dto = null;
            if (dtoIndex >= 0) {
                // @AprPayload 路径:帧内载荷整体反序列化到 dto 类。载荷为空视作 {}。
                Class<?> dtoType = params[dtoIndex].getType();
                String inner = (frameJson == null || frameJson.isBlank()) ? "{}" : frameJson;
                try {
                    dto = jsonMapper.readValue(inner, dtoType);
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
                rawJson = frameJson;
            }

            Object[] args = new Object[params.length];
            args[0] = ch;
            for (int i = 1; i < params.length; i++) {
                args[i] = (i == dtoIndex) ? dto : rawJson;
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

    private void handleBinary(C channel, ByteBuffer payload) {
        // 框架层不做任何 magic / 头部结构校验 —— 仅取首字节作为路由键,
        // 剩余字节原样交给业务 controller。这样:
        //   1. 框架不耦合具体协议布局(协议头是业务的事);
        //   2. 业务可以自己定义完整 binary 协议(magic / flags / seq ...),
        //      框架负责把 byte[] 路由到正确的 @AprByteFrame(N) 方法。
        if (payload == null || payload.remaining() < 1) {
            sendError(channel, 4400, "二进制帧为空");
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
            sendError(channel, 4400, "不支持的帧类型 " + frame.frameType());
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
        C channel = currentChannel(session);
        onOpen(channel);
        if (channel.open()) {
            sendReady(channel);
            log.info("[{}] 连接建立 sid={}", channel(), channel.id());
        }
    }

    /// 取出(或懒建)会话对应的通道实例 —— 供适配层广播插件事件时使用
    @SuppressWarnings("unchecked")
    public final C currentChannel(WebSocketSession session) {
        Object cached = session.getAttributes().get(ATTR_CHANNEL);
        if (cached != null) {
            return (C) cached;
        }
        C created = newChannel(session);
        session.getAttributes().put(ATTR_CHANNEL, created);
        return created;
    }

    public final void handleFrame(WebSocketSession session, WebSocketMessage<?> message) {
        C channel = currentChannel(session);
        if (message instanceof TextMessage text) {
            handleText(channel, text.getPayload());
        } else if (message instanceof BinaryMessage binary) {
            handleBinary(channel, binary.getPayload());
        }
    }

    public final void afterClosed(WebSocketSession session, CloseStatus status) {
        onClose(currentChannel(session), status);
        session.getAttributes().remove(ATTR_CHANNEL);
        log.info("[{}] 连接关闭 sid={} status={}", channel(), session.getId(), status);
    }

    public final void handleTransportError(WebSocketSession session, Throwable error) {
        log.warn("[{}] 传输异常 sid={} : {}", channel(), session.getId(), error.getMessage());
    }

    // ============ 帧分派 ============

    /// 下发 session.ready —— 框架协议自带的建连确认帧
    ///
    /// <p>各段上报的都是<b>实际生效</b>的插件配置:某插件被禁用则其能力位与对应段一律不出现,
    /// 客户端据此得知服务端真实边界(心跳节奏、帧上限),用于分片与重连。
    private void sendReady(C channel) {
        ApertureChannelProperties.ServerHeartbeat sh = props.getServerHeartbeat();
        ApertureChannelProperties.ClientHeartbeat ch = props.getClientHeartbeat();
        ApertureChannelProperties.FrameLimit fl = props.getFrameLimit();

        var caps = new ArrayList<String>();
        caps.add(TEXT_FRAME_V1);
        caps.add(BINARY_FRAME_V1);
        if (sh.isEnabled()) {
            caps.add(HEARTBEAT_V1);
        }
        if (ch.isEnabled()) {
            caps.add(CLIENT_HEARTBEAT_V1);
        }
        if (fl.isEnabled()) {
            caps.add(FRAME_LIMIT_V1);
        }

        var payload = Channel.payload("serverCaps", caps);
        if (sh.isEnabled()) {
            payload.put("serverHeartbeat", Channel.payload(
                    "intervalMs", sh.getIntervalMs(),
                    "timeoutMs", sh.getTimeoutMs()));
        }
        if (ch.isEnabled()) {
            payload.put("clientHeartbeat", Channel.payload(
                    "type", HEARTBEAT_PING,
                    "timeoutMs", ch.getTimeoutMs()));
        }
        if (fl.isEnabled()) {
            var limits = Channel.payload();
            if (fl.getMaxTextBytes() > 0) {
                limits.put("maxTextFrameBytes", fl.getMaxTextBytes());
            }
            if (fl.getMaxBinaryBytes() > 0) {
                limits.put("maxBinaryFrameBytes", fl.getMaxBinaryBytes());
            }
            if (!limits.isEmpty()) {
                payload.put("limits", limits);
            }
        }
        channel.sendObject("session.ready", payload);
    }

    /// 下发 error 帧
    private void sendError(C channel, int code, String message) {
        channel.sendObject("error", Channel.payload("code", code, "message", message, "fatal", false));
    }

    /// 框架内置路由 —— 先于业务路由表命中,业务无需注册即可使用
    private boolean invokeBuiltin(C channel, String type) {
        if (!HEARTBEAT_PING.equals(type)) {
            return false;
        }
        // 客户端心跳:仅回执,不进业务路由表。客户端即便不发,服务端心跳仍独立工作。
        channel.sendObject(HEARTBEAT_PONG, Channel.payload());
        return true;
    }

    private void handleText(C channel, String frameText) {
        TypeError err = checkRoutable(frameText);
        if (err != null) {
            sendError(channel, err.code(), err.message());
            return;
        }
        String type = readType(frameText);
        if (invokeBuiltin(channel, type)) {
            return;
        }
        TextInvocation inv = textRoutingTable.get(type);
        if (inv == null) {
            log.info("[{}] 未匹配文本帧策略 sid={} type={}", channel(), channel.id(), type);
            sendError(channel, 4401, "未知报文类型 " + type);
            return;
        }
        invokeText(channel, inv, type, readPayload(frameText));
    }

    private void invokeByte(C channel, ByteInvocation inv, BinaryFrame frame) {
        try {
            inv.invoke(channel, frame);
        } catch (Exception ex) {
            log.warn("[{}] 二进制帧处理器异常 sid={} frameType={} : {}",
                    channel(), channel.id(), frame.frameType(), ex.getMessage());
            sendError(channel, 4400, "处理失败: " + ex.getMessage());
        }
    }

    private void invokeText(C channel, TextInvocation inv, String type, String payload) {
        try {
            inv.invoke(channel, payload);
        } catch (PayloadValidationException ex) {
            log.warn("[{}] 形参校验失败 sid={} type={} : {}", channel(), channel.id(), type, ex.getMessage());
            sendError(channel, 4400, ex.getMessage());
        } catch (Exception ex) {
            log.warn("[{}] 文本帧处理器异常 sid={} type={} : {}", channel(), channel.id(), type, ex.getMessage());
            sendError(channel, 4400, "处理失败: " + ex.getMessage());
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
    // Aperture 0.5 起,框架不再解析任何协议头 —— 文本帧第一行即路由键,
    // 二进制路由仅取首字节作为 frameType,业务 controller 自决 magic / flags / seq 等字段布局。

    /// 路由前置检查失败的原因(区分"分帧失败"与"type 空",避免回一句带尾空格的
    /// "未知报文类型" 让客户端无法定位)
    private record TypeError(int code, String message) {
    }

    /**
     * 拆出文本帧的报文类型 —— 协议为 {@code type + "\n" + json},
     * 第一行是路由键,其余是载荷 JSON 原文。
     *
     * @param frameText 完整帧文本
     *
     * @return 报文类型;无分隔符或类型段为空返回 null
     */
    private static String readType(String frameText) {
        if (frameText == null || frameText.isEmpty()) {
            return null;
        }
        int nl = frameText.indexOf('\n');
        if (nl <= 0) {
            return null;
        }
        String type = frameText.substring(0, nl);
        return type.isBlank() ? null : type;
    }

    /**
     * 取出文本帧的载荷 JSON 原文 —— 第一个换行之后的部分。
     *
     * @param frameText 完整帧文本
     *
     * @return 载荷原文;无分隔符返回 null
     */
    private static String readPayload(String frameText) {
        int nl = frameText.indexOf('\n');
        return (nl < 0) ? null : frameText.substring(nl + 1);
    }

    /**
     * 检查文本帧能否取出路由键
     *
     * @param frameText 完整帧文本
     *
     * @return 失败原因;能正常路由时返回 null
     */
    private static TypeError checkRoutable(String frameText) {
        String type = readType(frameText);
        if (type == null) {
            return new TypeError(4400, "缺少换行分隔符或报文类型为空");
        }
        return null;
    }
}
