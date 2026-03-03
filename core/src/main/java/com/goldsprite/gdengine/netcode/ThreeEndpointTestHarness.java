package com.goldsprite.gdengine.netcode;

import java.util.function.BooleanSupplier;

/**
 * 三端测试工具 — 1 Server + 2 Client 的内存联网测试环境。
 * <p>
 * 无需 Mockito，使用 {@link SimulatedTransport} 在同一 JVM 内模拟真实网络拓扑。
 * 支持可配置的网络延迟（模拟真实网速 40ms 或高 ping 边界 200ms+）。
 *
 * <b>使用示例:</b>
 * <pre>
 * ThreeEndpointTestHarness harness = new ThreeEndpointTestHarness();
 * harness.setup(tankFactory);
 * harness.setLatency(40); // 模拟 40ms 单程延迟
 *
 * // Server spawn 实体
 * NetworkObject tank = harness.server.spawnWithPrefab(1, 0);
 *
 * // 推进帧直到 Client A 收到 spawn
 * harness.tickUntil(() -> harness.clientA.getNetworkObjectCount() > 0, 100);
 *
 * // 断言
 * assert harness.clientA.getNetworkObjectCount() == 1;
 * assert harness.clientB.getNetworkObjectCount() == 1;
 *
 * harness.teardown();
 * </pre>
 *
 * <b>延迟测试:</b>
 * <pre>
 * harness.setLatency(200); // 高 ping
 * // 数据包需要经过 flush 投递后才能被接收方处理
 * harness.tickFrames(10); // 推进 10 帧（每帧 ~16ms）
 * </pre>
 */
public class ThreeEndpointTestHarness {

    // ══════════════ 公共访问: 三端 Manager ══════════════
    /** 服务器端 NetworkManager */
    public NetworkManager server;
    /** 客户端 A 的 NetworkManager (clientId = 0) */
    public NetworkManager clientA;
    /** 客户端 B 的 NetworkManager (clientId = 1) */
    public NetworkManager clientB;

    // ══════════════ 公共访问: 三端 Transport ══════════════
    /** 服务器端传输 */
    public SimulatedTransport serverTransport;
    /** 客户端 A 传输 */
    public SimulatedTransport clientATransport;
    /** 客户端 B 传输 */
    public SimulatedTransport clientBTransport;

    // ══════════════ 配置 ══════════════
    /** 模拟帧间隔（毫秒），默认 16ms (~60fps) */
    private long frameDeltaMs = 16;
    /** 网络 tick rate，默认 60Hz */
    private int tickRate = 60;

    /**
     * 初始化三端环境。
     * <p>
     * 创建 1 Server + 2 Client，自动完成连接握手。
     * 调用此方法后可立即注册预制体、spawn 实体等。
     */
    public void setup() {
        // ── 创建传输层 ──
        serverTransport = SimulatedTransport.createServer();
        clientATransport = SimulatedTransport.createClient(0);
        clientBTransport = SimulatedTransport.createClient(1);

        // ── 创建 NetworkManager ──
        server = new NetworkManager();
        clientA = new NetworkManager();
        clientB = new NetworkManager();

        // ── 绑定传输层 ──
        server.setTransport(serverTransport);
        clientA.setTransport(clientATransport);
        clientB.setTransport(clientBTransport);

        // ── 设置 tick rate ──
        server.setTickRate(tickRate);

        // ── 建立连接（Server ↔ ClientA, Server ↔ ClientB） ──
        serverTransport.connectClient(0, clientATransport);
        serverTransport.connectClient(1, clientBTransport);
    }

    /**
     * 在三端同时注册预制体工厂。
     * 确保 Server 和所有 Client 都注册了相同的工厂，否则 Spawn 会失败。
     */
    public void registerPrefab(int prefabId, NetworkPrefabFactory factory) {
        server.registerPrefab(prefabId, factory);
        clientA.registerPrefab(prefabId, factory);
        clientB.registerPrefab(prefabId, factory);
    }

    /**
     * 设置所有传输层的单程延迟（毫秒）。
     * <ul>
     *   <li>0 = 即时投递（适合逻辑正确性测试）</li>
     *   <li>20~40 = 模拟正常网速</li>
     *   <li>100~200 = 模拟高 ping 边界</li>
     *   <li>500+ = 极端延迟测试</li>
     * </ul>
     */
    public void setLatency(long ms) {
        serverTransport.setLatencyMs(ms);
        clientATransport.setLatencyMs(ms);
        clientBTransport.setLatencyMs(ms);
    }

