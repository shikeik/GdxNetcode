package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.gdengine.test.CLogAssert;
import com.goldsprite.GdxTestRunner;

/**
 * TDD 驱动：验证 ReliableUdpTransport 可靠传输层的核心机制。
 * 涵盖序列号循环、ACK 确认、超时重传、乱序丢弃等。
 */
@RunWith(GdxTestRunner.class)
public class ReliableUdpTransportTest {

    // ========== 序列号工具测试 ==========

    /**
     * 测试1: 序列号比较 — 正常递增场景
     */
    @Test
    public void testSeqNumNewerThan_normalIncrement() {
        System.out.println("======= [TDD] 序列号比较: 正常递增 =======");
        CLogAssert.assertTrue("1 应新于 0", ReliableUdpTransport.isSeqNewer(1, 0));
        CLogAssert.assertTrue("100 应新于 50", ReliableUdpTransport.isSeqNewer(100, 50));
        CLogAssert.assertFalse("0 不应新于 1", ReliableUdpTransport.isSeqNewer(0, 1));
        CLogAssert.assertFalse("相同序列号不算更新", ReliableUdpTransport.isSeqNewer(5, 5));
        System.out.println("======= [TDD] 序列号比较: 正常递增 通过 =======");
    }

    /**
     * 测试2: 序列号比较 — 65535 → 0 循环场景
     */
    @Test
    public void testSeqNumNewerThan_wrapAround() {
        System.out.println("======= [TDD] 序列号比较: 循环 =======");
        // 0 应新于 65535（刚循环过来）
        CLogAssert.assertTrue("0 应新于 65535(循环)", ReliableUdpTransport.isSeqNewer(0, 65535));
        // 1 应新于 65534
        CLogAssert.assertTrue("1 应新于 65534(循环)", ReliableUdpTransport.isSeqNewer(1, 65534));
        // 65535 不应新于 0（反向应判定为旧包）
        CLogAssert.assertFalse("65535 不应新于 0(反循环)", ReliableUdpTransport.isSeqNewer(65535, 0));
        System.out.println("======= [TDD] 序列号比较: 循环 通过 =======");
    }

    // ========== 封包头编解码测试 ==========

    /**
     * 测试3: Reliable 封包的头部编码与解码
     */
    @Test
    public void testReliablePacketHeaderEncoding() {
        System.out.println("======= [TDD] Reliable 封包头编解码 =======");
        byte[] payload = new byte[]{0x10, 0x20, 0x30};
        int seqNum = 42;
        int ackNum = 37;

        byte[] packet = ReliableUdpTransport.encodeReliablePacket(seqNum, ackNum, payload);

        // 验证头部
        CLogAssert.assertEquals("channelType 应为 0x01", (byte) 0x01, packet[0]);
        // seqNum: 2 bytes big-endian
        int decodedSeq = ((packet[1] & 0xFF) << 8) | (packet[2] & 0xFF);
        CLogAssert.assertEquals("seqNum 应为 42", 42, decodedSeq);
        // ackNum: 2 bytes big-endian
        int decodedAck = ((packet[3] & 0xFF) << 8) | (packet[4] & 0xFF);
        CLogAssert.assertEquals("ackNum 应为 37", 37, decodedAck);
        // payload
        CLogAssert.assertEquals("payload[0] 应为 0x10", (byte) 0x10, packet[5]);
        CLogAssert.assertEquals("payload[2] 应为 0x30", (byte) 0x30, packet[7]);

        System.out.println("======= [TDD] Reliable 封包头编解码 通过 =======");
    }

    /**
     * 测试4: Unreliable 封包直接透传（仅加1字节头）
     */
    @Test
    public void testUnreliablePacketEncoding() {
        System.out.println("======= [TDD] Unreliable 封包编码 =======");
        byte[] payload = new byte[]{(byte) 0xAA, (byte) 0xBB};

        byte[] packet = ReliableUdpTransport.encodeUnreliablePacket(payload);

        CLogAssert.assertEquals("channelType 应为 0x00", (byte) 0x00, packet[0]);
        CLogAssert.assertEquals("payload 长度应为 2", 2, packet.length - 1);
        CLogAssert.assertEquals("payload[0]", (byte) 0xAA, packet[1]);
        CLogAssert.assertEquals("payload[1]", (byte) 0xBB, packet[2]);

        System.out.println("======= [TDD] Unreliable 封包编码 通过 =======");
    }

