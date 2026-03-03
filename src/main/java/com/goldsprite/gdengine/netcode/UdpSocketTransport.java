package com.goldsprite.gdengine.netcode;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.goldsprite.gdengine.log.DLog;

/**
 * 基于 Java 原生 DatagramSocket (UDP) 的真实网络传输层实现。
 * <p>
 * 与 LocalMemoryTransport（进程内内存直传）不同，本类通过真实的操作系统 Socket 发送和接收 UDP 数据报，
 * 适用于跨进程 / 跨网络的联机场景。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>Server 端绑定端口并监听，接收来自 Client 的数据，能够广播给所有已知 Client</li>
 *   <li>Client 端向 Server 地址发送数据，同时监听 Server 的回包</li>
 *   <li>使用守护线程异步接收数据，通过 TransportReceiveCallback 回调推送给上层</li>
 *   <li>握手协议：Client 连接时发送 [0xFF,0xFF,0xFF,0xFF] 握手包，Server 据此记录 Client 地址</li>
 * </ul>
 */
public class UdpSocketTransport implements Transport {

    // 身份标识
    private final boolean isServerIdentity;

    // UDP Socket
    private DatagramSocket socket;

    // 数据接收回调
    private TransportReceiveCallback receiveCallback;

    // 连接事件监听器
    private NetworkConnectionListener connectionListener;

    // 接收线程
    private Thread receiveThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Server 记录的所有已连接 Client 地址
    private final List<InetSocketAddress> clientAddresses = new CopyOnWriteArrayList<>();

    // 当前活跃（未断开）的客户端 ID 集合
    private final Set<Integer> activeClientIds = ConcurrentHashMap.newKeySet();

    // Client 记录的 Server 地址
    private InetSocketAddress serverAddress;

    // 最大 UDP 数据报大小（对于游戏 Netcode，一般不超过 MTU，这里保守设为 4096）
    private static final int MAX_PACKET_SIZE = 4096;

    // Socket 接收超时（毫秒）—— 避免 receive() 永久阻塞导致关窗时无法退出
    private static final int SOCKET_TIMEOUT_MS = 500;

