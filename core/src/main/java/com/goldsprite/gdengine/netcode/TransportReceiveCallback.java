package com.goldsprite.gdengine.netcode;

/**
 * 传输层统一的数据接收回调接口。
 * Transport 实现在收到网络数据后，通过此回调将原始字节推送给上层（通常是 NetworkManager）。
 * 这是一个函数式接口，支持 lambda / 方法引用。
 */
@FunctionalInterface
public interface TransportReceiveCallback {
    /**
     * 当底层传输层收到一帧完整的网络封包时调用。
     * @param payload  收到的原始字节数据
     * @param clientId 发送方的 clientId（Server 端接收时 >= 0；Client 端固定为 -1）
     */
    void onReceiveData(byte[] payload, int clientId);
}
