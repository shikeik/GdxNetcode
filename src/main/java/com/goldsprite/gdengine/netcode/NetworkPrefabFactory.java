package com.goldsprite.gdengine.netcode;

/**
 * 预制体工厂接口。
 * 在 Server 和 Client 两端注册相同的工厂，Server 调用 spawnWithPrefab 时会通知 Client 使用此工厂自动创建本地副本。
 */
public interface NetworkPrefabFactory {
    /**
     * 创建一个新的 NetworkObject 实例（包括其业务逻辑组件），
     * 此方法在 Server 端和 Client 端都会被调用。
     */
    NetworkObject create();
}
