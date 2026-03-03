package com.goldsprite.gdengine.netcode;

import java.util.ArrayList;
import java.util.List;

/**
 * 挂载在网络实体上的核心枢纽。
 * 负责收集实体下属的 NetworkVariable 的脏状态，并作为 RPC 拦截的分发站。
 */
public class NetworkObject {

    private long networkId;
    
    // 预制体ID，用于 Spawn 包广播时让远端知道用哪个工厂创建实例
    private int prefabId = -1;
    
    // 存储当前实体上所有的状态变量
    private List<NetworkVariable<?>> networkVariables = new ArrayList<>();

    // 存储当前实体挂载的所有网络行为逻辑
    private List<NetworkBehaviour> behaviours = new ArrayList<>();
    
    // 实体拥有者的 clientId（-1 表示 Server 拥有，>= 0 表示对应 Client）
    private int ownerClientId = -1;

    // 权限标记，后续将交由 NetworkManager 管理填充
    public boolean isServer = false;
    public boolean isClient = false;
    public boolean isLocalPlayer = false;

    /**
     * 无参构造，用于预制体工厂创建实例（网络ID将由 NetworkManager 分配）
     */
    public NetworkObject() {
        this.networkId = -1;
    }

    public NetworkObject(long networkId) {
        this.networkId = networkId;
    }

    public long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    public int getPrefabId() {
        return prefabId;
    }

    public void setPrefabId(int prefabId) {
        this.prefabId = prefabId;
    }

    public int getOwnerClientId() {
        return ownerClientId;
    }

    public void setOwnerClientId(int ownerClientId) {
        this.ownerClientId = ownerClientId;
    }

    /**
     * 为实体动态挂载一个网络行为组件
     */
    public void addComponent(NetworkBehaviour behaviour) {
        if (!behaviours.contains(behaviour)) {
            behaviours.add(behaviour);
            // 将自己作为宿主绑定给逻辑组件，内部会自动触发反射注册变量
            behaviour.internalAttach(this);
        }
    }

    /**
     * 获取当前实体上所有的状态变量（按注册顺序索引）
     */
    public List<NetworkVariable<?>> getNetworkVariables() {
        return networkVariables;
    }

    /**
     * 获取当前实体挂载的所有网络行为逻辑组件
     */
    public List<NetworkBehaviour> getBehaviours() {
        return behaviours;
    }

    /**
     * 获取指定行为组件在列表中的索引位置（用于 RPC 封包定位）
     */
    public int getBehaviourIndex(NetworkBehaviour behaviour) {
        return behaviours.indexOf(behaviour);
    }

    /**
     * 业务层通过此方法将自动同步变量交给 NetworkObject 托管。
     */
    public void registerVariable(NetworkVariable<?> variable) {
        if (variable != null && !networkVariables.contains(variable)) {
            networkVariables.add(variable);
        }
    }

    /**
     * 引擎核心将每帧执行（类似于 LateUpdate）。
     * 清点旗下所有的包裹，如果有人触发异动（Dirty），则表明它必须向上级提交同步打包任务。
     * @return 当前对象上发生更改了的脏变量数量
     */
    public int countDirtyVariables() {
        int dirtyCount = 0;
        for (NetworkVariable<?> var : networkVariables) {
            if (var.isDirty()) {
                dirtyCount++;
            }
        }
        return dirtyCount;
    }

    /**
     * 打包完毕后的状态重置清场。
     */
    public void clearAllDirtyVariables() {
        for (NetworkVariable<?> var : networkVariables) {
            var.clearDirty();
        }
    }
}
