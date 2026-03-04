package com.goldsprite.gdengine.netcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.goldsprite.gdengine.log.DLog;

/**
 * 可靠 UDP 传输层（装饰器模式）。
 * <p>
 * 在原始 {@link UdpSocketTransport} 之上添加可靠性保障：
 * <ul>
 *   <li>序列号 (16-bit 循环) 用于乱序检测和去重</li>
 *   <li>ACK 确认机制</li>
 *   <li>超时重传（默认 200ms，最多 5 次）</li>
 *   <li>双通道: Reliable（需确认）和 Unreliable（透传）</li>
 * </ul>
 * <p>
 * 封包格式:
 * <pre>
 * [channelType: 1B] [seqNum: 2B] [ackNum: 2B] [payload: NB]
 * </pre>
 * channelType: 0x00=Unreliable, 0x01=Reliable, 0x02=ACK
 */
public class ReliableUdpTransport implements Transport {

    // 通道类型常量
    public static final byte CHANNEL_UNRELIABLE = 0x00;
    public static final byte CHANNEL_RELIABLE = 0x01;
    public static final byte CHANNEL_ACK = 0x02;
    public static final byte CHANNEL_PING = 0x03;
    public static final byte CHANNEL_PONG = 0x04;

    // 序列号空间: 16-bit (0 ~ 65535)
    private static final int SEQ_MAX = 65536;
    private static final int SEQ_HALF = SEQ_MAX / 2;

    // ── 常规包重传参数（固定间隔，快速判定断线） ──
    private static final long RETRANSMIT_TIMEOUT_MS = 200;
    private static final int MAX_RETRANSMIT_COUNT = 5;

    // ── 首包(seq=0)重传参数（指数退避，容忍对端未就绪） ──
    private static final long FIRST_PKT_BASE_TIMEOUT_MS = 200;
    private static final int FIRST_PKT_MAX_RETRANSMIT = 10;
    private static final long FIRST_PKT_MAX_TIMEOUT_MS = 3000;

    // 被装饰的原始 UDP 传输层
    private final UdpSocketTransport rawTransport;

    // 上层数据接收回调
    private TransportReceiveCallback userCallback;

    // 连接事件监听器
    private NetworkConnectionListener userConnectionListener;

    // ── 发送端 ──
    /** 发送端递增序列号 */
    private int sendSeqNum = 0;
    /** 待确认包缓冲区（seqNum → PendingEntry） */
    private final PendingPacketBuffer pendingBuffer = new PendingPacketBuffer();

    // ── 接收端 ──
    /** 接收端序列号追踪器（Client 端使用：只连一个 Server） */
    private final ReceiveSequenceTracker receiveTracker = new ReceiveSequenceTracker();
    /** Server 端按客户端隔离的接收序列号追踪器（clientId → Tracker） */
    private final Map<Integer, ReceiveSequenceTracker> clientReceiveTrackers = new ConcurrentHashMap<>();
    /** 最近收到的对方序列号（用于 ACK 回复） */
    private volatile int lastReceivedSeqNum = 0;

    // ── 心跳 / Ping-Pong ──
    /** 最近一次收到任何有效包的时间戳(ms) */
    private volatile long lastRecvTimeMs = System.currentTimeMillis();
    /** 最近一次发送 Ping 的时间戳(ms) */
    private volatile long lastPingSentTimeMs = 0;
    /** 当前往返延迟(ms)，-1 表示尚未测量 */
    private volatile long currentPingMs = -1;
    /** Ping 发送间隔(ms) */
    private static final long PING_INTERVAL_MS = 1000;
    /** 每个客户端的最后接收时间（Server 端跟踪心跳） */
    private final Map<Integer, Long> clientLastRecvTimeMs = new ConcurrentHashMap<>();

    public ReliableUdpTransport(UdpSocketTransport rawTransport) {
        this.rawTransport = rawTransport;
    }

    // ========== Transport 接口实现 ==========

    @Override
    public void setReceiveCallback(TransportReceiveCallback callback) {
        this.userCallback = callback;
        // 拦截原始传输层的回调，在中间层处理可靠性协议
        rawTransport.setReceiveCallback(this::onRawReceive);
    }

