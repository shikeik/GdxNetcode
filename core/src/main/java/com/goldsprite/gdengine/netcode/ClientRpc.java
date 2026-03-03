package com.goldsprite.gdengine.netcode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 ClientRpc (服务端广播给所有客户端，或定向发给某些客户端执行)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME) 
public @interface ClientRpc {
}