    /** 设置模拟帧间隔（毫秒），影响 tickFrames 的推进步长 */
    public void setFrameDeltaMs(long ms) {
        this.frameDeltaMs = Math.max(1, ms);
    }

    /** 设置网络 tick rate（Hz） */
    public void setTickRate(int hz) {
        this.tickRate = hz;
        if (server != null) server.setTickRate(hz);
    }

    // ══════════════ 帧推进 ══════════════

    /**
     * 推进一帧: Server tick + 所有传输层 flush。
     * <p>
     * 模拟一帧的完整流程:
     * <ol>
     *   <li>Server tick（序列化脏数据、广播）</li>
     *   <li>全部传输层推进 frameDeltaMs（延迟包投递）</li>
     * </ol>
     */
    public void tickOneFrame() {
        float deltaSec = frameDeltaMs / 1000f;
        // Server tick（产生状态同步包）
        server.tick(deltaSec);
        // 推进所有传输层的时间轴（投递延迟包）
        SimulatedTransport.flushAll(frameDeltaMs, serverTransport, clientATransport, clientBTransport);
    }

    /**
     * 强制执行一次 Server 网络同步 + flush，不受 tickRate 累加器限制。
     * 适合测试中需要精确控制"何时同步"的场景。
     */
    public void forceTick() {
        // 无参 tick() 直接触发一次 tickInternal()，绕过累加器
        server.tick();
        SimulatedTransport.flushAll(frameDeltaMs, serverTransport, clientATransport, clientBTransport);
    }

    /**
     * 推进指定帧数。
     * @param frames 要推进的帧数
     */
    public void tickFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            tickOneFrame();
        }
    }

    /**
     * 循环推进帧直到条件满足或达到最大帧数。
     * <p>
     * 用于等待异步网络事件（如 Spawn 包到达客户端、状态同步完成等），
     * 替代传统的 Thread.sleep + 超时断言。
     *
     * @param condition 停止条件（返回 true 时停止）
     * @param maxFrames 最大推进帧数（防止死循环）
     * @return 实际推进的帧数（-1 = 条件未满足，已达上限）
     */
    public int tickUntil(BooleanSupplier condition, int maxFrames) {
        for (int i = 0; i < maxFrames; i++) {
            if (condition.getAsBoolean()) return i;
            tickOneFrame();
        }
        // 最后再检查一次（最后一帧 flush 后的状态）
        return condition.getAsBoolean() ? maxFrames : -1;
    }

    /**
     * 模拟指定毫秒数的时间流逝（按帧步进）。
     * @param totalMs 总时长（毫秒）
     * @return 实际推进的帧数
     */
    public int tickDuration(long totalMs) {
        int frames = (int) Math.ceil((double) totalMs / frameDeltaMs);
        tickFrames(frames);
        return frames;
    }

    // ══════════════ 断线模拟 ══════════════

    /**
     * 模拟客户端 A 断线。
     * 从 Server 端移除连接，触发 onClientDisconnected 回调。
     */
    public void disconnectClientA() {
        serverTransport.disconnectClient(0);
    }

    /**
     * 模拟客户端 B 断线。
     */
    public void disconnectClientB() {
        serverTransport.disconnectClient(1);
    }

    /**
     * 重新连接客户端 A（模拟重连）。
     */
    public void reconnectClientA() {
        clientATransport = SimulatedTransport.createClient(0);
        clientA = new NetworkManager();
        clientA.setTransport(clientATransport);
        // 重新注册预制体（需要外部在 reconnect 后重新调用 registerPrefab）
        serverTransport.connectClient(0, clientATransport);
    }

    // ══════════════ 清理 ══════════════

    /**
     * 断开所有连接，释放资源。
     * 测试结束后必须调用。
     */
    public void teardown() {
        if (serverTransport != null) serverTransport.disconnect();
        if (clientATransport != null) clientATransport.disconnect();
        if (clientBTransport != null) clientBTransport.disconnect();
        server = null;
        clientA = null;
        clientB = null;
        serverTransport = null;
        clientATransport = null;
        clientBTransport = null;
    }

    // ══════════════ 调试辅助 ══════════════

    /** 打印三端状态摘要 */
    public String getStatusSummary() {
        return String.format(
            "[Server: %d 实体] [ClientA: %d 实体] [ClientB: %d 实体] [延迟: %dms]",
            server != null ? server.getNetworkObjectCount() : 0,
            clientA != null ? clientA.getNetworkObjectCount() : 0,
            clientB != null ? clientB.getNetworkObjectCount() : 0,
            serverTransport != null ? serverTransport.getLatencyMs() : 0
        );
    }
}
