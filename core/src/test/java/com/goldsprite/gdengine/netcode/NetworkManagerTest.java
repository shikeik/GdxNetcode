package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.gdengine.test.CLogAssert;
import com.goldsprite.GdxTestRunner;

@RunWith(GdxTestRunner.class)
public class NetworkManagerTest {

    // 内部类模拟一个简单的 Transport 层（可记录发送内容）
    private static class MockTransport implements Transport {
        public int broadcastCount = 0;
        public byte[] lastPayload = null;
        public byte[] lastSentToClient = null;
        public int lastSentToClientId = -1;
        public byte[] lastSentToServer = null;
        private boolean isServerIdentity;
        private TransportReceiveCallback receiveCallback;
        private NetworkConnectionListener connectionListener;

        public MockTransport(boolean isServer) {
            this.isServerIdentity = isServer;
        }

        @Override public void startServer(int port) {}
        @Override public void connect(String ip, int port) {}
        @Override public void disconnect() {}
        @Override
        public void sendToClient(int clientId, byte[] payload) {
            lastSentToClientId = clientId;
            lastSentToClient = payload;
        }
        @Override
        public void sendToServer(byte[] payload) {
            lastSentToServer = payload;
        }
        @Override
        public void setReceiveCallback(TransportReceiveCallback callback) {
            this.receiveCallback = callback;
        }
        @Override
        public void setConnectionListener(NetworkConnectionListener listener) {
            this.connectionListener = listener;
        }
        @Override public int getLocalPort() { return 0; }

        @Override
        public void broadcast(byte[] payload) {
            broadcastCount++;
            lastPayload = payload;
        }

        @Override public boolean isServer() { return isServerIdentity; }
        @Override public boolean isClient() { return !isServerIdentity; }

        /** 模拟 Transport 层触发 onClientConnected */
        public void fireOnClientConnected(int clientId) {
            if (connectionListener != null) {
                connectionListener.onClientConnected(clientId);
            }
        }

        /** 模拟收到数据（推送给 NetworkManager 的 receiveCallback） */
        public void simulateReceive(byte[] payload, int clientId) {
            if (receiveCallback != null) {
                receiveCallback.onReceiveData(payload, clientId);
            }
        }
    }

    // 一个纯粹用作状态存储的组件
    private static class PlayerPositionBehaviour extends NetworkBehaviour {
        public NetworkVariable<Float> x = new NetworkVariable<>(0f);
        public NetworkVariable<Float> y = new NetworkVariable<>(0f);
    }