    @Override
    public void setConnectionListener(NetworkConnectionListener listener) {
        this.userConnectionListener = listener;
        // 拦截连接事件，管理按客户端隔离的可靠层状态
        rawTransport.setConnectionListener(new NetworkConnectionListener() {
            @Override
            public void onClientConnected(int clientId) {
                // 为新客户端创建独立的接收序列号追踪器（确保重连客户端从 seq=0 开始不被当作旧包）
                clientReceiveTrackers.put(clientId, new ReceiveSequenceTracker());
                if (listener != null) listener.onClientConnected(clientId);
            }
            @Override
            public void onClientDisconnected(int clientId) {
                // 清理断开客户端的可靠层状态
                clientReceiveTrackers.remove(clientId);
                clientLastRecvTimeMs.remove(clientId);
                if (listener != null) listener.onClientDisconnected(clientId);
            }
        });
    }

    @Override
    public void startServer(int port) {
        rawTransport.setReceiveCallback(this::onRawReceive);
        rawTransport.startServer(port);
    }

    @Override
    public void connect(String ip, int port) {
        rawTransport.setReceiveCallback(this::onRawReceive);
        rawTransport.connect(ip, port);
    }

    @Override
    public void disconnect() {
        rawTransport.disconnect();
        pendingBuffer.clear();
        clientReceiveTrackers.clear();
    }

    /**
     * 断开指定客户端的连接（Server 端调用）。
     * <p>
     * 向客户端发送断线通知，并清理该客户端的可靠层状态。
     *
     * @param clientId 要断开的客户端 ID
     */
    public void disconnectClient(int clientId) {
        rawTransport.disconnectClient(clientId);
        clientReceiveTrackers.remove(clientId);
        clientLastRecvTimeMs.remove(clientId);
    }

    @Override
    public void sendToClient(int clientId, byte[] payload) {
        byte[] wrapped = wrapPayload(payload);
        rawTransport.sendToClient(clientId, wrapped);
    }

    @Override
    public void sendToServer(byte[] payload) {
        byte[] wrapped = wrapPayload(payload);
        rawTransport.sendToServer(wrapped);
    }

    @Override
    public void broadcast(byte[] payload) {
        byte[] wrapped = wrapPayload(payload);
        rawTransport.broadcast(wrapped);
    }

    @Override
    public boolean isServer() {
        return rawTransport.isServer();
    }

    @Override
    public boolean isClient() {
        return rawTransport.isClient();
    }

    // ========== 可靠层核心逻辑 ==========

    /**
     * 根据 payload 内容自动选择通道并编码。
     * 业务层封包的前 4 字节是 packetType（0x10/0x11/0x12/0x20/0x21）。
     */
    private byte[] wrapPayload(byte[] payload) {
        if (payload.length < 4) {
            // 太短，无法解析 packetType，走 Unreliable
            return encodeUnreliablePacket(payload);
        }

        // 读取前 4 字节作为 packetType
        int packetType = ((payload[0] & 0xFF) << 24)
                       | ((payload[1] & 0xFF) << 16)
                       | ((payload[2] & 0xFF) << 8)
                       | (payload[3] & 0xFF);

        if (isReliablePacketType(packetType)) {
            int seq = nextSeqNum();
            byte[] packet = encodeReliablePacket(seq, lastReceivedSeqNum, payload);
            pendingBuffer.add(seq, packet, System.currentTimeMillis());
            return packet;
        } else {
            return encodeUnreliablePacket(payload);
        }
    }

    /**
     * 原始传输层收到数据后的拦截处理。
     * 解析可靠层头部，根据通道类型分别处理。
     * 外层 try-catch 保护 IO 线程不会因单个损坏包崩溃。
     */
    private void onRawReceive(byte[] rawData, int clientId) {
        // 任何有效接收都刷新心跳时间戳
        long now = System.currentTimeMillis();
        lastRecvTimeMs = now;
        if (rawTransport.isServer() && clientId >= 0) {
            clientLastRecvTimeMs.put(clientId, now);
        }

        try {
            onRawReceiveInternal(rawData, clientId);
        } catch (Exception e) {
            DLog.logErr("[ReliableUDP] 接收处理异常（已安全忽略）: " + e.getClass().getSimpleName()
                + " - " + e.getMessage() + " | clientId=" + clientId
                + ", rawLen=" + (rawData != null ? rawData.length : 0));
        }
    }

