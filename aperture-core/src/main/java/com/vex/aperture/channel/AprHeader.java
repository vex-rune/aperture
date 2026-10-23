package com.vex.aperture.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 {@link AprTextFrame} 路由方法中"协议头单字段"的形参 ——
 * {@code @AprHeader("sessionId")} 表示把 {@link AprFrameHeader#sessionId()} 作为该形参传入。
 *
 * <p>可选值:{@code "type"} / {@code "sessionId"} / {@code "seq"}。
 * 上行帧的 {@code header} 缺失或该字段为空时,形参收到 {@code null}(不会中断路由)。
 *
 * <p>与 {@link AprPayload}(取载荷)、{@link AprHeaders}(取整个协议头)可自由组合,
 * 同一方法内可声明多个 {@code @AprHeader} 取不同字段。
 *
 * <p>形参类型必须能承载字段值 —— {@code String} 收全部三项;{@code Long} 仅允许 {@code "seq"}。
 *
 * @since Aperture 0.5
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AprHeader {

    /**
     * 协议头字段名 — {@code "type"} / {@code "sessionId"} / {@code "seq"}
     *
     * @return 字段名
     */
    String value();
}