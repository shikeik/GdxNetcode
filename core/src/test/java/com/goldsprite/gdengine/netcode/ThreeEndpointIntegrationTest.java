package com.goldsprite.gdengine.netcode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

/**
 * 三端（1 Server + 2 Client）集成测试。
 * <p>
 * 基于 {@link SimulatedTransport} + {@link ThreeEndpointTestHarness}，
 * 在单 JVM 中模拟完整的 Server-ClientA-ClientB 网络拓扑。
 * <p>
 * 覆盖场景:
 * <ol>
 *   <li>零延迟: Spawn / 状态同步 / RPC / Despawn</li>
 *   <li>40ms 正常延迟: 数据包最终正确投递</li>
 *   <li>200ms 高 Ping 边界: 极端延迟下的最终一致性</li>
 *   <li>断线: 一端断线不影响另一端</li>
 * </ol>
 */
@RunWith(GdxTestRunner.class)
public class ThreeEndpointIntegrationTest {

    // ═══════ 测试用业务组件 ═══════

    /** 模拟坦克行为 — 含多个 NetworkVariable 和 RPC 方法 */
    public static class TankBehaviour extends NetworkBehaviour {
        public NetworkVariable<Float> posX = new NetworkVariable<>(0f);
        public NetworkVariable<Float> posY = new NetworkVariable<>(0f);
        public NetworkVariable<Integer> hp = new NetworkVariable<>(100);
        public NetworkVariable<String> playerName = new NetworkVariable<>("未命名");

        // ── RPC 标记字段（用于测试验证） ──
        public boolean serverRpcReceived = false;
        public int lastFireBulletId = -1;
        public String lastClientRpcFx = null;

        @ServerRpc
        public void requestFire(int bulletId) {
            serverRpcReceived = true;
            lastFireBulletId = bulletId;
        }

        @ClientRpc
        public void playEffect(String fxName) {
            lastClientRpcFx = fxName;
        }
    }

    // ═══════ 常量 ═══════
    private static final int TANK_PREFAB_ID = 1;
    private static final NetworkPrefabFactory TANK_FACTORY = () -> {
        NetworkObject obj = new NetworkObject();
        obj.addComponent(new TankBehaviour());
        return obj;
    };

    // ═══════ 测试工具 ═══════
    private ThreeEndpointTestHarness harness;

    @Before
    public void setUp() {
        harness = new ThreeEndpointTestHarness();
        harness.setup();
        harness.registerPrefab(TANK_PREFAB_ID, TANK_FACTORY);
    }

    @After
    public void tearDown() {
        if (harness != null) harness.teardown();
    }

    // ╔══════════════════════════════════════════════════════════╗
    // ║                 一、零延迟基础测试                          ║
    // ╚══════════════════════════════════════════════════════════╝

    /** Server Spawn 的实体应即时出现在两个 Client 端 */
    @Test
    public void testSpawn_bothClientsReceive() {
        System.out.println("═══ [三端] Spawn 双客户端接收 ═══");

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();

        // 零延迟下，broadcast 在 spawnWithPrefab 中同步执行
        NetworkObject clientATank = harness.clientA.getNetworkObject(netId);
        NetworkObject clientBTank = harness.clientB.getNetworkObject(netId);

        CLogAssert.assertTrue("ClientA 应收到 Spawn", clientATank != null);
        CLogAssert.assertTrue("ClientB 应收到 Spawn", clientBTank != null);
        CLogAssert.assertEquals("三端实体数量一致", 1, harness.clientA.getNetworkObjectCount());
        CLogAssert.assertEquals("三端实体数量一致", 1, harness.clientB.getNetworkObjectCount());

        System.out.println("═══ [通过] Spawn 双客户端接收 ═══");
    }

    /** Server 修改 NetworkVariable 后，状态同步应同时到达两个 Client */
    @Test
    public void testStateSync_bothClientsReceive() {
        System.out.println("═══ [三端] 状态同步双客户端 ═══");

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);

        // 修改 Server 端数据
        serverLogic.posX.setValue(100f);
        serverLogic.posY.setValue(200f);
        serverLogic.hp.setValue(75);

        // forceTick 保证立即触发一次状态同步（无累加器限制）
        harness.forceTick();

        // 验证两个 Client 都收到了同步数据
        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        TankBehaviour clientBLogic = (TankBehaviour) harness.clientB.getNetworkObject(netId).getBehaviours().get(0);

