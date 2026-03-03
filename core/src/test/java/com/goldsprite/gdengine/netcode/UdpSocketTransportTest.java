package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

/**
 * TDD 驱动：验证 UdpSocketTransport 通过真实 UDP Socket 传输数据。
 * 测试在本机 127.0.0.1 上使用随机端口进行，不依赖任何外部网络。
 */
@RunWith(GdxTestRunner.class)
public class UdpSocketTransportTest {

    /**
     * 测试1：基础 UDP 传输 — Server 广播数据，Client 通过回调接收。
     * 验证字节经过真实 Socket 后完整到达对端。
     */
    @Test
    public void testServerBroadcastToClient() throws Exception {
        System.out.println("======= [TDD] UDP: Server -> Client 广播测试 =======");

        int serverPort = 19001;

        // 创建 Server 端传输层
        UdpSocketTransport serverTransport = new UdpSocketTransport(true);
        // 创建 Client 端传输层
        UdpSocketTransport clientTransport = new UdpSocketTransport(false);

        // 用于在回调中捕获接收到的数据
        final byte[][] receivedHolder = new byte[1][];
        clientTransport.setReceiveCallback((payload, clientId) -> {
            receivedHolder[0] = payload;
        });

        // 启动
        serverTransport.startServer(serverPort);
        clientTransport.connect("127.0.0.1", serverPort);

        // 等待连接建立（Client 需要先向 Server 发送握手包，Server 才知道 Client 地址）
        // UdpSocketTransport.connect() 内部会发送一个握手包
        Thread.sleep(200);

        // Server 广播数据
        byte[] testData = new byte[]{0x10, 0x20, 0x30, 0x40};
        serverTransport.broadcast(testData);

        // 等待 UDP 数据到达（本地回环应极快）
        Thread.sleep(200);

        CLogAssert.assertTrue("Client 应通过回调收到广播数据", receivedHolder[0] != null);
        CLogAssert.assertEquals("数据长度应一致", testData.length, receivedHolder[0].length);
        CLogAssert.assertEquals("数据内容首字节匹配", (byte) 0x10, receivedHolder[0][0]);
        CLogAssert.assertEquals("数据内容末字节匹配", (byte) 0x40, receivedHolder[0][3]);

        // 清理
        serverTransport.disconnect();
        clientTransport.disconnect();

        System.out.println("======= [TDD] UDP 广播测试通过 =======");
    }

    /**
     * 测试2：Client -> Server 传输（sendToServer）。
     */
    @Test
    public void testClientSendToServer() throws Exception {
        System.out.println("======= [TDD] UDP: Client -> Server 传输测试 =======");

        int serverPort = 19002;

        UdpSocketTransport serverTransport = new UdpSocketTransport(true);
        UdpSocketTransport clientTransport = new UdpSocketTransport(false);

        final byte[][] receivedHolder = new byte[1][];
        serverTransport.setReceiveCallback((payload, clientId) -> {
            // 过滤握手包（握手包只有 4 字节：[0xFF, 0xFF, 0xFF, 0xFF]）
            if (payload.length == 4 && payload[0] == (byte) 0xFF) return;
            receivedHolder[0] = payload;
        });

        serverTransport.startServer(serverPort);
        clientTransport.connect("127.0.0.1", serverPort);
        Thread.sleep(200);

        // Client -> Server 发送数据
        byte[] testData = new byte[]{0x01, 0x02, 0x03};
        clientTransport.sendToServer(testData);

        Thread.sleep(200);

        CLogAssert.assertTrue("Server 应通过回调收到 Client 数据", receivedHolder[0] != null);
        CLogAssert.assertEquals("数据长度一致", testData.length, receivedHolder[0].length);
        CLogAssert.assertEquals("首字节匹配", (byte) 0x01, receivedHolder[0][0]);

        serverTransport.disconnect();
        clientTransport.disconnect();

        System.out.println("======= [TDD] Client->Server 传输测试通过 =======");
    }

    /**
     * 测试3：UDP + NetworkManager 全链路集成 — Spawn + StateSync 通过真实 Socket 完成。
     */
    @Test
    public void testUdpWithNetworkManager_SpawnAndSync() throws Exception {
        System.out.println("======= [TDD] UDP + NetworkManager 全链路集成测试 =======");

        int serverPort = 19003;

        // 搭建 Server 端
        NetworkManager serverManager = new NetworkManager();
        UdpSocketTransport serverTransport = new UdpSocketTransport(true);
        serverManager.setTransport(serverTransport);

        // 搭建 Client 端
        NetworkManager clientManager = new NetworkManager();
        UdpSocketTransport clientTransport = new UdpSocketTransport(false);
        clientManager.setTransport(clientTransport);

        // 启动网络
        serverTransport.startServer(serverPort);
        clientTransport.connect("127.0.0.1", serverPort);
        Thread.sleep(200);

        // 双端注册预制体工厂
        int PREFAB_ID = 1;
        NetworkPrefabFactory factory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new SyncTestBehaviour());
            return obj;
        };
        serverManager.registerPrefab(PREFAB_ID, factory);
        clientManager.registerPrefab(PREFAB_ID, factory);

        // Server Spawn（SpawnPacket 通过 UDP broadcast 发送）
        NetworkObject serverObj = serverManager.spawnWithPrefab(PREFAB_ID);
        int netId = (int) serverObj.getNetworkId();

        // 等待 SpawnPacket 通过 UDP 到达 Client
        Thread.sleep(300);

        NetworkObject clientObj = clientManager.getNetworkObject(netId);
        CLogAssert.assertTrue("Client 应通过 UDP 收到 SpawnPacket 并自动派生实体", clientObj != null);

        // 修改 Server 端状态并 tick
        SyncTestBehaviour serverLogic = (SyncTestBehaviour) serverObj.getBehaviours().get(0);
        serverLogic.score.setValue(42);

        serverManager.tick();

        // 等待状态同步包通过 UDP 到达
        Thread.sleep(300);

        SyncTestBehaviour clientLogic = (SyncTestBehaviour) clientObj.getBehaviours().get(0);
        CLogAssert.assertEquals("Client score 应通过 UDP 同步为 42", (Integer) 42, clientLogic.score.getValue());

        // 清理
        serverTransport.disconnect();
        clientTransport.disconnect();

        System.out.println("======= [TDD] UDP 全链路集成测试通过 =======");
    }

    // ======= 测试用的简单行为组件 =======
    private static class SyncTestBehaviour extends NetworkBehaviour {
        public NetworkVariable<Integer> score = new NetworkVariable<>(0);
    }
}