    /** 内部接收分发（由 onRawReceive try-catch 保护） */
    private void onRawReceiveInternal(byte[] rawData, int clientId) {
        if (rawData == null || rawData.length < 1) return;

        byte channelType = rawData[0];

        switch (channelType) {
            case CHANNEL_UNRELIABLE: {
                // Unreliable: 去掉 1 字节头，直接推送给上层
                byte[] payload = new byte[rawData.length - 1];
                System.arraycopy(rawData, 1, payload, 0, payload.length);
                if (userCallback != null) {
                    userCallback.onReceiveData(payload, clientId);
                }
                break;
            }

            case CHANNEL_RELIABLE: {
                if (rawData.length < 5) return; // 头部不完整
                // 解析 seqNum 和 ackNum
                int seqNum = ((rawData[1] & 0xFF) << 8) | (rawData[2] & 0xFF);
                int ackNum = ((rawData[3] & 0xFF) << 8) | (rawData[4] & 0xFF);

                // 处理对方捎带的 ACK
                pendingBuffer.ack(ackNum);

                // 选择对应的序列号追踪器（Server 端按客户端隔离，Client 端使用全局追踪器）
                ReceiveSequenceTracker tracker;
                if (rawTransport.isServer() && clientId >= 0) {
                    tracker = clientReceiveTrackers.get(clientId);
                    if (tracker == null) {
                        // 安全兜底：如果追踪器不存在（理论上不应该发生），动态创建
                        tracker = new ReceiveSequenceTracker();
                        clientReceiveTrackers.put(clientId, tracker);
                    }
                } else {
                    tracker = receiveTracker;
                }

                // 乱序/重复检测
                if (!tracker.accept(seqNum)) {
                    // 旧包或重复包，但仍需回复 ACK（避免对方无限重传）
                    sendAck(seqNum, clientId);
                    return;
                }

                // 更新最近收到的序列号
                lastReceivedSeqNum = seqNum;

                // 回复 ACK
                sendAck(seqNum, clientId);

                // 提取 payload 推送给上层
                byte[] payload = new byte[rawData.length - 5];
                System.arraycopy(rawData, 5, payload, 0, payload.length);
                if (userCallback != null) {
                    userCallback.onReceiveData(payload, clientId);
                }
                break;
            }

            case CHANNEL_ACK: {
                if (rawData.length < 3) return;
                int ackedSeq = ((rawData[1] & 0xFF) << 8) | (rawData[2] & 0xFF);
                pendingBuffer.ack(ackedSeq);
                break;
            }

            case CHANNEL_PING: {
                // Server 收到 Ping，回复 Pong（原样回传时间戳）
                if (rawData.length >= 9) {
                    byte[] pong = new byte[9];
                    System.arraycopy(rawData, 0, pong, 0, 9);
                    pong[0] = CHANNEL_PONG;
                    rawTransport.sendToClient(clientId, pong);
                }
                break;
            }

            case CHANNEL_PONG: {
                // Client 收到 Pong，计算 RTT
                if (rawData.length >= 9 && !rawTransport.isServer()) {
                    long sentTime = bytesToLong(rawData, 1);
                    currentPingMs = System.currentTimeMillis() - sentTime;
                }
                break;
            }

            default:
                // 未知通道类型，忽略
                DLog.logErr("[ReliableUDP] 未知通道类型: 0x" + Integer.toHexString(channelType & 0xFF));
                break;
        }
    }

    /** 发送 ACK 包: [0x02][seqNum: 2B] */
    private void sendAck(int seqNum, int clientId) {
        byte[] ackPacket = new byte[3];
        ackPacket[0] = CHANNEL_ACK;
        ackPacket[1] = (byte) ((seqNum >> 8) & 0xFF);
        ackPacket[2] = (byte) (seqNum & 0xFF);

        if (rawTransport.isServer()) {
            rawTransport.sendToClient(clientId, ackPacket);
        } else {
            rawTransport.sendToServer(ackPacket);
        }
    }

    /** 分配下一个序列号（16-bit 循环） */
    private synchronized int nextSeqNum() {
        int seq = sendSeqNum;
        sendSeqNum = (sendSeqNum + 1) % SEQ_MAX;
        return seq;
    }