        CLogAssert.assertEquals("ClientA posX 同步", 100f, clientALogic.posX.getValue());
        CLogAssert.assertEquals("ClientA posY 同步", 200f, clientALogic.posY.getValue());
        CLogAssert.assertEquals("ClientA hp 同步", (Integer) 75, clientALogic.hp.getValue());
        CLogAssert.assertEquals("ClientB posX 同步", 100f, clientBLogic.posX.getValue());
        CLogAssert.assertEquals("ClientB posY 同步", 200f, clientBLogic.posY.getValue());
        CLogAssert.assertEquals("ClientB hp 同步", (Integer) 75, clientBLogic.hp.getValue());

        System.out.println("═══ [通过] 状态同步双客户端 ═══");
    }

    /** Server 发送 ClientRpc，两个 Client 都应执行 */
    @Test
    public void testClientRpc_bothClientsReceive() {
        System.out.println("═══ [三端] ClientRpc 广播 ═══");

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);

        // Server 端发送 ClientRpc
        serverLogic.sendClientRpc("playEffect", "explosion_big");

        // 零延迟下，RPC 立即到达
        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        TankBehaviour clientBLogic = (TankBehaviour) harness.clientB.getNetworkObject(netId).getBehaviours().get(0);

        CLogAssert.assertEquals("ClientA 收到 ClientRpc", "explosion_big", clientALogic.lastClientRpcFx);
        CLogAssert.assertEquals("ClientB 收到 ClientRpc", "explosion_big", clientBLogic.lastClientRpcFx);

        System.out.println("═══ [通过] ClientRpc 广播 ═══");
    }

    /** Client 发送 ServerRpc，Server 端应收到并执行 */
    @Test
    public void testServerRpc_clientToServer() {
        System.out.println("═══ [三端] ServerRpc (Client → Server) ═══");

        // 为 ClientA 创建一个带 owner 的坦克
        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID, 0);
        int netId = (int) serverTank.getNetworkId();
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);

        // ClientA 端通过自己的 ghost 发送 ServerRpc
        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        clientALogic.sendServerRpc("requestFire", 42);

        // 零延迟下，RPC 立即到达 Server
        CLogAssert.assertTrue("Server 应收到 ServerRpc", serverLogic.serverRpcReceived);
        CLogAssert.assertEquals("Server 收到的 bulletId 正确", 42, serverLogic.lastFireBulletId);

        System.out.println("═══ [通过] ServerRpc (Client → Server) ═══");
    }

    /** Server Despawn 实体后，两个 Client 都应移除 */
    @Test
    public void testDespawn_bothClientsRemove() {
        System.out.println("═══ [三端] Despawn 双客户端移除 ═══");

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();

        // 确认两端都有
        CLogAssert.assertTrue("ClientA 拥有实体", harness.clientA.getNetworkObject(netId) != null);
        CLogAssert.assertTrue("ClientB 拥有实体", harness.clientB.getNetworkObject(netId) != null);

        // Server Despawn
        harness.server.despawn(netId);

        // 验证两端都移除了
        CLogAssert.assertTrue("ClientA 实体已移除", harness.clientA.getNetworkObject(netId) == null);
        CLogAssert.assertTrue("ClientB 实体已移除", harness.clientB.getNetworkObject(netId) == null);
        CLogAssert.assertEquals("ClientA 实体数为 0", 0, harness.clientA.getNetworkObjectCount());
        CLogAssert.assertEquals("ClientB 实体数为 0", 0, harness.clientB.getNetworkObjectCount());

        System.out.println("═══ [通过] Despawn 双客户端移除 ═══");
    }

    /** 多实体 Spawn: Server 为两个 Client 各创建一个坦克 */
    @Test
    public void testMultiSpawn_perClient() {
        System.out.println("═══ [三端] 多实体 Spawn ═══");

        NetworkObject tankA = harness.server.spawnWithPrefab(TANK_PREFAB_ID, 0);
        NetworkObject tankB = harness.server.spawnWithPrefab(TANK_PREFAB_ID, 1);
        int netIdA = (int) tankA.getNetworkId();
        int netIdB = (int) tankB.getNetworkId();

        // 两个 Client 都应看到两个实体
        CLogAssert.assertEquals("ClientA 实体数为 2", 2, harness.clientA.getNetworkObjectCount());
        CLogAssert.assertEquals("ClientB 实体数为 2", 2, harness.clientB.getNetworkObjectCount());

        // 验证 owner 信息正确传递
        CLogAssert.assertEquals("实体A 的 owner", 0, harness.clientA.getNetworkObject(netIdA).getOwnerClientId());
        CLogAssert.assertEquals("实体B 的 owner", 1, harness.clientB.getNetworkObject(netIdB).getOwnerClientId());

        System.out.println("═══ [通过] 多实体 Spawn ═══");
    }

    // ╔══════════════════════════════════════════════════════════╗
    // ║              二、40ms 正常延迟测试                          ║
    // ╚══════════════════════════════════════════════════════════╝

    /** 40ms 延迟下，Spawn 包在数帧后到达两个 Client */
    @Test
    public void testLatency40ms_spawnEventuallyArrives() {
        System.out.println("═══ [三端] 40ms 延迟 Spawn ═══");
        harness.setLatency(40);

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();

        // Spawn 后立即检查 — Client 还没收到（包在延迟队列中）
        CLogAssert.assertTrue("ClientA 尚未收到 Spawn（包在延迟中）",
            harness.clientA.getNetworkObject(netId) == null);

        // 推帧直到 Client 收到 Spawn（40ms / 16ms ≈ 3 帧）
        int frames = harness.tickUntil(
            () -> harness.clientA.getNetworkObject(netId) != null, 20);
        CLogAssert.assertTrue("ClientA 应在合理帧数内收到 Spawn", frames >= 0 && frames <= 10);

        // ClientB 也应收到
        CLogAssert.assertTrue("ClientB 也收到了 Spawn",
            harness.clientB.getNetworkObject(netId) != null);

        System.out.println("═══ [通过] 40ms 延迟 Spawn（" + frames + " 帧后到达）═══");
    }

    /** 40ms 延迟下，状态同步最终到达两个 Client */
    @Test
    public void testLatency40ms_stateSyncEventuallyConsistent() {
        System.out.println("═══ [三端] 40ms 延迟状态同步 ═══");
        harness.setLatency(40);

        // 先零延迟 Spawn（方便获取引用），然后再设延迟
        harness.setLatency(0);
        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);
        harness.setLatency(40);

        // Server 修改状态
        serverLogic.posX.setValue(999f);
        serverLogic.hp.setValue(50);

        // forceTick 产生状态同步包（但因延迟，Client 还没收到）
        harness.forceTick();

        // 此刻 Client 还未同步
        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        // posX 可能还是旧值 0（延迟中）

        // 继续推帧，等待延迟包到达
        harness.tickFrames(5); // 5 * 16ms = 80ms >> 40ms，肯定到达

        CLogAssert.assertEquals("ClientA posX 最终同步", 999f, clientALogic.posX.getValue());
        CLogAssert.assertEquals("ClientA hp 最终同步", (Integer) 50, clientALogic.hp.getValue());

        TankBehaviour clientBLogic = (TankBehaviour) harness.clientB.getNetworkObject(netId).getBehaviours().get(0);
        CLogAssert.assertEquals("ClientB posX 最终同步", 999f, clientBLogic.posX.getValue());
        CLogAssert.assertEquals("ClientB hp 最终同步", (Integer) 50, clientBLogic.hp.getValue());

        System.out.println("═══ [通过] 40ms 延迟状态同步 ═══");
    }

    /** 40ms 延迟下，RPC 最终到达 */
    @Test
    public void testLatency40ms_rpcEventuallyArrives() {
        System.out.println("═══ [三端] 40ms 延迟 RPC ═══");

        // 零延迟 Spawn
        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID, 0);
        int netId = (int) serverTank.getNetworkId();
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);

        // 设置 40ms 延迟
        harness.setLatency(40);

        // Server 发 ClientRpc
        serverLogic.sendClientRpc("playEffect", "heal_sparkle");

        // 立即检查 — 延迟中，还没到达
        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        CLogAssert.assertTrue("ClientA RPC 尚未到达", clientALogic.lastClientRpcFx == null);

        // 推帧等待
        harness.tickFrames(5);

        CLogAssert.assertEquals("ClientA 收到 RPC", "heal_sparkle", clientALogic.lastClientRpcFx);

        // Client ServerRpc 测试
        clientALogic.sendServerRpc("requestFire", 77);
        CLogAssert.assertTrue("Server 尚未收到 ServerRpc", !serverLogic.serverRpcReceived);
        harness.tickFrames(5);
        CLogAssert.assertTrue("Server 最终收到 ServerRpc", serverLogic.serverRpcReceived);
        CLogAssert.assertEquals("bulletId 正确", 77, serverLogic.lastFireBulletId);

        System.out.println("═══ [通过] 40ms 延迟 RPC ═══");
    }

    // ╔══════════════════════════════════════════════════════════╗
    // ║             三、200ms 高 Ping 边界测试                     ║
    // ╚══════════════════════════════════════════════════════════╝

    /** 200ms 高延迟下，Spawn 仍然最终到达 */
    @Test
    public void testHighPing200ms_spawnEventuallyArrives() {
        System.out.println("═══ [三端] 200ms 高 Ping Spawn ═══");
        harness.setLatency(200);

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();

        // 200ms / 16ms ≈ 13 帧
        CLogAssert.assertTrue("ClientA 尚未收到",
            harness.clientA.getNetworkObject(netId) == null);

        int frames = harness.tickUntil(
            () -> harness.clientA.getNetworkObject(netId) != null, 30);

        CLogAssert.assertTrue("ClientA 在 30 帧内收到 Spawn（实际 " + frames + " 帧）",
            frames >= 0);
        CLogAssert.assertTrue("ClientB 也收到了",
            harness.clientB.getNetworkObject(netId) != null);

        System.out.println("═══ [通过] 200ms 高 Ping Spawn（" + frames + " 帧后到达）═══");
    }

    /** 200ms 高延迟下，连续多次状态变更仍然最终一致 */
    @Test
    public void testHighPing200ms_multipleUpdatesEventuallyConsistent() {
        System.out.println("═══ [三端] 200ms 高 Ping 多次状态变更 ═══");

        // 零延迟 Spawn
        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);

        // 设置 200ms 高延迟
        harness.setLatency(200);

        // 连续多次修改并 tick
        serverLogic.posX.setValue(10f);
        harness.forceTick();
        serverLogic.posX.setValue(20f);
        harness.forceTick();
        serverLogic.posX.setValue(30f);
        harness.forceTick();

        // 推进足够帧让所有延迟包到达（200ms + 3 次 tick 间隔）
        harness.tickFrames(20); // 20 * 16 = 320ms >> 200ms

        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        // 最终值应该是最后一次设置的 30f（因为所有包都按顺序到达，最终覆盖为最新值）
        CLogAssert.assertEquals("ClientA 最终值一致", 30f, clientALogic.posX.getValue());

        TankBehaviour clientBLogic = (TankBehaviour) harness.clientB.getNetworkObject(netId).getBehaviours().get(0);
        CLogAssert.assertEquals("ClientB 最终值一致", 30f, clientBLogic.posX.getValue());

        System.out.println("═══ [通过] 200ms 高 Ping 多次状态变更 ═══");
    }

    /** 500ms 极端延迟 — 验证系统不会崩溃，数据最终可达 */
    @Test
    public void testExtremePing500ms_stillWorking() {
        System.out.println("═══ [三端] 500ms 极端延迟 ═══");
        harness.setLatency(500);

        NetworkObject serverTank = harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        int netId = (int) serverTank.getNetworkId();

        // 500ms / 16ms ≈ 32 帧
        int frames = harness.tickUntil(
            () -> harness.clientA.getNetworkObject(netId) != null, 50);

        CLogAssert.assertTrue("极端延迟下 ClientA 仍收到 Spawn（" + frames + " 帧）",
            frames >= 0);
        CLogAssert.assertTrue("极端延迟下 ClientB 仍收到 Spawn",
            harness.clientB.getNetworkObject(netId) != null);

        // 验证状态同步也能通过
        TankBehaviour serverLogic = (TankBehaviour) serverTank.getBehaviours().get(0);
        serverLogic.playerName.setValue("高延迟玩家");
        harness.forceTick();
        harness.tickFrames(40); // 640ms >> 500ms

        TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
        CLogAssert.assertEquals("极端延迟下状态仍最终同步", "高延迟玩家", clientALogic.playerName.getValue());

        System.out.println("═══ [通过] 500ms 极端延迟 ═══");
    }

    // ╔══════════════════════════════════════════════════════════╗
    // ║               四、断线与异常场景                            ║
    // ╚══════════════════════════════════════════════════════════╝

    /** ClientB 断线后，Server 和 ClientA 仍正常工作 */
    @Test
    public void testDisconnect_otherClientUnaffected() {
        System.out.println("═══ [三端] 断线不影响其他客户端 ═══");

        // Spawn 两个坦克
        NetworkObject tankA = harness.server.spawnWithPrefab(TANK_PREFAB_ID, 0);
        NetworkObject tankB = harness.server.spawnWithPrefab(TANK_PREFAB_ID, 1);
        int netIdA = (int) tankA.getNetworkId();

        CLogAssert.assertEquals("初始 ClientA 有 2 个实体", 2, harness.clientA.getNetworkObjectCount());

        // ClientB 断线
        harness.disconnectClientB();

        // Server tick 处理断开事件（despawnByOwner）
        harness.forceTick();

        // ClientA 应仍能正常接收状态同步
        TankBehaviour serverLogicA = (TankBehaviour) tankA.getBehaviours().get(0);
        serverLogicA.posX.setValue(500f);
        harness.forceTick();

        TankBehaviour clientALogicA = (TankBehaviour) harness.clientA.getNetworkObject(netIdA).getBehaviours().get(0);
        CLogAssert.assertEquals("ClientA 仍正常同步", 500f, clientALogicA.posX.getValue());

        System.out.println("═══ [通过] 断线不影响其他客户端 ═══");
    }

    /** 断线后 Server 移除该 Client 拥有的所有实体 */
    @Test
    public void testDisconnect_serverCleansUpOwnedEntities() {
        System.out.println("═══ [三端] 断线后 Server 清理实体 ═══");

        // 为 ClientB (clientId=1) 创建坦克
        harness.server.spawnWithPrefab(TANK_PREFAB_ID, 1);
        // Server 拥有的坦克
        harness.server.spawnWithPrefab(TANK_PREFAB_ID, -1);

        CLogAssert.assertEquals("Server 初始 2 个实体", 2, harness.server.getNetworkObjectCount());

        // ClientB 断线
        harness.disconnectClientB();
        // forceTick 让 NetworkManager 处理 pendingDisconnects
        harness.forceTick();

        // Server 应移除 ClientB 拥有的实体（owner=1），保留 Server 拥有的（owner=-1）
        CLogAssert.assertEquals("Server 应剩 1 个实体（Server 自有的）", 1, harness.server.getNetworkObjectCount());

        System.out.println("═══ [通过] 断线后 Server 清理实体 ═══");
    }

    // ╔══════════════════════════════════════════════════════════╗
    // ║               五、综合压力测试                              ║
    // ╚══════════════════════════════════════════════════════════╝

    /** 多实体 + 40ms 延迟 + 状态同步，验证无数据错乱 */
    @Test
    public void testStress_multipleEntitiesWithLatency() {
        System.out.println("═══ [三端] 多实体压力测试 ═══");

        // 零延迟 Spawn 5 个坦克
        NetworkObject[] tanks = new NetworkObject[5];
        for (int i = 0; i < 5; i++) {
            tanks[i] = harness.server.spawnWithPrefab(TANK_PREFAB_ID, i % 2);
        }

        CLogAssert.assertEquals("ClientA 看到 5 个实体", 5, harness.clientA.getNetworkObjectCount());
        CLogAssert.assertEquals("ClientB 看到 5 个实体", 5, harness.clientB.getNetworkObjectCount());

        // 设置 40ms 延迟
        harness.setLatency(40);

        // 同时修改所有坦克的状态
        for (int i = 0; i < 5; i++) {
            TankBehaviour logic = (TankBehaviour) tanks[i].getBehaviours().get(0);
            logic.posX.setValue(i * 100f);
            logic.hp.setValue(100 - i * 10);
        }
        harness.forceTick();

        // 推帧让延迟包全部到达
        harness.tickFrames(10);

        // 验证所有数据正确（无交叉错乱）
        for (int i = 0; i < 5; i++) {
            int netId = (int) tanks[i].getNetworkId();
            TankBehaviour clientALogic = (TankBehaviour) harness.clientA.getNetworkObject(netId).getBehaviours().get(0);
            CLogAssert.assertEquals("坦克" + i + " ClientA posX", i * 100f, clientALogic.posX.getValue());
            CLogAssert.assertEquals("坦克" + i + " ClientA hp", (Integer)(100 - i * 10), clientALogic.hp.getValue());
        }

        System.out.println("═══ [通过] 多实体压力测试 ═══");
    }

    /** 测试 harness 状态摘要输出不崩溃 */
    @Test
    public void testStatusSummary() {
        harness.server.spawnWithPrefab(TANK_PREFAB_ID);
        harness.setLatency(40);
        String summary = harness.getStatusSummary();
        CLogAssert.assertTrue("状态摘要非空", summary != null && !summary.isEmpty());
        System.out.println("状态摘要: " + summary);
    }
}
