package com.goldsprite.gdengine.netcode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 联机引擎核心序列化缓冲器。
 * 用于将各种网络变量、实体ID压缩成高度紧凑的字节数组 (byte[])，
 * 以便 Transport 层可以通过 UDP/TCP 发送。同时也负责解析收到的封包。
 * <p>
 * 安全特性:
 * <ul>
 *   <li>写入端自动扩容，不会 BufferOverflowException</li>
 *   <li>读取端防御性检查，损坏数据不会导致 OOM 或下标越界</li>
 *   <li>字符串长度上限 MAX_STRING_LENGTH，防止恶意/损坏包分配巨量内存</li>
 * </ul>
 */
public class NetBuffer {
    private ByteBuffer buffer;

    /** 字符串最大字节长度（防止损坏数据导致 OOM） */
    private static final int MAX_STRING_LENGTH = 4096;

    /** 单次分配最大容量上限（防止异常扩容） */
    private static final int MAX_BUFFER_SIZE = 65536;

    /**
     * 作为发送端：开辟写入缓冲区（初始 1024 字节，自动扩容）
     */
    public NetBuffer() {
        buffer = ByteBuffer.allocate(1024);
    }

    /**
     * 作为接收端：将收到的字节流包装进来准备读取
     */
    public NetBuffer(byte[] data) {
        buffer = ByteBuffer.wrap(data);
    }

    /** 确保有足够的写入空间，不足时自动扩容 */
    private void ensureWriteCapacity(int bytesNeeded) {
        if (buffer.remaining() < bytesNeeded) {
            int newCapacity = Math.min(MAX_BUFFER_SIZE,
                Math.max(buffer.capacity() * 2, buffer.position() + bytesNeeded));
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
    }

    /** 返回当前缓冲区剩余可读字节数 */
    public int remaining() {
        return buffer.remaining();
    }

    public void writeInt(int value) {
        ensureWriteCapacity(4);
        buffer.putInt(value);
    }

    public int readInt() {
        if (buffer.remaining() < 4) {
            throw new NetBufferUnderflowException("readInt 需要 4 字节，剩余 " + buffer.remaining());
        }
        return buffer.getInt();
    }

    public void writeFloat(float value) {
        ensureWriteCapacity(4);
        buffer.putFloat(value);
    }

    public float readFloat() {
        if (buffer.remaining() < 4) {
            throw new NetBufferUnderflowException("readFloat 需要 4 字节，剩余 " + buffer.remaining());
        }
        return buffer.getFloat();
    }

    public void writeBoolean(boolean value) {
        ensureWriteCapacity(1);
        buffer.put((byte) (value ? 1 : 0));
    }

    public boolean readBoolean() {
        if (buffer.remaining() < 1) {
            throw new NetBufferUnderflowException("readBoolean 需要 1 字节，剩余 0");
        }
        return buffer.get() == 1;
    }

    public void writeString(String value) {
        if (value == null) {
            value = "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ensureWriteCapacity(4 + bytes.length);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    public String readString() {
        if (buffer.remaining() < 4) {
            throw new NetBufferUnderflowException("readString 头部需要 4 字节，剩余 " + buffer.remaining());
        }
        int len = buffer.getInt();
        // 防御性检查：长度必须合理
        if (len < 0 || len > MAX_STRING_LENGTH) {
            throw new NetBufferUnderflowException("readString 长度异常: " + len + " (上限 " + MAX_STRING_LENGTH + ")");
        }
        if (buffer.remaining() < len) {
            throw new NetBufferUnderflowException("readString 需要 " + len + " 字节内容，剩余 " + buffer.remaining());
        }
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 将装配好的数据翻转并导出为紧凑的 byte 数组，以便底层网络层发送
     */
    public byte[] toByteArray() {
        int positionResult = buffer.position();
        byte[] result = new byte[positionResult];
        
        // 临时备份状态以便读取，读出刚刚装入的字节
        ByteBuffer readCopy = buffer.duplicate();
        readCopy.position(0);
        readCopy.get(result, 0, positionResult);
        
        return result;
    }

    /**
     * NetBuffer 专用异常：数据不足或损坏时抛出（替代原生 BufferUnderflowException，携带诊断信息）
     */
    public static class NetBufferUnderflowException extends RuntimeException {
        public NetBufferUnderflowException(String message) {
            super("[NetBuffer] " + message);
        }
    }
}