    /**
     * 每帧调用: 检查超时重传。
     * 由 NetworkManager 或游戏层在主循环中调用。
     */
    public void tickReliable() {
        // Client 端定期发送 Ping（维持心跳 + 测量 RTT）
        sendPingIfNeeded();

        long now = System.currentTimeMillis();
        List<PendingEntry> entries = pendingBuffer.getAllPending();

        for (PendingEntry entry : entries) {
            boolean isFirstPacket = (entry.seqNum == 0);
            long timeout;
            int maxRetries;

            if (isFirstPacket) {
                // 首包: 指数退避，容忍对端启动延迟
                timeout = Math.min(
                    FIRST_PKT_BASE_TIMEOUT_MS * (1L << Math.min(entry.retransmitCount, 10)),
                    FIRST_PKT_MAX_TIMEOUT_MS);
                maxRetries = FIRST_PKT_MAX_RETRANSMIT;
            } else {
                // 常规包: 固定间隔，快速判定丢包/断线
                timeout = RETRANSMIT_TIMEOUT_MS;
                maxRetries = MAX_RETRANSMIT_COUNT;
            }

            if (now - entry.lastSendTime < timeout) continue; // 未超时，跳过

            if (entry.retransmitCount >= maxRetries) {
                DLog.logT("Netcode", "[ReliableUDP] 包 seq=" + entry.seqNum
                    + " 达到最大重传次数(" + maxRetries + ")，放弃");
                pendingBuffer.remove(entry.seqNum);
                continue;
            }

            // 重传
            if (rawTransport.isServer()) {
                rawTransport.broadcast(entry.packetData);
            } else {
                rawTransport.sendToServer(entry.packetData);
            }
            pendingBuffer.markRetransmitted(entry.seqNum, now);
        }
    }

    // ========== 心跳 / Ping-Pong ==========

    /** Client 端定期发送 Ping 包，用于测量 RTT 和维持心跳 */
    private void sendPingIfNeeded() {
        if (rawTransport.isServer()) return;
        long now = System.currentTimeMillis();
        if (now - lastPingSentTimeMs >= PING_INTERVAL_MS) {
            lastPingSentTimeMs = now;
            byte[] ping = new byte[9];
            ping[0] = CHANNEL_PING;
            longToBytes(now, ping, 1);
            rawTransport.sendToServer(ping);
        }
    }

    /** 获取当前 Ping 延迟(ms)，未测量时返回 -1 */
    public long getPingMs() { return currentPingMs; }

    /** 获取距离上次收到任何数据的时间(ms) */
    public long getTimeSinceLastRecvMs() { return System.currentTimeMillis() - lastRecvTimeMs; }

    /**
     * 客户端重连: 重新创建底层 socket 并发送握手。
     * 重置可靠层接收状态（序列号追踪器），确保重连后不会因序列号不匹配而丢弃有效数据。
     */
    public void reconnect() {
        rawTransport.reconnect();
        // 重置可靠层状态，确保重连后序列号同步
        pendingBuffer.clear();
        receiveTracker.reset();
        sendSeqNum = 0;
        lastReceivedSeqNum = 0;
        // 刷新心跳时间戳，避免重连后立即触发心跳超时
        lastRecvTimeMs = System.currentTimeMillis();
    }

    /** 获取指定客户端距离上次收到数据的时间(ms)（Server 端） */
    public long getClientTimeSinceLastRecvMs(int clientId) {
        Long t = clientLastRecvTimeMs.get(clientId);
        return t == null ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    /**
     * 检查并处理心跳超时的客户端（Server 端）。
     * 超时的客户端会被标记为非活跃并触发 onClientDisconnected 回调。
     * @param timeoutMs 超时阈值(毫秒)
     */
    public void checkHeartbeatTimeouts(long timeoutMs) {
        if (!rawTransport.isServer()) return;
        long now = System.currentTimeMillis();
        java.util.List<Integer> timedOut = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Long> entry : clientLastRecvTimeMs.entrySet()) {
            if (now - entry.getValue() > timeoutMs) {
                timedOut.add(entry.getKey());
            }
        }
        for (int cid : timedOut) {
            clientLastRecvTimeMs.remove(cid);
            clientReceiveTrackers.remove(cid);
            rawTransport.deactivateClient(cid);
            DLog.logT("Netcode", "[ReliableUDP] 客户端 #" + cid + " 心跳超时，触发断线");
            if (userConnectionListener != null) {
                userConnectionListener.onClientDisconnected(cid);
            }
        }
    }

