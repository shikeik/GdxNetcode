package com.goldsprite.gdengine.netcode;

public interface Transport {
    void startServer(int port);
    void connect(String ip, int port);
    void disconnect();
    void sendToClient(int clientId, byte[] payload);
    void sendToServer(byte[] payload);
    void broadcast(byte[] payload);
    
    // 返回是否是服务器端
    boolean isServer();
    // 返回是否是客户端
    boolean isClient();
    
    /**
     * 设置数据接收回调。传输层收到网络数据后，通过此回调推送给上层。
     * 由 NetworkManager.setTransport() 自动调用，无需手动接线。
     */
    void setReceiveCallback(TransportReceiveCallback callback);

    /**
     * 设置连接事件监听器。
     * Server 端：新 Client 握手成功时触发 onClientConnected(clientId)。
     * Client 端：收到 Server 分配的 clientId 时触发 onClientConnected(assignedClientId)。
     */
    void setConnectionListener(NetworkConnectionListener listener);
}
