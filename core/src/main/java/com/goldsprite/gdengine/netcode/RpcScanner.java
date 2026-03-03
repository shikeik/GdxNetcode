package com.goldsprite.gdengine.netcode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Rpc 扫描器。用于在 NetworkBehaviour 注册时，通过反射预先缓存所有的 RPC 方法引用。
 */
public class RpcScanner {

    public static List<Method> getServerRpcs(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        // 获取包括私有在内的所有声明方法（但暂不上溯父类，按需可改）
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ServerRpc.class)) {
                m.setAccessible(true); // 保证能调用私有/保护的 RPC
                methods.add(m);
            }
        }
        return methods;
    }

    public static List<Method> getClientRpcs(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ClientRpc.class)) {
                m.setAccessible(true);
                methods.add(m);
            }
        }
        return methods;
    }
}