    @Test
    public void testNetworkManagerSpawnAndTick() {
        NetworkManager manager = new NetworkManager();
        MockTransport transport = new MockTransport(true);
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

    // ========== A2: 重连以 playerToken 识别 ==========

    /** 构建 CONNECT_REQUEST 封包: [0x30][playerToken(string)] */
    private byte[] buildConnectRequest(String token) {
        NetBuffer buf = new NetBuffer();
        buf.writeInt(0x30);
        buf.writeString(token);
        return buf.toByteArray();
    }

    /** 解析 CONNECT_ACCEPT 封包: [0x31][clientId][isReconnect] */
    private int[] parseConnectAccept(byte[] data) {
        NetBuffer buf = new NetBuffer(data);
        int type = buf.readInt();
        CLogAssert.assertEquals("包类型应为 0x31", 0x31, type);
        int clientId = buf.readInt();
        boolean isReconnect = buf.readBoolean();
        return new int[]{clientId, isReconnect ? 1 : 0};
    }

    /**
     * 测试 A2-1: 首次连接 — Server 收到 CONNECT_REQUEST 后发送 CONNECT_ACCEPT(isReconnect=false)
     */
    @Test
    public void testA2_firstConnectRequest() {
        System.out.println("======= [TDD] A2 首次连接请求 =======");

        NetworkManager serverMgr = new NetworkManager();
        MockTransport serverTransport = new MockTransport(true);
        serverMgr.setTransport(serverTransport);

        // 记录游戏层回调
        final int[] connectedHolder = {-1};
        final boolean[] reconnectHolder = {false};
        serverMgr.setConnectionListener(new NetworkConnectionListener() {
            @Override
            public void onClientConnected(int clientId) {
                connectedHolder[0] = clientId;
            }
        });

        // 模拟 UDP 握手完成，clientId=0
        serverTransport.fireOnClientConnected(0);

        // 此时游戏层不应该收到回调（A2: 延迟到 CONNECT_REQUEST 后）
        CLogAssert.assertEquals("UDP 握手后游戏层不应立即收到回调", -1, connectedHolder[0]);

        // 模拟 Client 发送 CONNECT_REQUEST
        serverTransport.simulateReceive(buildConnectRequest("PlayerAlice"), 0);

        // 现在游戏层应该收到回调了
        CLogAssert.assertEquals("CONNECT_REQUEST 后游戏层应收到 clientId=0", 0, connectedHolder[0]);

        // Server 应该已发送 CONNECT_ACCEPT 给 clientId=0
        CLogAssert.assertTrue("Server 应发送 CONNECT_ACCEPT", serverTransport.lastSentToClient != null);
        int[] accept = parseConnectAccept(serverTransport.lastSentToClient);
        CLogAssert.assertEquals("CONNECT_ACCEPT clientId 应为 0", 0, accept[0]);
        CLogAssert.assertEquals("isReconnect 应为 false", 0, accept[1]);

        System.out.println("======= [TDD] A2 首次连接请求 通过 =======");
    }

    /**
     * 测试 A2-2: 重连 — 相同 token 再次连入，Server 识别为重连
     */
    @Test
    public void testA2_reconnectSameToken() {
        System.out.println("======= [TDD] A2 重连识别 =======");

        NetworkManager serverMgr = new NetworkManager();
        MockTransport serverTransport = new MockTransport(true);
        serverMgr.setTransport(serverTransport);
        serverMgr.registerPrefab(1, () -> {
            NetworkObject obj = new NetworkObject(0);
            obj.addComponent(new PlayerPositionBehaviour());
            return obj;
        });

        // 首次连接: clientId=0, token="Bob"
        serverTransport.fireOnClientConnected(0);
        serverTransport.simulateReceive(buildConnectRequest("Bob"), 0);

        // Server 为 Bob Spawn 一个实体 (ownerClientId=0)
        NetworkObject bobTank = serverMgr.spawnWithPrefab(1, 0);
        int bobNetId = (int) bobTank.getNetworkId();

        CLogAssert.assertEquals("Bob 的实体 ownerClientId 应为 0", 0, bobTank.getOwnerClientId());

        // 模拟断线（不调 despawnByOwner，因为实际重连场景是 Client 断链后立即重连）

        // 重连: 新 clientId=1 (UDP 新端口), 但相同 token="Bob"
        serverTransport.fireOnClientConnected(1);
        serverTransport.simulateReceive(buildConnectRequest("Bob"), 1);

        // CONNECT_ACCEPT 应为 isReconnect=true
        int[] accept = parseConnectAccept(serverTransport.lastSentToClient);
        CLogAssert.assertEquals("重连 CONNECT_ACCEPT clientId 应为 1", 1, accept[0]);
        CLogAssert.assertEquals("isReconnect 应为 true", 1, accept[1]);

        // Bob 的实体 ownerClientId 应更新为新的 clientId=1
        CLogAssert.assertEquals("重连后实体 ownerClientId 应更新为 1", 1, bobTank.getOwnerClientId());

        // 实体仍在 NetworkManager 中（未被 despawn）
        CLogAssert.assertTrue("重连后实体不应被移除", serverMgr.getNetworkObject(bobNetId) != null);

        System.out.println("======= [TDD] A2 重连识别 通过 =======");
    }

    /**
     * 测试 A2-3: 不同 token 视为新玩家
     */
    @Test
    public void testA2_differentTokenIsNewPlayer() {
        System.out.println("======= [TDD] A2 不同 token 新玩家 =======");

        NetworkManager serverMgr = new NetworkManager();
        MockTransport serverTransport = new MockTransport(true);
        serverMgr.setTransport(serverTransport);

        final int[] connectedCount = {0};
        serverMgr.setConnectionListener(new NetworkConnectionListener() {
            @Override
            public void onClientConnected(int clientId) {
                connectedCount[0]++;
            }
        });

        // 玩家 Alice
        serverTransport.fireOnClientConnected(0);
        serverTransport.simulateReceive(buildConnectRequest("Alice"), 0);
        CLogAssert.assertEquals("Alice 连接后回调次数", 1, connectedCount[0]);

        // 玩家 Charlie (不同 token)
        serverTransport.fireOnClientConnected(1);
        serverTransport.simulateReceive(buildConnectRequest("Charlie"), 1);
        CLogAssert.assertEquals("Charlie 连接后回调次数", 2, connectedCount[0]);

        // 两个都不是重连
        CLogAssert.assertFalse("Alice 不是重连",
            serverMgr.isLastConnectionReconnect());

        System.out.println("======= [TDD] A2 不同 token 新玩家 通过 =======");
    }

    /**
     * 测试 A2-4: Client 端收到 CONNECT_ACCEPT 后设置 localClientId
     */
    @Test
    public void testA2_clientReceivesConnectAccept() {
        System.out.println("======= [TDD] A2 Client 收到 CONNECT_ACCEPT =======");

        NetworkManager clientMgr = new NetworkManager();
        MockTransport clientTransport = new MockTransport(false);
        clientMgr.setTransport(clientTransport);
        clientMgr.setPlayerToken("TestPlayer");

        // 模拟 Client 端 UDP 握手完成 → 自动发送 CONNECT_REQUEST
        clientTransport.fireOnClientConnected(5);

        // 验证 Client 是否发了 CONNECT_REQUEST
        CLogAssert.assertTrue("Client 应自动发送 CONNECT_REQUEST", clientTransport.lastSentToServer != null);
        NetBuffer reqBuf = new NetBuffer(clientTransport.lastSentToServer);
        CLogAssert.assertEquals("CONNECT_REQUEST 包类型", 0x30, reqBuf.readInt());
        CLogAssert.assertEquals("token 应为 TestPlayer", "TestPlayer", reqBuf.readString());

        // 模拟 Server 发回 CONNECT_ACCEPT
        NetBuffer acceptBuf = new NetBuffer();
        acceptBuf.writeInt(0x31);
        acceptBuf.writeInt(5);
        acceptBuf.writeBoolean(false);
        clientTransport.simulateReceive(acceptBuf.toByteArray(), -1);

        // Client 的 localClientId 应更新
        CLogAssert.assertEquals("Client localClientId 应为 5", 5, clientMgr.getLocalClientId());

        System.out.println("======= [TDD] A2 Client 收到 CONNECT_ACCEPT 通过 =======");
    }
}
