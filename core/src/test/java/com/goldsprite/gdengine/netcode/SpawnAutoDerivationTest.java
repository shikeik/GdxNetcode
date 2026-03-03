package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

/**
 * TDD 驱动：验证 Server 调用 spawnWithPrefab 后，Client 能自动收到 SpawnPacket 并在本地派生实体，
 * 彻底消除之前依赖 java.lang.reflect 强行注入 networkObjects 的黑魔法。
 */
@RunWith(GdxTestRunner.class)
public class SpawnAutoDerivationTest {

    // ======= 测试用的预制体行为 =======
    private static class TankBehaviour extends NetworkBehaviour {
        public NetworkVariable<Float> x = new NetworkVariable<>(0f);
        public NetworkVariable<Float> y = new NetworkVariable<>(0f);
        public NetworkVariable<Integer> hp = new NetworkVariable<>(4);
    }

    /**
     * 测试1：Server 调用 spawnWithPrefab 后，Client 通过预制体工厂自动派生出实体，不需要任何反射 Hack。
     */
    @Test
    public void testServerSpawnAutoDerivesToClient() {
        System.out.println("======= [TDD-RED] 开始 Spawn 自动派生测试 =======");

        // ---------- 1. 搭建双端 ----------
        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        // ---------- 2. 双端注册同一个预制体工厂 ----------
        int TANK_PREFAB_ID = 1;
        NetworkPrefabFactory tankFactory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new TankBehaviour());
            return obj;
        };
        serverManager.registerPrefab(TANK_PREFAB_ID, tankFactory);
        clientManager.registerPrefab(TANK_PREFAB_ID, tankFactory);

        // ---------- 3. Server 端通过预制体 Spawn ----------
        NetworkObject serverTank = serverManager.spawnWithPrefab(TANK_PREFAB_ID);
        CLogAssert.assertTrue("Server 端应成功创建预制体实体", serverTank != null);

        int assignedNetId = (int) serverTank.getNetworkId();
        System.out.println("Server 分配的网络ID: " + assignedNetId);

        // 此刻 Spawn 包应该已经被即时广播到了客户端
        // Client 端应该已经自动派生出了对应实体
        NetworkObject clientTank = clientManager.getNetworkObject(assignedNetId);
        CLogAssert.assertTrue("Client 端应自动派生出与 Server 同 ID 的实体（无需反射 Hack）", clientTank != null);

        System.out.println("======= [TDD] Spawn 自动派生基础验证通过 =======");
    }

    /**
     * 测试2：Spawn 后初始状态变量在首帧 tick 中自动同步给客户端。
     */
    @Test
    public void testSpawnedEntityInitialStateSync() {
        System.out.println("======= [TDD-RED] 开始 Spawn+初始同步 联合测试 =======");

        // ---------- 搭建 ----------
        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        int TANK_PREFAB_ID = 1;
        serverManager.registerPrefab(TANK_PREFAB_ID, () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new TankBehaviour());
            return obj;
        });
        clientManager.registerPrefab(TANK_PREFAB_ID, () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new TankBehaviour());
            return obj;
        });

        // ---------- Server Spawn 并修改状态 ----------
        NetworkObject serverTank = serverManager.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();

        // 获取 Server 端的行为组件并修改状态
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);
        serverLogic.x.setValue(150f);
        serverLogic.y.setValue(250f);
        serverLogic.hp.setValue(3);

        // ---------- 执行 tick 推送同步数据 ----------
        serverManager.tick();

        // ---------- Client 验证 ----------
        NetworkObject clientTank = clientManager.getNetworkObject(netId);
        CLogAssert.assertTrue("Client 应存在自动派生的实体", clientTank != null);

        TankBehaviour clientLogic = (TankBehaviour) clientTank.getBehaviours().get(0);
        CLogAssert.assertEquals("Client X 坐标应与 Server 同步", 150f, clientLogic.x.getValue());
        CLogAssert.assertEquals("Client Y 坐标应与 Server 同步", 250f, clientLogic.y.getValue());
        CLogAssert.assertEquals("Client HP 应与 Server 同步", (Integer) 3, clientLogic.hp.getValue());

        System.out.println("======= [TDD] Spawn+初始同步 联合测试通过 =======");
    }

    /**
     * 测试3：多个预制体 Spawn，客户端应各自独立派生。
     */
    @Test
    public void testMultiplePrefabSpawn() {
        System.out.println("======= [TDD-RED] 多预制体 Spawn 测试 =======");

        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        int TANK_PREFAB_ID = 1;
        NetworkPrefabFactory tankFactory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new TankBehaviour());
            return obj;
        };
        serverManager.registerPrefab(TANK_PREFAB_ID, tankFactory);
        clientManager.registerPrefab(TANK_PREFAB_ID, tankFactory);

        // Spawn 两个坦克
        NetworkObject tank1 = serverManager.spawnWithPrefab(TANK_PREFAB_ID);
        NetworkObject tank2 = serverManager.spawnWithPrefab(TANK_PREFAB_ID);

        int id1 = (int) tank1.getNetworkId();
        int id2 = (int) tank2.getNetworkId();

        CLogAssert.assertTrue("两个实体的网络ID必须不同", id1 != id2);

        // 验证客户端两个都有
        CLogAssert.assertTrue("Client 端应自动派生出实体1", clientManager.getNetworkObject(id1) != null);
        CLogAssert.assertTrue("Client 端应自动派生出实体2", clientManager.getNetworkObject(id2) != null);

        System.out.println("======= [TDD] 多预制体 Spawn 通过 =======");
    }
}
