package com.vex.aperture.channel;

/**
 * 统一响应结构体 —— Aperture 下行报文的标准载荷外壳。
 *
 * <p>{@code code} 与 {@code message} 描述结果本身(状态码 / 状态短语),
 * {@code data} 携带业务数据;所有字段均 {@code String} —— 错误码也以字符串下发,
 * 避免客户端为"数字型错误码"与"字符串型状态码"分叉处理。
 *
 * <p>错误帧的默认载荷即本结构(见
 * {@code AbstractChannelHandler#errorPayload});业务也可直接拿它当任何下行报文的
 * 载荷,与 HTTP 侧的统一响应体对齐口径。
 *
 * @param <T> 业务数据类型
 */
public record Response<T>(String code, String message, T data) {

    /** 成功响应码 */
    public static final String ok = "ok";

    /** 错误码 —— 请求非法:缺换行分隔符 / 首行为空、载荷 JSON 解析失败、@AprPayload 校验不通过 */
    public static final String BAD_REQUEST = "bad_request";

    /** 错误码 —— 首行路由键未注册到任何 @AprTextFrame,或二进制 frameType 未注册 */
    public static final String NOT_FOUND = "not_found";

    /** 错误码 —— 业务方法抛异常 */
    public static final String INTERNAL = "internal";

    /** 错误码 —— 帧超限(由帧限制插件下发,随后以 1009 关闭连接) */
    public static final String FRAME_TOO_LARGE = "frame_too_large";

    /** 错误码 —— 客户端心跳超时(由客户端心跳插件下发,随后以 1001 关闭连接) */
    public static final String HEARTBEAT_TIMEOUT = "heartbeat_timeout";

    /**
     * 成功响应
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     *
     * @return {@code code="ok"} 的成功响应
     */
    public static <T> Response<T> ok(T data) {
        return new Response<>(ok, ok, data);
    }

    /**
     * 成功响应(无数据)
     *
     * @param <T> 业务数据类型
     *
     * @return {@code code="ok"} 的成功响应
     */
    public static <T> Response<T> ok() {
        return ok(null);
    }

    /**
     * 错误响应
     *
     * @param code    错误码
     * @param message 状态短语
     * @param <T>     业务数据类型
     *
     * @return 错误响应
     */
    public static <T> Response<T> error(String code, String message) {
        return new Response<>(code, message, null);
    }
}
