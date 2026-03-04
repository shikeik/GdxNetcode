package com.goldsprite.gdengine.netcode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 模拟网络传输层 — 支持多客户端、可配置延迟的纯内存传输。
 * <p>
 * 与 {@link LocalMemoryTransport} 不同:
 * <ul>
 *   <li>Server 端可同时连接多个 Client（通过 clientId 路由）</li>
 *   <li>支持模拟网络延迟（单程延迟，毫秒级），数据包在延迟到期后才投递</li>
 *   <li>通过 {@link #flush(long)} 推进时间轴，使延迟包按时投递</li>
 * </ul>
 *
 * <b>典型用法（三端测试）:</b>
 * <pre>
 * SimulatedTransport serverT  = SimulatedTransport.createServer();
 * SimulatedTransport clientAT = SimulatedTransport.createClient(0);
 * SimulatedTransport clientBT = SimulatedTransport.createClient(1);
 *
 * serverT.connectClient(0, clientAT);
 * serverT.connectClient(1, clientBT);
 *
 * // 设置单程延迟 40ms
 * serverT.setLatencyMs(40);
 * clientAT.setLatencyMs(40);
 * clientBT.setLatencyMs(40);
 *
 * // 推进时间轴使延迟包投递
 * SimulatedTransport.flushAll(40, serverT, clientAT, clientBT);
 * </pre>
 */
public class SimulatedTransport implements Transport {

    // ── 身份与 ID ──
    private final boolean serverIdentity;
    private final int clientId; // 仅 Client 端有意义

    // ── 回调 ──
    private TransportReceiveCallback receiveCallback;
    private NetworkConnectionListener connectionListener;

    // ── 多客户端路由（Server 端使用） ──
    private final Map<Integer, SimulatedTransport> connectedClients = new HashMap<>();

    // ── 客户端到服务器引用 ──
    private SimulatedTransport serverPeer;

    // ── 延迟模拟 ──
    /** 单程延迟（毫秒），0 = 即时投递 */
    private long latencyMs = 0;
    /** 待投递数据包队列（按投递时间排序） */
    private final Deque<DelayedPacket> pendingQueue = new ArrayDeque<>();
    /** 当前模拟时间（毫秒），由 flush() 推进 */
    private long currentTimeMs = 0;

    // ══════════════ 工厂方法 ══════════════

    /** 创建 Server 端传输 */
    public static SimulatedTransport createServer() {
        return new SimulatedTransport(true, -1);
    }

    /** 创建 Client 端传输，指定 clientId */
    public static SimulatedTransport createClient(int clientId) {
        return new SimulatedTransport(false, clientId);
    }

    private SimulatedTransport(boolean isServer, int clientId) {
        this.serverIdentity = isServer;
        this.clientId = clientId;
    }

    // ══════════════ 配置 ══════════════

    /** 设置单程延迟（毫秒）。发送的数据包需等待此延迟后才会被投递到接收方。 */
    public void setLatencyMs(long ms) {
        this.latencyMs = Math.max(0, ms);
    }

    /** 获取当前单程延迟（毫秒） */
    public long getLatencyMs() {
        return latencyMs;
    }

    // ══════════════ 连接管理 ══════════════

    /**
     * Server 端: 连接一个客户端传输。
     * 双向建立引用，并触发 onClientConnected 回调。
     */
    public void connectClient(int clientId, SimulatedTransport clientTransport) {
        if (!serverIdentity) throw new IllegalStateException("只有 Server 端可以 connectClient");
        connectedClients.put(clientId, clientTransport);
        clientTransport.serverPeer = this;

        // 触发 Server 端的连接事件
        if (connectionListener != null) {
            connectionListener.onClientConnected(clientId);
        }
        // 触发 Client 端的连接事件（通知 clientId 分配）
        if (clientTransport.connectionListener != null) {
            clientTransport.connectionListener.onClientConnected(clientId);
        }
    }

    /**
     * Server 端: 断开指定客户端。
     */
    public void disconnectClient(int clientId) {
        SimulatedTransport client = connectedClients.remove(clientId);
        if (client != null) {
            client.serverPeer = null;
            // 触发断开事件
            if (connectionListener != null) {
                connectionListener.onClientDisconnected(clientId);
            }
        }
    }

    // ══════════════ Transport 接口实现 ══════════════

    @Override
    public void startServer(int port) {
        // 模拟环境无需真实端口
    }

    @Override
    public void connect(String ip, int port) {
        // 模拟环境通过 connectClient() 手动接线
    }

    @Override
    public void disconnect() {
        if (serverIdentity) {
            // Server 端: 断开所有客户端
            for (SimulatedTransport client : connectedClients.values()) {
                client.serverPeer = null;
            }
            connectedClients.clear();
        } else {
            // Client 端: 断开与 Server 的连接
            if (serverPeer != null) {
                serverPeer.connectedClients.remove(clientId);
                serverPeer = null;
            }
        }
    }

    @Override
    public void sendToClient(int targetClientId, byte[] payload) {
        if (!serverIdentity) return;
        SimulatedTransport client = connectedClients.get(targetClientId);
        if (client != null) {
            client.enqueueOrDeliver(payload, -1); // -1 = 来自 Server
        }
    }

    @Override
    public void sendToServer(byte[] payload) {
        if (serverIdentity || serverPeer == null) return;
        serverPeer.enqueueOrDeliver(payload, clientId); // 带上自己的 clientId
    }

    @Override
    public void broadcast(byte[] payload) {
        if (!serverIdentity) return;
        for (Map.Entry<Integer, SimulatedTransport> entry : connectedClients.entrySet()) {
            entry.getValue().enqueueOrDeliver(payload, -1);
        }
    }

    @Override
    public boolean isServer() { return serverIdentity; }

    @Override
    public boolean isClient() { return !serverIdentity; }

    @Override
    public void setReceiveCallback(TransportReceiveCallback callback) {
        this.receiveCallback = callback;
    }

    @Override
    public void setConnectionListener(NetworkConnectionListener listener) {
        this.connectionListener = listener;
    }

    @Override
    public int getLocalPort() {
        return 0; // 模拟环境无真实端口
    }

    // ══════════════ 模拟核心逻辑 ══════════════

    /**
     * 将数据包入队（有延迟时）或立即投递（无延迟时）。
     * 此方法在接收方的传输上调用。
     */
    private void enqueueOrDeliver(byte[] payload, int senderClientId) {
        if (latencyMs <= 0) {
            // 无延迟: 立即投递
            deliverToCallback(payload, senderClientId);
        } else {
            // 有延迟: 放入待投递队列
            pendingQueue.add(new DelayedPacket(payload, senderClientId, currentTimeMs + latencyMs));
        }
    }

    /** 将数据包投递到 receiveCallback */
    private void deliverToCallback(byte[] payload, int senderClientId) {
        if (receiveCallback != null) {
            receiveCallback.onReceiveData(payload, senderClientId);
        }
    }

    /**
     * 推进模拟时间轴，投递所有已到期的延迟包。
     * @param advanceMs 推进的时间（毫秒）
     * @return 本次投递的数据包数量
     */
    public int flush(long advanceMs) {
        currentTimeMs += advanceMs;
        int delivered = 0;
        while (!pendingQueue.isEmpty() && pendingQueue.peek().deliverAtMs <= currentTimeMs) {
            DelayedPacket pkt = pendingQueue.poll();
            deliverToCallback(pkt.payload, pkt.senderClientId);
            delivered++;
        }
        return delivered;
    }

    /** 获取待投递队列中的包数量 */
    public int getPendingCount() {
        return pendingQueue.size();
    }

    /** 重置模拟时间轴（归零） */
    public void resetTime() {
        currentTimeMs = 0;
        pendingQueue.clear();
    }

    // ══════════════ 静态工具 ══════════════

    /**
     * 批量推进所有传输的时间轴。
     * @param advanceMs   推进时间（毫秒）
     * @param transports  所有需要推进的传输实例
     * @return 总共投递的数据包数量
     */
    public static int flushAll(long advanceMs, SimulatedTransport... transports) {
        int total = 0;
        for (SimulatedTransport t : transports) {
            total += t.flush(advanceMs);
        }
        return total;
    }

    // ══════════════ 内部数据类 ══════════════

    /** 延迟数据包 */
    private static class DelayedPacket {
        final byte[] payload;
        final int senderClientId;
        final long deliverAtMs;

        DelayedPacket(byte[] payload, int senderClientId, long deliverAtMs) {
            this.payload = payload;
            this.senderClientId = senderClientId;
            this.deliverAtMs = deliverAtMs;
        }
    }
}
