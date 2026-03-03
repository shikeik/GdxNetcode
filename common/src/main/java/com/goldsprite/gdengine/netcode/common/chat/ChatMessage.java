package com.goldsprite.gdengine.netcode.common.chat;

/**
 * 游戏聊天/系统消息数据模型。
 * <p>
 * 用于在 {@link ChatChannel} 中流转的消息载体，区分聊天、系统通知、指令反馈三种类型。
 * 纯 Java 类，不依赖 libGDX，可在 Headless 服务端和 GUI 客户端通用。
 *
 * <pre>
 * // 示例
 * ChatMessage.chat("Player#1", "大家好!");
 * ChatMessage.system("Player#2 加入了游戏");
 * ChatMessage.commandResult("已传送到 (100, 200)");
 * </pre>
 */
public class ChatMessage {

    /** 消息类型 */
    public enum Type {
        /** 玩家聊天消息 */
        CHAT,
        /** 系统通知（如玩家连接/断开、服务器公告） */
        SYSTEM,
        /** 命令执行反馈 */
        COMMAND_RESULT
    }

    public final Type type;
    /** 发送者名称（如 "Server"、"Player#1"） */
    public final String sender;
    /** 消息正文 */
    public final String content;
    /** 创建时间戳（毫秒） */
    public final long timestamp;

    public ChatMessage(Type type, String sender, String content) {
        this.type = type;
        this.sender = sender != null ? sender : "";
        this.content = content != null ? content : "";
        this.timestamp = System.currentTimeMillis();
    }

    // ══════════════ 便捷工厂方法 ══════════════

    /** 创建聊天消息 */
    public static ChatMessage chat(String sender, String content) {
        return new ChatMessage(Type.CHAT, sender, content);
    }

    /** 创建系统通知 */
    public static ChatMessage system(String content) {
        return new ChatMessage(Type.SYSTEM, "System", content);
    }

    /** 创建命令反馈 */
    public static ChatMessage commandResult(String content) {
        return new ChatMessage(Type.COMMAND_RESULT, "Console", content);
    }

    // ══════════════ 格式化 ══════════════

    /**
     * 获取适合显示的格式化文本。
     * <ul>
     *   <li>CHAT: {@code <Player#1> 大家好!}</li>
     *   <li>SYSTEM: {@code [系统] Player#2 加入了游戏}</li>
     *   <li>COMMAND_RESULT: 原文</li>
     * </ul>
     */
    public String getFormatted() {
        switch (type) {
            case CHAT:
                return String.format("<%s> %s", sender, content);
            case SYSTEM:
                return String.format("[系统] %s", content);
            case COMMAND_RESULT:
                return content;
            default:
                return content;
        }
    }

    @Override
    public String toString() {
        return getFormatted();
    }
}
