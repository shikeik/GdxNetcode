package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

@RunWith(GdxTestRunner.class)
public class NetworkManagerTest {

    // 内部类模拟一个简单的 Transport 层
    private static class MockTransport implements Transport {
        public int broadcastCount = 0;
        public byte[] lastPayload = null;
        
        @Override public void startServer(int port) {}
        @Override public void connect(String ip, int port) {}
        @Override public void disconnect() {}
        @Override public void sendToClient(int clientId, byte[] payload) {}
        @Override public void sendToServer(byte[] payload) {}
        @Override public void setReceiveCallback(TransportReceiveCallback callback) {}
        @Override public void setConnectionListener(NetworkConnectionListener listener) {}
        
        @Override
        public void broadcast(byte[] payload) {
            broadcastCount++;
            lastPayload = payload;
        }

        @Override public boolean isServer() { return true; }
        @Override public boolean isClient() { return false; }
    }

    // 一个纯粹用作状态存储的组件
    private static class PlayerPositionBehaviour extends NetworkBehaviour {
        public NetworkVariable<Float> x = new NetworkVariable<>(0f);
        public NetworkVariable<Float> y = new NetworkVariable<>(0f);
    }

    @Test
    public void testNetworkManagerSpawnAndTick() {
        NetworkManager manager = new NetworkManager();
        MockTransport transport = new MockTransport();
        manager.setTransport(transport);

        // 创建网络对象与组件
        NetworkObject playerObj = new NetworkObject(0);
        PlayerPositionBehaviour posLogic = new PlayerPositionBehaviour();
        playerObj.addComponent(posLogic);

        // 1. 测试 Spawn
        manager.spawn(playerObj);
        CLogAssert.assertTrue("验证 NetworkManager 是否成功托管了生成的网络对象: Spawn后的首个实体网络ID应被分配为1", manager.getNetworkObject(1) == playerObj);

        // 2. 测试 Tick 初始同步（所有变量初始 dirty 状态应当在第一次Tick时发出）
        manager.tick();
        CLogAssert.assertTrue("对象初次 Spawn 后，其所含未清空的脏数据应当产生首发同步: Broadcast 次数应当为1", transport.broadcastCount == 1);

        // 3. 产生下一次变种的正常数据修改
        posLogic.x.setValue(100f);
        manager.tick();
        CLogAssert.assertTrue("修改坐标后的Tick检查: 应该发生第2次广播叠加", transport.broadcastCount == 2);

        // 4. Tick 执行完毕后，脏位应当自动清空，接着再Tick一次
        manager.tick();
        CLogAssert.assertTrue("脏数据清理后，无变动连续Tick不应产生无效的重复发送", transport.broadcastCount == 2);

        // 5. 再次修改另一个变量
        posLogic.y.setValue(50f);
        manager.tick();
        CLogAssert.assertTrue("对新被托管属性产生变动的持续监测", transport.broadcastCount == 3);
    }
}
