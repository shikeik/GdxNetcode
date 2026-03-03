package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

@RunWith(GdxTestRunner.class)
public class DualEndpointIntegrationTest {

    private static class DummyPlayerBehaviour extends NetworkBehaviour {
        public NetworkVariable<Float> hp = new NetworkVariable<>(100f);
    }

    @Test
    public void testServerToClientStateSync() {
        System.out.println("======= 开始双端自动同步TDD驱动测试 =======");

        // ------------- 1. 搭建 Server -------------
        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        // ------------- 2. 搭建 Client -------------
        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        // ------------- 3. 双端注册预制体工厂（取代反射 Hack） -------------
        int PLAYER_PREFAB_ID = 1;
        NetworkPrefabFactory playerFactory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new DummyPlayerBehaviour());
            return obj;
        };
        serverManager.registerPrefab(PLAYER_PREFAB_ID, playerFactory);
        clientManager.registerPrefab(PLAYER_PREFAB_ID, playerFactory);

        // ------------- 4. Server 通过预制体 Spawn（自动广播到 Client） -------------
        NetworkObject serverPlayer = serverManager.spawnWithPrefab(PLAYER_PREFAB_ID);
        int netId = (int) serverPlayer.getNetworkId();
        DummyPlayerBehaviour serverLogic = (DummyPlayerBehaviour) serverPlayer.getBehaviours().get(0);

        // Client 端应已自动派生出对应实体
        NetworkObject clientPlayer = clientManager.getNetworkObject(netId);
        CLogAssert.assertTrue("Client 应已自动派生出实体", clientPlayer != null);
        DummyPlayerBehaviour clientLogic = (DummyPlayerBehaviour) clientPlayer.getBehaviours().get(0);

        // ------------- 5. 驱动 Server 状态同步 -------------
        int expectedPreRecv = clientTransport.messagesReceived;
        
        serverLogic.hp.setValue(80f);
        System.out.println("扣除了Server上的血量，当前Server HP: " + serverLogic.hp.getValue());

        // 执行网络管理同步主循环，此时产生Dirty应该被序列化发包
        serverManager.tick();

        int afterRecv = clientTransport.messagesReceived;
        CLogAssert.assertTrue("当Server数据更新时必须能收到包", afterRecv > expectedPreRecv);   
        
        System.out.println("收到包之前客户端的HP: " + clientLogic.hp.getValue());

        CLogAssert.assertEquals("自动同步流：Client的数据应该被同步，从而与Server的数据保持严格一致", 80f, clientLogic.hp.getValue());

        System.out.println("======= 测试双端自动同步完成 =======");
    }
}
