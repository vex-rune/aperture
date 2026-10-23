package com.vex.aperture.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 {@link AprTextFrame} 路由方法中"整个协议头"的形参 ——
 * 把解析出的 {@link AprFrameHeader} 整体作为该形参传入。
 *
 * <p>与 {@link AprPayload}(取载荷)、{@link AprHeader}(取单个头字段)可自由组合,
 * 但同一方法内 {@link AprHeaders} 形参最多一个。
 *
 * <p>上行帧的 {@code header} 缺失时,形参收到 {@code null}(不会中断路由)。
 *
 * @since Aperture 0.5
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AprHeaders {
}