    // 握手魔法标识
    private static final byte[] HANDSHAKE_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    // 握手回复魔法标识: [0xFE×4] + [4字节 clientId]
    private static final byte[] HANDSHAKE_REPLY_MAGIC = new byte[]{(byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE};

    // 断开通知魔法标识: [0xFD×4] — Client 主动断开时发送给 Server
    private static final byte[] DISCONNECT_MAGIC = new byte[]{(byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD};

    // Client 端被 Server 分配的 clientId（通过握手回复获得）
    private int assignedClientId = -1;

    /** Server 主动断线标志（Client 端收到 DISCONNECT_MAGIC 时置位） */
    private volatile boolean serverDisconnectReceived = false;

    public UdpSocketTransport(boolean isServerIdentity) {
        this.isServerIdentity = isServerIdentity;
    }

    @Override
    public void setReceiveCallback(TransportReceiveCallback callback) {
        this.receiveCallback = callback;
    }

    @Override
    public void setConnectionListener(NetworkConnectionListener listener) {
        this.connectionListener = listener;
    }

    /** 获取被 Server 分配的 clientId（仅 Client 端有意义） */
    public int getAssignedClientId() {
        return assignedClientId;
    }

    /** Client 端：是否收到了 Server 的主动断线通知 */
    public boolean isServerDisconnectReceived() { return serverDisconnectReceived; }

    @Override
    public void startServer(int port) {
        if (!isServerIdentity) return;
        try {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            running.set(true);
            startReceiveLoop();
            DLog.logT("Netcode", "[UdpTransport] Server 已启动，监听端口: " + port);
        } catch (SocketException e) {
            throw new RuntimeException("[UdpTransport] Server 启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void connect(String ip, int port) {
        if (isServerIdentity) return;
        try {
            socket = new DatagramSocket(); // 随机绑定本地端口
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            serverAddress = new InetSocketAddress(ip, port);
            running.set(true);
            startReceiveLoop();

            // 发送握手包，让 Server 知道我们的地址
            sendRaw(HANDSHAKE_MAGIC, serverAddress);
            DLog.logT("Netcode", "[UdpTransport] Client 已连接到 " + ip + ":" + port);
        } catch (SocketException e) {
            throw new RuntimeException("[UdpTransport] Client 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        // Server 断开前通知所有客户端（多次发送提高 UDP 可达率）
        if (isServerIdentity && socket != null && !socket.isClosed()) {
            for (InetSocketAddress addr : clientAddresses) {
                for (int i = 0; i < 3; i++) {
                    try { sendRaw(DISCONNECT_MAGIC, addr); } catch (Exception ignored) { }
                }
            }
        }
        // Client 断开前多次通知 Server（提高 UDP 可达率）
        if (!isServerIdentity && serverAddress != null && socket != null && !socket.isClosed()) {
            for (int i = 0; i < 3; i++) {
                try { sendRaw(DISCONNECT_MAGIC, serverAddress); } catch (Exception ignored) { }
            }
        }
        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        clientAddresses.clear();
        activeClientIds.clear();
        serverDisconnectReceived = false;
        DLog.logT("Netcode", "[UdpTransport] 已断开连接");
    }

    @Override
    public void sendToClient(int clientId, byte[] payload) {
        if (!isServerIdentity || clientAddresses.isEmpty()) return;
        // 根据 clientId 发送（目前简化：如果只有一个 Client，直接发）
        if (clientId >= 0 && clientId < clientAddresses.size()) {
            sendWithLengthPrefix(payload, clientAddresses.get(clientId));
        }
    }

    @Override
    public void sendToServer(byte[] payload) {
        if (isServerIdentity || serverAddress == null) return;
        sendWithLengthPrefix(payload, serverAddress);
    }

    @Override
    public void broadcast(byte[] payload) {
        if (!isServerIdentity) return;
        // 仅向活跃客户端广播（避免发送给已断开的旧地址）
        for (int clientId : activeClientIds) {
            if (clientId >= 0 && clientId < clientAddresses.size()) {
                sendWithLengthPrefix(payload, clientAddresses.get(clientId));
            }
        }
    }

    @Override
    public boolean isServer() {
        return isServerIdentity;
    }

    @Override
    public boolean isClient() {
        return !isServerIdentity;
    }

    /**
     * 客户端重连: 关闭旧 socket，创建新 socket，重新发送握手包。
     * 适用于移动网络切换（data toggle）后 socket 绑定的网络接口失效的场景。
     */
    public void reconnect() {
        if (isServerIdentity || serverAddress == null) return;

        // 停止旧接收循环
        running.set(false);
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        // 创建新 socket 并重新握手
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            running.set(true);
            startReceiveLoop();

            // 重新发送握手包，让 Server 识别我们的新地址
            sendRaw(HANDSHAKE_MAGIC, serverAddress);
            DLog.logT("Netcode", "[UdpTransport] Client 重连尝试: " + serverAddress);
        } catch (SocketException e) {
            DLog.logErr("[UdpTransport] Client 重连失败: " + e.getMessage());
        }
    }

    /** 返回历史连接过的客户端总数（包含已断开的，用于 clientId 索引） */
    public int getClientCount() {
        return clientAddresses.size();
    }

    /** 返回当前活跃（未断开）的客户端数量 */
    public int getActiveClientCount() {
        return activeClientIds.size();
    }

    /** 返回当前活跃客户端 ID 集合（只读副本） */
    public Set<Integer> getActiveClientIds() {
        return java.util.Collections.unmodifiableSet(activeClientIds);
    }

    /** 将指定客户端标记为非活跃（心跳超时时由上层调用） */
    public void deactivateClient(int clientId) {
        activeClientIds.remove(clientId);
    }

    /**
     * 主动断开指定客户端的连接（Server 端调用）。
     * <p>
     * 发送断线通知包给客户端，并将其标记为非活跃。
     *
     * @param clientId 要断开的客户端 ID
     */
    public void disconnectClient(int clientId) {
        if (!isServerIdentity) return;
        if (clientId >= 0 && clientId < clientAddresses.size()) {
            InetSocketAddress addr = clientAddresses.get(clientId);
            // 发送 3 次断线通知（UDP 不可靠，冗余发送提高送达率）
            for (int i = 0; i < 3; i++) {
                try { sendRaw(DISCONNECT_MAGIC, addr); } catch (Exception ignored) { }
            }
            activeClientIds.remove(clientId);
            DLog.logT("Netcode", "[UdpTransport] Server 主动断开 Client #" + clientId);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 启动异步接收循环（守护线程）
     */
    private void startReceiveLoop() {
        receiveThread = new Thread(() -> {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            while (running.get() && !socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    byte[] rawData = new byte[packet.getLength()];
                    System.arraycopy(buffer, 0, rawData, 0, packet.getLength());

                    // 解析带长度前缀的封包（try-catch 防止单个损坏包杀死整个接收线程）
                    try {
                        processReceivedData(rawData, sender);
                    } catch (Exception e) {
                        DLog.logErr("[UdpTransport] 处理封包异常（已安全跳过）: " + e.getClass().getSimpleName()
                            + " - " + e.getMessage() + " | sender=" + sender + ", rawLen=" + rawData.length);
                    }

                } catch (SocketTimeoutException e) {
                    // 正常超时，回到循环头检查 running 标志
                    continue;
                } catch (IOException e) {
                    if (running.get()) {
                        DLog.logErr("[UdpTransport] 接收数据异常: " + e.getMessage());
                    }
                    // socket 关闭时会触发 IOException，属于正常退出
                }
            }
        }, "UdpTransport-Recv-" + (isServerIdentity ? "Server" : "Client"));
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /**
     * 处理收到的原始数据。
     * 协议格式: [4字节payload长度][payload字节]
     * 特殊: 握手包是裸的 [0xFF,0xFF,0xFF,0xFF]（无长度前缀）
     */
    private void processReceivedData(byte[] rawData, InetSocketAddress sender) {
        // 检查是否为断开通知包 [0xFD×4]
        if (isDisconnectNotify(rawData)) {
            if (isServerIdentity) {
                int clientId = clientAddresses.indexOf(sender);
                if (clientId >= 0) {
                    // 不立即移除地址（保持 clientId 索引稳定），但从活跃集合中移除
                    activeClientIds.remove(clientId);
                    DLog.logT("Netcode", "[UdpTransport] Server 收到 Client #" + clientId + " 的断开通知: " + sender);
                    if (connectionListener != null) {
                        connectionListener.onClientDisconnected(clientId);
                    }
                }
            } else {
                // Client 收到 Server 的断线通知
                serverDisconnectReceived = true;
                DLog.logT("Netcode", "[UdpTransport] Client 收到 Server 断线通知");
            }
            return;
        }

        // 检查是否为握手包
        if (isHandshake(rawData)) {
            if (isServerIdentity) {
                // Server 收到握手包，记录 Client 地址
                if (!clientAddresses.contains(sender)) {
                    clientAddresses.add(sender);
                    int clientId = clientAddresses.indexOf(sender);
                    activeClientIds.add(clientId);
                    DLog.logT("Netcode", "[UdpTransport] Server 发现新客户端: " + sender + " -> clientId=" + clientId);

                    // 发送握手回复: [0xFE×4][clientId]
                    byte[] reply = new byte[8];
                    System.arraycopy(HANDSHAKE_REPLY_MAGIC, 0, reply, 0, 4);
                    ByteBuffer bb = ByteBuffer.wrap(reply, 4, 4);
                    bb.putInt(clientId);
                    sendRaw(reply, sender);

                    // 触发连接事件
                    if (connectionListener != null) {
                        connectionListener.onClientConnected(clientId);
                    }
                }
            }
            // 握手包不传递给上层
            return;
        }

        // 检查是否为握手回复包 (Client 端接收): [0xFE×4][4字节 clientId]
        if (!isServerIdentity && rawData.length == 8 && isHandshakeReply(rawData)) {
            ByteBuffer bb = ByteBuffer.wrap(rawData, 4, 4);
            assignedClientId = bb.getInt();
            DLog.logT("Netcode", "[UdpTransport] Client 被分配 clientId=" + assignedClientId);
            if (connectionListener != null) {
                connectionListener.onClientConnected(assignedClientId);
            }
            return;
        }

        // 解析长度前缀封包: [4字节length][payload]
        if (rawData.length < 4) return;

        ByteBuffer bb = ByteBuffer.wrap(rawData, 0, 4);
        int payloadLen = bb.getInt();

        if (payloadLen <= 0 || payloadLen + 4 > rawData.length) {
            DLog.logErr("[UdpTransport] 封包长度异常: declared=" + payloadLen + ", actual=" + (rawData.length - 4));
            return;
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(rawData, 4, payload, 0, payloadLen);

        // 通过回调推送给上层
        if (receiveCallback != null) {
            // Server 端：根据 sender 地址反查 clientId；Client 端：固定 -1
            int clientId = -1;
            if (isServerIdentity) {
                clientId = clientAddresses.indexOf(sender);
            }
            receiveCallback.onReceiveData(payload, clientId);
        }
    }

    /**
     * 发送带长度前缀的封包: [4字节payload长度][payload字节]
     */
    private void sendWithLengthPrefix(byte[] payload, InetSocketAddress target) {
        byte[] packet = new byte[4 + payload.length];
        ByteBuffer bb = ByteBuffer.wrap(packet);
        bb.putInt(payload.length);
        bb.put(payload);
        sendRaw(packet, target);
    }

    /**
     * 底层发送原始字节
     */
    private void sendRaw(byte[] data, InetSocketAddress target) {
        if (socket == null || socket.isClosed()) return;
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, target.getAddress(), target.getPort());
            socket.send(packet);
        } catch (IOException e) {
            DLog.logErr("[UdpTransport] 发送失败: " + e.getMessage());
        }
    }

    /**
     * 判断是否为握手包 [0xFF,0xFF,0xFF,0xFF]
     */
    private boolean isHandshake(byte[] data) {
        if (data.length != HANDSHAKE_MAGIC.length) return false;
        for (int i = 0; i < HANDSHAKE_MAGIC.length; i++) {
            if (data[i] != HANDSHAKE_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * 判断是否为握手回复包前缀 [0xFE,0xFE,0xFE,0xFE]
     */
    private boolean isHandshakeReply(byte[] data) {
        if (data.length < HANDSHAKE_REPLY_MAGIC.length) return false;
        for (int i = 0; i < HANDSHAKE_REPLY_MAGIC.length; i++) {
            if (data[i] != HANDSHAKE_REPLY_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * 判断是否为断开通知包 [0xFD,0xFD,0xFD,0xFD]
     */
    private boolean isDisconnectNotify(byte[] data) {
        if (data.length != DISCONNECT_MAGIC.length) return false;
        for (int i = 0; i < DISCONNECT_MAGIC.length; i++) {
            if (data[i] != DISCONNECT_MAGIC[i]) return false;
        }
        return true;
    }
}
