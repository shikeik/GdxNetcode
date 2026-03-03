package com.goldsprite.gdengine.netcode.common.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link ChatMessage} 消息模型单元测试。
 * <p>
 * 纯 Java 类，不依赖 libGDX 上下文，无需 GdxTestRunner。
 * 覆盖工厂方法、类型判断、格式化输出和 null 安全性。
 */
public class ChatMessageTest {

    // ══════════════ 工厂方法 ══════════════

    @Test
    public void testChatFactory() {
        ChatMessage msg = ChatMessage.chat("Player#1", "你好!");
        assertEquals(ChatMessage.Type.CHAT, msg.type);
        assertEquals("Player#1", msg.sender);
        assertEquals("你好!", msg.content);
        assertTrue("时间戳应为正数", msg.timestamp > 0);
    }

    @Test
    public void testSystemFactory() {
        ChatMessage msg = ChatMessage.system("玩家加入了游戏");
        assertEquals(ChatMessage.Type.SYSTEM, msg.type);
        assertEquals("System", msg.sender);
        assertEquals("玩家加入了游戏", msg.content);
    }

    @Test
    public void testCommandResultFactory() {
        ChatMessage msg = ChatMessage.commandResult("已传送到 (100, 200)");
        assertEquals(ChatMessage.Type.COMMAND_RESULT, msg.type);
        assertEquals("Console", msg.sender);
        assertEquals("已传送到 (100, 200)", msg.content);
    }

    // ══════════════ 格式化输出 ══════════════

    @Test
    public void testFormattedChat() {
        ChatMessage msg = ChatMessage.chat("Host", "大家好");
        assertEquals("<Host> 大家好", msg.getFormatted());
    }

    @Test
    public void testFormattedSystem() {
        ChatMessage msg = ChatMessage.system("服务器即将关闭");
        assertEquals("[系统] 服务器即将关闭", msg.getFormatted());
    }

    @Test
    public void testFormattedCommandResult() {
        ChatMessage msg = ChatMessage.commandResult("执行成功");
        assertEquals("执行成功", msg.getFormatted());
    }

    @Test
    public void testToStringEqualsFormatted() {
        ChatMessage msg = ChatMessage.chat("P1", "test");
        assertEquals(msg.getFormatted(), msg.toString());
    }

    // ══════════════ null 安全性 ══════════════

    @Test
    public void testNullSender() {
        ChatMessage msg = new ChatMessage(ChatMessage.Type.CHAT, null, "内容");
        assertEquals("", msg.sender);
        assertEquals("内容", msg.content);
    }

    @Test
    public void testNullContent() {
        ChatMessage msg = new ChatMessage(ChatMessage.Type.SYSTEM, "sys", null);
        assertEquals("sys", msg.sender);
        assertEquals("", msg.content);
    }

    @Test
    public void testBothNull() {
        ChatMessage msg = new ChatMessage(ChatMessage.Type.COMMAND_RESULT, null, null);
        assertEquals("", msg.sender);
        assertEquals("", msg.content);
    }
}
