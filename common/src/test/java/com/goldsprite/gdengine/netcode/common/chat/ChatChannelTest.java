package com.goldsprite.gdengine.netcode.common.chat;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.GdxTestRunner;

/**
 * {@link ChatChannel} 消息总线单元测试。
 * <p>
 * 需要 GdxTestRunner 提供 Gdx.app 上下文（因为 postMessage 内调用 DLog）。
 * 覆盖 processInput 路由、消息投递、监听器回调和消息上限裁剪。
 */
@RunWith(GdxTestRunner.class)
public class ChatChannelTest {

    private ChatChannel channel;

    @Before
    public void setUp() {
        channel = ChatChannel.get();
        channel.clear();
        // 重置注入的组件
        channel.setNetworkSender(null);
        channel.setCommandExecutor(null);
    }

    // ══════════════ 基本投递 ══════════════

    @Test
    public void testPostMessage() {
        ChatMessage msg = ChatMessage.system("测试消息");
        channel.postMessage(msg);
        assertEquals(1, channel.getMessageCount());
        assertSame(msg, channel.getMessages().get(0));
    }

    @Test
    public void testPostNullIgnored() {
        channel.postMessage(null);
        assertEquals(0, channel.getMessageCount());
    }

    // ══════════════ processInput 路由 ══════════════

    @Test
    public void testProcessInputChat() {
        // 普通文本 → 聊天消息
        channel.processInput("大家好", "Player#1");
        assertEquals(1, channel.getMessageCount());

        ChatMessage msg = channel.getMessages().get(0);
        assertEquals(ChatMessage.Type.CHAT, msg.type);
        assertEquals("Player#1", msg.sender);
        assertEquals("大家好", msg.content);
    }

    @Test
    public void testProcessInputSayCommand() {
        // /say → 聊天消息
        channel.processInput("/say 服务器公告", "Server");
        assertEquals(1, channel.getMessageCount());

        ChatMessage msg = channel.getMessages().get(0);
        assertEquals(ChatMessage.Type.CHAT, msg.type);
        assertEquals("Server", msg.sender);
        assertEquals("服务器公告", msg.content);
    }

    @Test
    public void testProcessInputSayEmpty() {
        // /say 无内容 → 不产生消息
        channel.processInput("/say", "Server");
        assertEquals(0, channel.getMessageCount());
    }

    @Test
    public void testProcessInputCommand() {
        // 设置命令执行器
        channel.setCommandExecutor(cmdLine -> {
            if (cmdLine.startsWith("tp")) return "已传送";
            return null;
        });
        channel.processInput("/tp 100 200", "Host");

        // 应产生一条 COMMAND_RESULT 消息
        assertEquals(1, channel.getMessageCount());
        ChatMessage msg = channel.getMessages().get(0);
        assertEquals(ChatMessage.Type.COMMAND_RESULT, msg.type);
        assertEquals("已传送", msg.content);
    }

    @Test
    public void testProcessInputCommandNoExecutor() {
        // 未设置命令执行器 → 提示消息
        channel.processInput("/unknowncmd", "Host");
        assertEquals(1, channel.getMessageCount());

        ChatMessage msg = channel.getMessages().get(0);
        assertEquals(ChatMessage.Type.COMMAND_RESULT, msg.type);
        assertTrue("应包含未设置执行器提示", msg.content.contains("未设置命令执行器"));
    }

    @Test
    public void testProcessInputEmpty() {
        channel.processInput("", "Player");
        channel.processInput(null, "Player");
        channel.processInput("   ", "Player");
        assertEquals(0, channel.getMessageCount());
    }

    @Test
    public void testProcessInputSlashOnly() {
        // 仅 "/" → 不产生消息（命令行为空）
        channel.processInput("/", "Player");
        assertEquals(0, channel.getMessageCount());
    }

    // ══════════════ 网络发送器 ══════════════

    @Test
    public void testNetworkSenderCalledOnChat() {
        List<ChatMessage> sent = new ArrayList<>();
        channel.setNetworkSender(sent::add);

        channel.processInput("hello", "P1");
        assertEquals("网络发送器应被调用", 1, sent.size());
        assertEquals("P1", sent.get(0).sender);
    }

    @Test
    public void testNetworkSenderCalledOnSay() {
        List<ChatMessage> sent = new ArrayList<>();
        channel.setNetworkSender(sent::add);

        channel.processInput("/say 公告", "Server");
        assertEquals("网络发送器应被调用", 1, sent.size());
        assertEquals("公告", sent.get(0).content);
    }

    @Test
    public void testNetworkSenderNotCalledOnCommand() {
        List<ChatMessage> sent = new ArrayList<>();
        channel.setNetworkSender(sent::add);
        channel.setCommandExecutor(cmd -> "ok");

        channel.processInput("/tp 0 0", "Host");
        assertEquals("命令不应触发网络发送", 0, sent.size());
    }

    // ══════════════ 监听器 ══════════════

    @Test
    public void testListenerReceivesMessages() {
        List<ChatMessage> received = new ArrayList<>();
        channel.addListener(received::add);

        channel.postMessage(ChatMessage.system("test"));
        assertEquals(1, received.size());
        assertEquals(ChatMessage.Type.SYSTEM, received.get(0).type);

        channel.removeListener(received::add); // 清理
    }

    // ══════════════ 消息上限 ══════════════

    @Test
    public void testMessageLimit() {
        // MAX_MESSAGES = 200，投递 210 条后应只保留 200 条
        for (int i = 0; i < 210; i++) {
            channel.postMessage(ChatMessage.system("msg#" + i));
        }
        assertEquals(200, channel.getMessageCount());
        // 最早的 10 条应被裁剪，第一条应为 msg#10
        assertEquals("msg#10", channel.getMessages().get(0).content);
    }

    // ══════════════ getRecentMessages ══════════════

    @Test
    public void testGetRecentMessages() {
        for (int i = 0; i < 10; i++) {
            channel.postMessage(ChatMessage.system("msg#" + i));
        }
        List<ChatMessage> recent = channel.getRecentMessages(3);
        assertEquals(3, recent.size());
        assertEquals("msg#7", recent.get(0).content);
        assertEquals("msg#9", recent.get(2).content);
    }

    @Test
    public void testGetRecentMessagesExceedsTotal() {
        channel.postMessage(ChatMessage.system("唯一"));
        List<ChatMessage> recent = channel.getRecentMessages(100);
        assertEquals(1, recent.size());
    }
}