    // ========== 重传缓冲区测试 ==========

    /**
     * 测试5: Reliable 包发送后进入重传缓冲区，收到 ACK 后移除
     */
    @Test
    public void testPendingBufferAddAndAck() {
        System.out.println("======= [TDD] 重传缓冲区: 添加与 ACK 移除 =======");

        ReliableUdpTransport.PendingPacketBuffer buffer = new ReliableUdpTransport.PendingPacketBuffer();

        byte[] data1 = new byte[]{0x01};
        byte[] data2 = new byte[]{0x02};
        buffer.add(1, data1, System.currentTimeMillis());
        buffer.add(2, data2, System.currentTimeMillis());

        CLogAssert.assertEquals("缓冲区应有 2 个待确认包", 2, buffer.size());

        // 模拟收到 ack=1
        buffer.ack(1);
        CLogAssert.assertEquals("ack(1) 后应剩 1 个", 1, buffer.size());

        // 模拟收到 ack=2
        buffer.ack(2);
        CLogAssert.assertEquals("ack(2) 后应为空", 0, buffer.size());

        System.out.println("======= [TDD] 重传缓冲区: 添加与 ACK 移除 通过 =======");
    }

    /**
     * 测试6: 超时重传检测
     */
    @Test
    public void testPendingBufferTimeout() throws InterruptedException {
        System.out.println("======= [TDD] 重传缓冲区: 超时检测 =======");

        ReliableUdpTransport.PendingPacketBuffer buffer = new ReliableUdpTransport.PendingPacketBuffer();
        long now = System.currentTimeMillis();
        byte[] data = new byte[]{0x01};

        // 加入一个包，发送时间 = now - 300ms（超过 200ms 超时阈值）
        buffer.add(1, data, now - 300);

        // 获取需要重传的包
        var timeouts = buffer.getTimedOut(now, 200);
        CLogAssert.assertEquals("应有 1 个超时包", 1, timeouts.size());

        System.out.println("======= [TDD] 重传缓冲区: 超时检测 通过 =======");
    }

    /**
     * 测试7: 最大重传次数后标记为失败
     */
    @Test
    public void testMaxRetransmissions() {
        System.out.println("======= [TDD] 最大重传次数 =======");

        ReliableUdpTransport.PendingPacketBuffer buffer = new ReliableUdpTransport.PendingPacketBuffer();
        long now = System.currentTimeMillis();
        byte[] data = new byte[]{0x01};

        buffer.add(1, data, now - 300);

        // 模拟 5 次重传
        for (int i = 0; i < 5; i++) {
            var timeouts = buffer.getTimedOut(now + i * 300, 200);
            if (!timeouts.isEmpty()) {
                buffer.markRetransmitted(1, now + i * 300);
            }
        }

        CLogAssert.assertTrue("5 次重传后应标记为失败", buffer.isMaxRetriesExceeded(1, 5));

        System.out.println("======= [TDD] 最大重传次数 通过 =======");
    }

    // ========== 乱序丢弃测试 ==========

    /**
     * 测试8: 接收端丢弃旧序列号封包
     */
    @Test
    public void testReceiveRejectsOldSeqNum() {
        System.out.println("======= [TDD] 接收端乱序丢弃 =======");

        ReliableUdpTransport.ReceiveSequenceTracker tracker = new ReliableUdpTransport.ReceiveSequenceTracker();

        // 收到 seq=1, 应接受
        CLogAssert.assertTrue("seq=1 应被接受", tracker.accept(1));
        // 收到 seq=2, 应接受
        CLogAssert.assertTrue("seq=2 应被接受", tracker.accept(2));
        // 收到 seq=1(旧包), 应拒绝
        CLogAssert.assertFalse("seq=1(旧) 应被拒绝", tracker.accept(1));
        // 收到 seq=2(重复), 应拒绝
        CLogAssert.assertFalse("seq=2(重复) 应被拒绝", tracker.accept(2));
        // 收到 seq=3, 应接受
        CLogAssert.assertTrue("seq=3 应被接受", tracker.accept(3));

        System.out.println("======= [TDD] 接收端乱序丢弃 通过 =======");
    }

