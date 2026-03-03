package com.goldsprite.gdengine.netcode.common.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.goldsprite.gdengine.log.DLog;

/**
 * 游戏聊天/指令消息总线（单例）。
 * <p>
 * 职责:
 * <ul>
 *   <li>存储游戏内聊天、系统通知、命令反馈消息</li>
 *   <li>统一输入路由: {@code /} 前缀 → 命令，普通文本 → 聊天</li>
 *   <li>同步写入 {@link DLog}（tag = "Chat"），满足「依赖 DLog 源」需求</li>
 *   <li>通过 {@link MessageListener} 通知 UI / 服务端控制台</li>
 *   <li>通过 {@link NetworkSender} 预留网络传输接口</li>
 * </ul>
 *
 * <b>与 MC 对标的行为对照表:</b>
 * <table>
 *   <tr><th>场景</th><th>输入</th><th>行为</th></tr>
 *   <tr><td>GUI 聊天框</td><td>你好</td><td>发送聊天消息</td></tr>
 *   <tr><td>GUI 聊天框</td><td>/tp 100 200</td><td>执行 tp 命令</td></tr>
 *   <tr><td>服务器控制台</td><td>/say 大家好</td><td>发送服务器聊天</td></tr>
 *   <tr><td>服务器控制台</td><td>status</td><td>（由 ServerConsole 处理，不经过 ChatChannel）</td></tr>
 * </table>
 */
public class ChatChannel {

    // ── 单例 ──
    private static final ChatChannel INSTANCE = new ChatChannel();

    public static ChatChannel get() {
        return INSTANCE;
    }

    // ── 消息存储 ──
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private static final int MAX_MESSAGES = 200;

    // ── 监听器 ──

    /** 消息到达监听器 */
    @FunctionalInterface
    public interface MessageListener {
        void onMessage(ChatMessage message);
    }

    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    // ── 网络发送器（由业务层设置） ──

    /**
     * 网络发送器接口。
     * <p>
     * 业务层（如 SandTank）实现此接口，将聊天消息通过 RPC 发送到远端。
     */
    @FunctionalInterface
    public interface NetworkSender {
        void sendChat(ChatMessage message);
    }

    private NetworkSender networkSender;

    // ── 命令执行器（可由 UI 层或 ServerConsole 设置） ──

    /**
     * 命令执行器接口。
     * <p>
     * GUI 模式下接入 {@code CommandRegistry}；Headless 模式下接入 ServerConsole 命令表。
     */
    @FunctionalInterface
    public interface CommandExecutor {
        /**
         * 执行命令并返回结果文本。
         *
         * @param commandLine 完整命令行（不含前缀 /），如 "tp 100 200"
         * @return 执行结果，null 或空串表示无输出
         */
        String execute(String commandLine);
    }

    private CommandExecutor commandExecutor;

    private ChatChannel() {
    }

    // ══════════════════════════════════════════
    //  配置
    // ══════════════════════════════════════════

    /** 设置网络发送器（业务层注入） */
    public void setNetworkSender(NetworkSender sender) {
        this.networkSender = sender;
    }

    /** 设置命令执行器 */
    public void setCommandExecutor(CommandExecutor executor) {
        this.commandExecutor = executor;
    }

    // ══════════════════════════════════════════
    //  消息操作
    // ══════════════════════════════════════════

    /**
     * 投递一条消息到频道。
     * <p>
     * 同时:
     * <ol>
     *   <li>追加到本地消息列表（带上限裁剪）</li>
     *   <li>写入 DLog（tag = "Chat"，INFO 级别）</li>
     *   <li>通知所有 {@link MessageListener}</li>
     * </ol>
     */
    public void postMessage(ChatMessage message) {
        if (message == null) return;

        messages.add(message);

        // 裁剪超限
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }

        // 同步写入 DLog（满足「依赖 DLog 源」需求）
        DLog.logInfoT("Chat", message.getFormatted());

        // 通知监听器
        for (MessageListener l : listeners) {
            try {
                l.onMessage(message);
            } catch (Exception e) {
                DLog.logWarnT("Chat", "监听器异常: " + e.getMessage());
            }
        }
    }

    /**
     * 统一输入处理（MC 风格）。
     * <p>
     * <ul>
     *   <li>以 {@code /} 开头 → 命令（剥除前缀后交给 {@link CommandExecutor}）</li>
     *   <li>其中 {@code /say <msg>} 特殊处理为发送聊天消息</li>
     *   <li>普通文本 → 聊天消息</li>
     * </ul>
     *
     * @param input      用户输入的原始文本
     * @param senderName 发送者名称
     */
    public void processInput(String input, String senderName) {
        if (input == null || input.trim().isEmpty()) return;
        input = input.trim();

        if (input.startsWith("/")) {
            // ── 命令模式 ──
            String cmdLine = input.substring(1).trim();
            if (cmdLine.isEmpty()) return;

            // /say 特殊处理 — 作为聊天消息
            String[] parts = cmdLine.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            String args = parts.length > 1 ? parts[1] : "";

            if ("say".equals(cmd)) {
                if (!args.isEmpty()) {
                    ChatMessage msg = ChatMessage.chat(senderName, args);
                    if (networkSender != null) {
                        // 联机模式: 交由网络发送器处理（Host 本地显示+广播 / Client 仅发往服务端）
                        networkSender.sendChat(msg);
                    } else {
                        // 非联网模式: 直接本地显示
                        postMessage(msg);
                    }
                }
                return;
            }

            // 其他命令 → 交给命令执行器
            if (commandExecutor != null) {
                String result = commandExecutor.execute(cmdLine);
                if (result != null && !result.isEmpty()) {
                    postMessage(ChatMessage.commandResult(result));
                }
            } else {
                postMessage(ChatMessage.commandResult("未设置命令执行器，无法执行: /" + cmd));
            }
        } else {
            // ── 聊天模式 ──
            ChatMessage msg = ChatMessage.chat(senderName, input);
            if (networkSender != null) {
                // 联机模式: 交由网络发送器处理
                // Host: 本地显示 + 广播给所有客户端
                // Client: 仅发送 ServerRpc，等待服务端广播后再显示
                networkSender.sendChat(msg);
            } else {
                // 非联网模式: 直接本地显示
                postMessage(msg);
            }
        }
    }

    // ══════════════════════════════════════════
    //  数据访问
    // ══════════════════════════════════════════

    /** 获取全部消息列表（只读语义，底层为 CopyOnWriteArrayList） */
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /** 获取最近 N 条消息 */
    public List<ChatMessage> getRecentMessages(int count) {
        int size = messages.size();
        if (count >= size) return new ArrayList<>(messages);
        return new ArrayList<>(messages.subList(size - count, size));
    }

    /** 当前消息总数 */
    public int getMessageCount() {
        return messages.size();
    }

    // ══════════════════════════════════════════
    //  监听器管理
    // ══════════════════════════════════════════

    public void addListener(MessageListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    // ══════════════════════════════════════════
    //  清理
    // ══════════════════════════════════════════

    /** 清空所有消息 */
    public void clear() {
        messages.clear();
    }
}
