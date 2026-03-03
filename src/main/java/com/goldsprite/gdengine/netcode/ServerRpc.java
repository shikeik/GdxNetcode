package com.goldsprite.gdengine.netcode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 ServerRpc (客户端请求服务端执行)。
 * 注意：挂载该注解的方法通常需要以 request 开头，以表明这是一个请求，而非立刻本地执行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME) // 运行时须要保留，以供反射获取
public @interface ServerRpc {
}