    /**
     * 测试9: 接收端处理序列号循环 (65535 → 0)
     */
    @Test
    public void testReceiveSeqWrapAround() {
        System.out.println("======= [TDD] 接收端序列号循环 =======");

        ReliableUdpTransport.ReceiveSequenceTracker tracker = new ReliableUdpTransport.ReceiveSequenceTracker();

        // 设置初始期望为 65534
        tracker.accept(65534);
        tracker.accept(65535);
        // 循环: seq=0 应新于 65535
        CLogAssert.assertTrue("循环后 seq=0 应被接受", tracker.accept(0));
        CLogAssert.assertTrue("循环后 seq=1 应被接受", tracker.accept(1));
        // 旧包 65535 应被拒绝
        CLogAssert.assertFalse("循环后 seq=65535 应被拒绝", tracker.accept(65535));

        System.out.println("======= [TDD] 接收端序列号循环 通过 =======");
    }

    // ========== 通道路由测试 ==========

    /**
     * 测试10: 根据封包类型自动选择通道
     */
    @Test
    public void testChannelRouting() {
        System.out.println("======= [TDD] 通道路由 =======");
        // 状态同步 (0x10) → Unreliable
        CLogAssert.assertFalse("状态同步应走 Unreliable", ReliableUdpTransport.isReliablePacketType(0x10));
        // Spawn (0x11) → Reliable
        CLogAssert.assertTrue("Spawn 应走 Reliable", ReliableUdpTransport.isReliablePacketType(0x11));
        // Despawn (0x12) → Reliable
        CLogAssert.assertTrue("Despawn 应走 Reliable", ReliableUdpTransport.isReliablePacketType(0x12));
        // ServerRpc (0x20) → Reliable
        CLogAssert.assertTrue("ServerRpc 应走 Reliable", ReliableUdpTransport.isReliablePacketType(0x20));
        // ClientRpc (0x21) → Reliable
        CLogAssert.assertTrue("ClientRpc 应走 Reliable", ReliableUdpTransport.isReliablePacketType(0x21));
        System.out.println("======= [TDD] 通道路由 通过 =======");
    }

    // ========== 端到端集成测试 ==========

    /**
     * 测试11: ReliableUdpTransport 端到端可靠传输
     * Server 通过 Reliable 通道发送 Spawn 包，Client 收到并回复 ACK。
     */
    @Test
    public void testEndToEndReliableTransport() throws Exception {
        System.out.println("======= [TDD] 端到端 Reliable 传输 =======");

        int serverPort = 19010;

        // 底层 UDP
        UdpSocketTransport rawServer = new UdpSocketTransport(true);
        UdpSocketTransport rawClient = new UdpSocketTransport(false);

        // 可靠层包装
        ReliableUdpTransport server = new ReliableUdpTransport(rawServer);
        ReliableUdpTransport client = new ReliableUdpTransport(rawClient);

        // 接收捕获
        final byte[][] receivedHolder = new byte[1][];
        client.setReceiveCallback((payload, clientId) -> {
            receivedHolder[0] = payload;
        });

        server.startServer(serverPort);
        client.connect("127.0.0.1", serverPort);

        Thread.sleep(200);

        // 构造一个 Spawn 包 (0x11 = Reliable 通道)
        NetBuffer buf = new NetBuffer();
        buf.writeInt(0x11); // SpawnPacket
        buf.writeInt(1);    // netId
        buf.writeInt(1);    // prefabId
        buf.writeInt(-1);   // ownerClientId
        byte[] spawnPacket = buf.toByteArray();

        server.broadcast(spawnPacket);
        Thread.sleep(300);

        // 驱动一次 tick 让 client 处理 ACK
        client.tickReliable();

        CLogAssert.assertTrue("Client 应收到 Spawn 包", receivedHolder[0] != null);
        // 验证 payload 内容完整
        NetBuffer inBuf = new NetBuffer(receivedHolder[0]);
        int packetType = inBuf.readInt();
        CLogAssert.assertEquals("封包类型应为 0x11", 0x11, packetType);

        // 清理
        server.disconnect();
        client.disconnect();

        System.out.println("======= [TDD] 端到端 Reliable 传输 通过 =======");
    }
}
