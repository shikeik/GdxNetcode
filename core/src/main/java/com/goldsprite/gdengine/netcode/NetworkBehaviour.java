package com.goldsprite.gdengine.netcode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * 所有联机业务逻辑的基类 (类似 Unity 的 NetworkBehaviour)。
 * 当它被挂载到 NetworkObject 后，会自动通过反射将其带有的 NetworkVariable 字段注册给 NetworkObject 进行管理，
 * 并预扫描所有 @ServerRpc/@ClientRpc 方法缓存到 rpcMethodCache 中，避免每次 RPC 调用都反射查找。
 */
public abstract class NetworkBehaviour {

    private NetworkObject networkObject;
    
    // 所属的 NetworkManager（由 NetworkObject 或外部绑定）
    private NetworkManager manager;

    // RPC 方法缓存：方法名 -> Method 对象（在 internalAttach 时一次性扫描并缓存）
    private final Map<String, Method> rpcMethodCache = new HashMap<>();

    public void internalAttach(NetworkObject parentObject) {
        this.networkObject = parentObject;
        autoRegisterNetworkVariables();
        cacheRpcMethods();
    }

    public NetworkObject getNetworkObject() {
        return networkObject;
    }
    
    public void setManager(NetworkManager manager) {
        this.manager = manager;
    }
    
    public NetworkManager getManager() {
        return manager;
    }

    public boolean isServer() {
        return networkObject != null && networkObject.isServer;
    }

    public boolean isClient() {
        return networkObject != null && networkObject.isClient;
    }

    public boolean isLocalPlayer() {
        return networkObject != null && networkObject.isLocalPlayer;
    }

    /**
     * 业务层调用：向 Server 端发送 RPC 请求。
     * 参数会被序列化为字节流通过 Transport 发给 Server，Server 端会反射执行对应方法。
     */
    public void sendServerRpc(String methodName, Object... args) {
        if (manager == null || networkObject == null) {
            throw new IllegalStateException("NetworkBehaviour 未绑定到 Manager，无法发送 RPC");
        }
        int behaviourIndex = networkObject.getBehaviourIndex(this);
        manager.sendRpcPacket(0x20, (int) networkObject.getNetworkId(), behaviourIndex, methodName, args);
    }

    /**
     * 业务层调用：向所有 Client 端广播 RPC 调用。
     * 参数会被序列化为字节流通过 Transport 广播给所有客户端，客户端反射执行对应方法。
     */
    public void sendClientRpc(String methodName, Object... args) {
        if (manager == null || networkObject == null) {
            throw new IllegalStateException("NetworkBehaviour 未绑定到 Manager，无法发送 RPC");
        }
        int behaviourIndex = networkObject.getBehaviourIndex(this);
        manager.sendRpcPacket(0x21, (int) networkObject.getNetworkId(), behaviourIndex, methodName, args);
    }

    /**
     * 利用反射自动收集当前业务逻辑子类中定义的所有 NetworkVariable，
     * 并将其托管给上级的 NetworkObject。
     * <p>
     * 关键: 按字段名字母序排序后再注册，保证不同 JVM 实现（HotSpot / Android ART）
     * 返回相同的 varIndex 映射。{@code Class.getDeclaredFields()} 的顺序在 JVM 规范中
     * 是未定义的，Android ART 和桌面 HotSpot 可能返回不同顺序，导致 varIndex 错位，
     * 所有同步数据交叉赋值（HP=几千万、颜色乱闪等）。
     */
    private void autoRegisterNetworkVariables() {
        Class<?> clazz = this.getClass();
        Field[] fields = clazz.getDeclaredFields();

        // 按字段名字母序排序，保证跨平台（PC HotSpot / Android ART）一致的注册顺序
        Arrays.sort(fields, Comparator.comparing(Field::getName));

        for (Field f : fields) {
            if (NetworkVariable.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try {
                    NetworkVariable<?> var = (NetworkVariable<?>) f.get(this);
                    if (var != null) {
                        networkObject.registerVariable(var);
                    }
                } catch (IllegalAccessException e) {
                    System.err.println("[NetworkBehaviour] 反射访问 NetworkVariable 失败: " + f.getName());
                }
            }
        }
    }

    /**
     * 在 attach 时一次性扫描所有 @ServerRpc 和 @ClientRpc 方法，缓存到 rpcMethodCache 中。
     * 之后 RPC 调度只需 Map.get(methodName)，无需每次 getDeclaredMethod 反射查找。
     */
    private void cacheRpcMethods() {
        for (Method m : this.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(ServerRpc.class) || m.isAnnotationPresent(ClientRpc.class)) {
                m.setAccessible(true);
                rpcMethodCache.put(m.getName(), m);
            }
        }
    }

    /**
     * 从预缓存中获取 RPC 方法。供 NetworkManager.handleRpcPacket() 调用，
     * 替代原来每次 getDeclaredMethod 的反射查找。
     * @param methodName RPC 方法名
     * @return 缓存的 Method 对象，未找到返回 null
     */
    public Method getCachedRpcMethod(String methodName) {
        return rpcMethodCache.get(methodName);
    }
}