    /** long → byte[8] 大端序 */
    private static void longToBytes(long value, byte[] dest, int offset) {
        for (int i = 7; i >= 0; i--) {
            dest[offset + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    /** byte[8] 大端序 → long */
    private static long bytesToLong(byte[] src, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (src[offset + i] & 0xFF);
        }
        return value;
    }

    // ========== 转发方法: 暴露底层 UdpSocketTransport 的特性 ==========

    /** 获取底层 UDP 传输被分配的 clientId */
    public int getAssignedClientId() {
        return rawTransport.getAssignedClientId();
    }

    /** 获取当前活跃客户端数 */
    public int getActiveClientCount() {
        return rawTransport.getActiveClientCount();
    }

    /** 获取当前活跃客户端 ID 集合 */
    public java.util.Set<Integer> getActiveClientIds() {
        return rawTransport.getActiveClientIds();
    }

    // ========== 静态工具方法 ==========

    /**
     * 判断序列号 a 是否新于 b（支持 16-bit 循环）。
     * 使用半空间比较法: 如果 (a - b) mod 65536 < 32768，则 a 新于 b。
     */
    public static boolean isSeqNewer(int a, int b) {
        if (a == b) return false;
        int diff = (a - b + SEQ_MAX) % SEQ_MAX;
        return diff > 0 && diff < SEQ_HALF;
    }

    /**
     * 判断业务封包类型是否需要走 Reliable 通道。
     * 状态同步 (0x10) → Unreliable，其他关键封包 → Reliable。
     */
    public static boolean isReliablePacketType(int packetType) {
        switch (packetType) {
            case 0x11: // Spawn
            case 0x12: // Despawn
            case 0x20: // ServerRpc
            case 0x21: // ClientRpc
                return true;
            case 0x10: // 状态同步
            default:
                return false;
        }
    }

    /**
     * 编码 Reliable 封包: [0x01][seqNum: 2B][ackNum: 2B][payload]
     */
    public static byte[] encodeReliablePacket(int seqNum, int ackNum, byte[] payload) {
        byte[] packet = new byte[5 + payload.length];
        packet[0] = CHANNEL_RELIABLE;
        packet[1] = (byte) ((seqNum >> 8) & 0xFF);
        packet[2] = (byte) (seqNum & 0xFF);
        packet[3] = (byte) ((ackNum >> 8) & 0xFF);
        packet[4] = (byte) (ackNum & 0xFF);
        System.arraycopy(payload, 0, packet, 5, payload.length);
        return packet;
    }

    /**
     * 编码 Unreliable 封包: [0x00][payload]
     */
    public static byte[] encodeUnreliablePacket(byte[] payload) {
        byte[] packet = new byte[1 + payload.length];
        packet[0] = CHANNEL_UNRELIABLE;
        System.arraycopy(payload, 0, packet, 1, payload.length);
        return packet;
    }

    // ========== 内部数据结构 ==========

    /** 待确认包条目 */
    public static class PendingEntry {
        public final int seqNum;
        public final byte[] packetData;
        public long lastSendTime;
        public int retransmitCount;

        public PendingEntry(int seqNum, byte[] packetData, long sendTime) {
            this.seqNum = seqNum;
            this.packetData = packetData;
            this.lastSendTime = sendTime;
            this.retransmitCount = 0;
        }
    }

    /**
     * 待确认包缓冲区。
     * 线程安全: 发送在主线程，ACK 回调在 IO 线程。
     */
    public static class PendingPacketBuffer {
        private final Map<Integer, PendingEntry> entries = new ConcurrentHashMap<>();

        public void add(int seqNum, byte[] packetData, long sendTime) {
            entries.put(seqNum, new PendingEntry(seqNum, packetData, sendTime));
        }

        /** 收到 ACK 后移除对应条目 */
        public void ack(int seqNum) {
            entries.remove(seqNum);
        }

        /** 移除指定条目 */
        public void remove(int seqNum) {
            entries.remove(seqNum);
        }

        /** 清空所有待确认包 */
        public void clear() {
            entries.clear();
        }

        /** 当前待确认包数量 */
        public int size() {
            return entries.size();
        }

        /** 获取所有超时的条目（不修改状态） */
        public List<PendingEntry> getTimedOut(long now, long timeoutMs) {
            List<PendingEntry> result = new ArrayList<>();
            for (PendingEntry entry : entries.values()) {
                if (now - entry.lastSendTime >= timeoutMs) {
                    result.add(entry);
                }
            }
            return result;
        }

        /** 获取所有待确认包（用于指数退避逻辑，由调用方判断超时） */
        public List<PendingEntry> getAllPending() {
            return new ArrayList<>(entries.values());
        }

        /** 标记重传（更新发送时间和重传计数） */
        public void markRetransmitted(int seqNum, long now) {
            PendingEntry entry = entries.get(seqNum);
            if (entry != null) {
                entry.lastSendTime = now;
                entry.retransmitCount++;
            }
        }

        /** 检查是否超过最大重传次数 */
        public boolean isMaxRetriesExceeded(int seqNum, int maxRetries) {
            PendingEntry entry = entries.get(seqNum);
            return entry != null && entry.retransmitCount >= maxRetries;
        }
    }

    /**
     * 接收端序列号追踪器（滑动窗口 + 位域去重）。
     * <p>
     * 旧版仅用"最高水位"判断，公网乱序包（如 seq=3 先于 seq=2 到达）会被永久丢弃。
     * 新版使用 WINDOW_SIZE=256 的位域，允许窗口内的乱序包被正确接收。
     */
    public static class ReceiveSequenceTracker {
        /** 滑动窗口大小（支持最多 256 个序列号的乱序容忍） */
        private static final int WINDOW_SIZE = 256;

        /** 最近接受的最大序列号，-1 表示尚未收到任何包 */
        private int highestAccepted = -1;

        /**
         * 位域：记录最近 WINDOW_SIZE 个序列号的接收状态。
         * receivedBits[i] == true 表示 (highestAccepted - WINDOW_SIZE + 1 + i) 已接收。
         */
        private final boolean[] receivedBits = new boolean[WINDOW_SIZE];

        /**
         * 尝试接受一个序列号。
         * @return true 如果是新包（接受），false 如果是旧包或重复（拒绝）
         */
        public boolean accept(int seqNum) {
            if (highestAccepted < 0) {
                // 首个包，直接接受；标记在窗口末端（即 highestAccepted 对应位置）
                highestAccepted = seqNum;
                receivedBits[WINDOW_SIZE - 1] = true;
                return true;
            }

            if (isSeqNewer(seqNum, highestAccepted)) {
                // seqNum 比当前最高更新 → 滑动窗口前移
                int advance = seqDistance(highestAccepted, seqNum);
                slideWindow(advance);
                highestAccepted = seqNum;
                receivedBits[WINDOW_SIZE - 1] = true;
                return true;
            }

            // seqNum <= highestAccepted，检查是否在窗口范围内
            int age = seqDistance(seqNum, highestAccepted);
            if (age >= WINDOW_SIZE) {
                // 太旧，超出窗口范围，拒绝
                return false;
            }

            int index = WINDOW_SIZE - 1 - age;
            if (receivedBits[index]) {
                // 重复包，拒绝
                return false;
            }

            // 窗口内的乱序包，接受
            receivedBits[index] = true;
            return true;
        }

        /** 窗口向前滑动 advance 位 */
        private void slideWindow(int advance) {
            if (advance >= WINDOW_SIZE) {
                // 整个窗口都过期了，清空
                java.util.Arrays.fill(receivedBits, false);
            } else {
                // 左移 advance 位
                System.arraycopy(receivedBits, advance, receivedBits, 0, WINDOW_SIZE - advance);
                java.util.Arrays.fill(receivedBits, WINDOW_SIZE - advance, WINDOW_SIZE, false);
            }
        }

        /**
         * 计算从 from 到 to 的正向距离（支持 16-bit 循环）。
         * 要求 to 在 from 的"前方"或等于 from。
         */
        private static int seqDistance(int from, int to) {
            return (to - from + SEQ_MAX) % SEQ_MAX;
        }

        public int getHighestAccepted() {
            return highestAccepted;
        }

        /** 重置追踪器状态（重连时使用） */
        public void reset() {
            highestAccepted = -1;
            java.util.Arrays.fill(receivedBits, false);
        }
    }
}
