package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.CLogAssert;
import com.goldsprite.GdxTestRunner;

@RunWith(GdxTestRunner.class)
public class NetBufferTest {

    @Test
    public void testSerializationAndDeserialization() {
        // --- 发送端视角：序列化 ---
        NetBuffer outBuffer = new NetBuffer();
        
        // 模拟装配一个网络同步包的头与体
        int networkId = 10086;
        float positionX = -99.5f;
        boolean isShooting = true;
        String playerName = "T34-坦克";

        outBuffer.writeInt(networkId);
        outBuffer.writeFloat(positionX);
        outBuffer.writeBoolean(isShooting);
        outBuffer.writeString(playerName);

        // 生成最终的二进制载荷
        byte[] payload = outBuffer.toByteArray();
        
        CLogAssert.assertTrue("序列化装箱测试: 导出的负载数组不应该为空，并且应当有实质大小", payload.length > 0);

        // --- 接收端视角：反序列化 ---
        // 模拟底层 Transport 接收到了这一串 byte[]
        NetBuffer inBuffer = new NetBuffer(payload);

        int readNetworkId = inBuffer.readInt();
        float readPositionX = inBuffer.readFloat();
        boolean readShooting = inBuffer.readBoolean();
        String readPlayerName = inBuffer.readString();

        CLogAssert.assertEquals("解包测试 - 网络ID必须原样拆出", networkId, readNetworkId);
        CLogAssert.assertEquals("解包测试 - 浮点坐标必须无精度损失", positionX, readPositionX, 0.001f);
        CLogAssert.assertTrue("解包测试 - 布尔状态必须维持原状", readShooting);
        CLogAssert.assertEquals("解包测试 - 含有中文的中文字符串必须不能乱码", playerName, readPlayerName);
    }
}
