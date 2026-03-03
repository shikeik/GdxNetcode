package com.goldsprite.gdengine.netcode;

/**
 * 网络连接事件监听器。
 * <ul>
 *   <li>Server 端：当新 Client 连入时触发 onClientConnected(clientId)</li>
 *   <li>Client 端：当收到 Server 握手回复（分配了 clientId）时触发 onClientConnected(assignedClientId)</li>
 * </ul>
 */
public interface NetworkConnectionListener {
    /** 有新客户端接入（或 Client 端收到分配的 clientId） */
    void onClientConnected(int clientId);

    /** 客户端断开（暂留，后续心跳检测时实现） */
    default void onClientDisconnected(int clientId) {}
}
