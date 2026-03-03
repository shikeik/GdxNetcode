package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

/**
 * TDD 驱动：验证 RPC 调用链路的完整性。
 * - ClientRpc: Server 调用后，Client 端自动收到并执行对应方法
 * - ServerRpc: Client 调用后，Server 端自动收到并执行对应方法
 */
@RunWith(GdxTestRunner.class)
public class RpcInvocationTest {

    // ======= 测试用的业务逻辑 =======
    public static class GunBehaviour extends NetworkBehaviour {
        public boolean bulletFired = false;
        public int lastBulletId = -1;
        public float lastX = 0, lastY = 0;

        public String lastExplosionFx = null;

        /**
         * Client -> Server: 客户端请求服务端执行开火
         */
        @ServerRpc
        public void requestFire(int bulletId, float x, float y) {
            bulletFired = true;
            lastBulletId = bulletId;
            lastX = x;
            lastY = y;
        }

        /**
         * Server -> Client: 服务端通知所有客户端播放爆炸特效
         */
        @ClientRpc
        public void playExplosion(String fxName) {
            lastExplosionFx = fxName;
        }
    }

    /**
     * 测试1: Server 调用 ClientRpc，Client 端自动收到并执行
     */
    @Test
    public void testClientRpc_ServerToClient() {
        System.out.println("======= [TDD] ClientRpc: Server -> Client 测试 =======");

        // 搭建双端
        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        // 双端注册预制体
        int GUN_PREFAB = 1;
        NetworkPrefabFactory gunFactory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new GunBehaviour());
            return obj;
        };
        serverManager.registerPrefab(GUN_PREFAB, gunFactory);
        clientManager.registerPrefab(GUN_PREFAB, gunFactory);

        // Server Spawn
        NetworkObject serverGun = serverManager.spawnWithPrefab(GUN_PREFAB);
        int netId = (int) serverGun.getNetworkId();
        GunBehaviour serverLogic = (GunBehaviour) serverGun.getBehaviours().get(0);

        // 获取 Client 端的 ghost
        GunBehaviour clientLogic = (GunBehaviour) clientManager.getNetworkObject(netId).getBehaviours().get(0);

        // 验证初始状态
        CLogAssert.assertTrue("Client 端爆炸特效应为空", clientLogic.lastExplosionFx == null);

        // Server 端发起 ClientRpc
        serverLogic.sendClientRpc("playExplosion", "boom_fx_01");

        // Client 端应自动执行了方法
        CLogAssert.assertEquals("Client 端应收到并执行了 playExplosion", "boom_fx_01", clientLogic.lastExplosionFx);

        System.out.println("======= [TDD] ClientRpc 测试通过 =======");
    }

    /**
     * 测试2: Client 调用 ServerRpc，Server 端自动收到并执行
     */
    @Test
    public void testServerRpc_ClientToServer() {
        System.out.println("======= [TDD] ServerRpc: Client -> Server 测试 =======");

        // 搭建双端
        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        // 双端注册预制体
        int GUN_PREFAB = 1;
        NetworkPrefabFactory gunFactory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new GunBehaviour());
            return obj;
        };
        serverManager.registerPrefab(GUN_PREFAB, gunFactory);
        clientManager.registerPrefab(GUN_PREFAB, gunFactory);

        // Server Spawn
        NetworkObject serverGun = serverManager.spawnWithPrefab(GUN_PREFAB);
        int netId = (int) serverGun.getNetworkId();
        GunBehaviour serverLogic = (GunBehaviour) serverGun.getBehaviours().get(0);

        // 获取 Client ghost
        GunBehaviour clientLogic = (GunBehaviour) clientManager.getNetworkObject(netId).getBehaviours().get(0);

        // 验证初始状态
        CLogAssert.assertFalse("Server 端开火状态应为 false", serverLogic.bulletFired);

        // Client 端发起 ServerRpc
        clientLogic.sendServerRpc("requestFire", 42, 100.5f, 200.0f);

        // Server 端应自动执行了方法
        CLogAssert.assertTrue("Server 端应收到并执行了 requestFire", serverLogic.bulletFired);
        CLogAssert.assertEquals("子弹ID应匹配", 42, serverLogic.lastBulletId);
        CLogAssert.assertEquals("X 坐标应匹配", 100.5f, serverLogic.lastX, 0.001f);
        CLogAssert.assertEquals("Y 坐标应匹配", 200.0f, serverLogic.lastY, 0.001f);

        System.out.println("======= [TDD] ServerRpc 测试通过 =======");
    }

    /**
     * 测试3: RPC 参数多种类型混合 (int, float, boolean, String)
     */
    @Test
    public void testRpcWithMixedParamTypes() {
        System.out.println("======= [TDD] RPC 混合参数类型测试 =======");

        // 内联一个多参数 Behaviour
        NetworkManager serverManager = new NetworkManager();
        LocalMemoryTransport serverTransport = new LocalMemoryTransport(true);
        serverManager.setTransport(serverTransport);

        NetworkManager clientManager = new NetworkManager();
        LocalMemoryTransport clientTransport = new LocalMemoryTransport(false);
        clientManager.setTransport(clientTransport);

        serverTransport.connectToPeer(clientTransport);

        int GUN_PREFAB = 1;
        NetworkPrefabFactory gunFactory = () -> {
            NetworkObject obj = new NetworkObject();
            obj.addComponent(new GunBehaviour());
            return obj;
        };
        serverManager.registerPrefab(GUN_PREFAB, gunFactory);
        clientManager.registerPrefab(GUN_PREFAB, gunFactory);

        NetworkObject serverGun = serverManager.spawnWithPrefab(GUN_PREFAB);
        int netId = (int) serverGun.getNetworkId();
        GunBehaviour serverLogic = (GunBehaviour) serverGun.getBehaviours().get(0);
        GunBehaviour clientLogic = (GunBehaviour) clientManager.getNetworkObject(netId).getBehaviours().get(0);

        // Server -> Client RPC: String 参数
        serverLogic.sendClientRpc("playExplosion", "mega_boom");
        CLogAssert.assertEquals("String 参数传递正确", "mega_boom", clientLogic.lastExplosionFx);

        // Client -> Server RPC: int + float + float 参数
        clientLogic.sendServerRpc("requestFire", 99, 50.0f, 75.5f);
        CLogAssert.assertTrue("Server 收到 RPC", serverLogic.bulletFired);
        CLogAssert.assertEquals("int 参数传递正确", 99, serverLogic.lastBulletId);

        System.out.println("======= [TDD] 混合参数类型测试通过 =======");
    }
}
