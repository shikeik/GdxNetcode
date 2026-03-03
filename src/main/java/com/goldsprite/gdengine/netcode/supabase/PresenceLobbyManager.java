package com.goldsprite.gdengine.netcode.supabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import com.goldsprite.gdengine.log.DLog;

/**
 * Supabase Realtime Presence 云大厅管理器
 * <p>
 * 替代旧的 RoomManager (REST 表操作 + HTTP 心跳，已删除)，采用 WebSocket Presence
 * 实现零数据库 I/O 的实时房间列表同步。
 * <p>
 * 核心能力:
 * <ul>
 *   <li>通过 WebSocket 长连接实时接收房间列表变化 (无需轮询)</li>
 *   <li>房主断线后毫秒级自动从列表移除 (无需 pg_cron 清理)</li>
 *   <li>track/untrack 语义简洁，代码量比 REST 方案少一半</li>
 * </ul>
 *
 * 典型使用流程:
 * <pre>
 *   // 1. 在 Screen.create() 中
 *   manager = new PresenceLobbyManager();
 *   manager.connect(syncListener, statusListener);
 *
 *   // 2. 房主建房
 *   manager.publishRoom(info);
 *
 *   // 3. 在 Screen.dispose() 中
 *   manager.disconnect();
 * </pre>
 */
public class PresenceLobbyManager {

    private static final String TAG = "PresenceLobby";

    // === Phoenix Channel ===
    private PhoenixChannel channel;
    private String presenceKey;

    // === 当前 Presence 状态缓存 (key -> 房间信息) ===
    private final Map<String, PresenceRoomInfo> presenceState = new HashMap<>();

    // === 回调 ===
    private OnRoomsSyncListener syncListener;

    private final Json json;

    // ==================== 回调接口 ====================

    /**
     * 房间列表同步回调。每次有人加入/离开/更新都会触发。
     */
    public interface OnRoomsSyncListener {
        /**
         * 房间列表已更新 (全量)
         * @param allRooms 当前所有在线房间
         */
        void onSync(List<PresenceRoomInfo> allRooms);
    }

    /**
     * 连接状态变化回调
     */
    public interface OnStatusListener {
        /** WebSocket 已连接到 Supabase */
        void onConnected();

        /** 已成功加入 Presence 频道，可以开始 track */
        void onJoined();

        /** 发生错误 */
        void onError(String message);

        /** 连接已断开 (可能正在重连) */
        void onDisconnected(String reason);
    }

    // ==================== 构造 ====================

    public PresenceLobbyManager() {
        this.json = new Json();
        this.json.setOutputType(JsonWriter.OutputType.json);
    }

    // ==================== 连接管理 ====================

    /**
     * 连接到大厅频道并开始监听 Presence 事件
     *
     * @param syncListener   房间列表同步回调 (不可为 null)
     * @param statusListener 连接状态回调 (可为 null)
     */
    public void connect(OnRoomsSyncListener syncListener, OnStatusListener statusListener) {
        this.syncListener = syncListener;

        // 生成唯一的 Presence Key (用于在频道内标识自己)
        this.presenceKey = "user_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);

        // 创建 Phoenix Channel
        channel = new PhoenixChannel(
            SupabaseConfig.REALTIME_URL,
            SupabaseConfig.PUBLISHABLE_KEY,
            SupabaseConfig.LOBBY_CHANNEL
        );

        channel.setListener(new PhoenixChannel.ChannelListener() {
            @Override
            public void onConnected() {
                DLog.logT(TAG, "WebSocket 已连接");
                if (statusListener != null) statusListener.onConnected();
            }

            @Override
            public void onJoined() {
                DLog.logT(TAG, "已加入大厅频道");
                if (statusListener != null) statusListener.onJoined();
            }

            @Override
            public void onPresenceState(JsonValue payload) {
                handlePresenceState(payload);
            }

            @Override
            public void onPresenceDiff(JsonValue payload) {
                handlePresenceDiff(payload);
            }

            @Override
            public void onError(String message, Throwable cause) {
                DLog.logErrT(TAG, "频道错误: " + message + (cause != null ? " | " + cause : ""));
                if (statusListener != null) statusListener.onError(message);
            }

            @Override
            public void onDisconnected(int code, String reason) {
                DLog.logT(TAG, "连接断开: " + reason);
                if (statusListener != null) statusListener.onDisconnected(reason);
            }
        });

        channel.connect(presenceKey);
    }

    /**
     * 断开连接并释放所有资源
     */
    public void disconnect() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        presenceState.clear();
    }

    /**
     * 是否已连接并加入频道
     */
    public boolean isReady() {
        return channel != null && channel.isJoined();
    }

    // ==================== 房间发布 ====================

    /**
     * 房主: 发布自己的房间信息到 Presence
     * <p>
     * 调用后，所有已连接的客户端都会通过 {@link OnRoomsSyncListener#onSync} 收到更新。
     *
     * @param info 房间元数据
     */
    public void publishRoom(PresenceRoomInfo info) {
        if (!isReady()) {
            DLog.logErrT(TAG, "频道未就绪，无法发布房间");
            return;
        }

        info.presenceKey = this.presenceKey;
        String stateJson = buildRoomJson(info);
        channel.trackPresence(stateJson);
        DLog.logT(TAG, "已发布房间: " + info.roomName);
    }

    /**
     * 房主: 更新房间信息 (如人数变化、状态变化)
     * <p>
     * Presence track 是幂等的，重复调用会覆盖之前的状态。
     *
     * @param info 更新后的房间元数据
     */
    public void updateRoom(PresenceRoomInfo info) {
        publishRoom(info); // track 是幂等操作
    }

    /**
     * 房主: 取消发布房间 (从 Presence 中移除)
     * <p>
     * 即使不调用此方法，断开 WebSocket 也会自动移除。
     * 但主动调用可以让其他客户端更快收到更新。
     */
    public void unpublishRoom() {
        if (channel != null && channel.isJoined()) {
            channel.untrackPresence();
            DLog.logT(TAG, "已取消发布房间");
        }
    }

    // ==================== 获取房间列表 ====================

    /**
     * 获取当前缓存的所有在线房间列表 (快照)
     */
    public List<PresenceRoomInfo> getRooms() {
        return new ArrayList<>(presenceState.values());
    }

    /**
     * 获取当前在线房间数量
     */
    public int getRoomCount() {
        return presenceState.size();
    }

    // ==================== Presence 事件处理 ====================

    /**
     * 处理 presence_state 事件 (全量同步，加入频道后立即触发)
     * <p>
     * payload 结构:
     * <pre>
     * {
     *   "user_key_1": { "metas": [{ "roomName": "xxx", ... }] },
     *   "user_key_2": { "metas": [{ "roomName": "yyy", ... }] }
     * }
     * </pre>
     */
    private void handlePresenceState(JsonValue payload) {
        presenceState.clear();

        for (JsonValue entry = payload.child; entry != null; entry = entry.next) {
            String key = entry.name;
            PresenceRoomInfo room = parsePresenceEntry(key, entry);
            if (room != null) {
                presenceState.put(key, room);
            }
        }

        DLog.logT(TAG, "Presence 全量同步完成，共 " + presenceState.size() + " 个房间");
        notifySync();
    }

    /**
     * 处理 presence_diff 事件 (增量更新)
     * <p>
     * payload 结构:
     * <pre>
     * {
     *   "joins": { "new_key": { "metas": [...] } },
     *   "leaves": { "left_key": { "metas": [...] } }
     * }
     * </pre>
     */
    private void handlePresenceDiff(JsonValue payload) {
        boolean changed = false;

        // 处理 joins
        JsonValue joins = payload.get("joins");
        if (joins != null) {
            for (JsonValue entry = joins.child; entry != null; entry = entry.next) {
                String key = entry.name;
                PresenceRoomInfo room = parsePresenceEntry(key, entry);
                if (room != null) {
                    presenceState.put(key, room);
                    changed = true;
                    DLog.logT(TAG, "房间加入: " + room.roomName + " (" + key + ")");
                }
            }
        }

        // 处理 leaves
        JsonValue leaves = payload.get("leaves");
        if (leaves != null) {
            for (JsonValue entry = leaves.child; entry != null; entry = entry.next) {
                String key = entry.name;
                PresenceRoomInfo removed = presenceState.remove(key);
                if (removed != null) {
                    changed = true;
                    DLog.logT(TAG, "房间离开: " + removed.roomName + " (" + key + ")");
                }
            }
        }

        if (changed) {
            DLog.logT(TAG, "Presence 增量更新完成，当前 " + presenceState.size() + " 个房间");
            notifySync();
        }
    }

    /**
     * 解析单个 Presence 条目为 PresenceRoomInfo
     *
     * @param key   Presence key (如 "user_xxx")
     * @param entry JsonValue，结构为 { "metas": [{ ...room fields... }] }
     */
    private PresenceRoomInfo parsePresenceEntry(String key, JsonValue entry) {
        try {
            JsonValue metas = entry.get("metas");
            if (metas == null || metas.size == 0) return null;

            // 取 metas 数组的第一个元素
            JsonValue meta = metas.get(0);
            if (meta == null) return null;

            PresenceRoomInfo room = new PresenceRoomInfo();
            room.presenceKey = key;
            room.roomName = meta.getString("roomName", "未命名房间");
            room.hostIp = meta.getString("hostIp", "");
            room.localIp = meta.getString("localIp", "");
            room.hostPort = meta.getInt("hostPort", 0);
            room.currentPlayers = meta.getInt("currentPlayers", 1);
            room.maxPlayers = meta.getInt("maxPlayers", 6);
            room.status = meta.getString("status", "waiting");
            return room;
        } catch (Exception e) {
            DLog.logErrT(TAG, "解析 Presence 条目失败 (key=" + key + "): " + e);
            return null;
        }
    }

    /**
     * 通知上层房间列表已更新
     */
    private void notifySync() {
        if (syncListener != null) {
            syncListener.onSync(getRooms());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 将 PresenceRoomInfo 构建为 JSON 字符串 (用于 Presence track payload)
     */
    private String buildRoomJson(PresenceRoomInfo info) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{");
        sb.append("\"roomName\":\"").append(escapeJson(info.roomName)).append("\"");
        sb.append(",\"hostIp\":\"").append(escapeJson(info.hostIp)).append("\"");
        sb.append(",\"localIp\":\"").append(escapeJson(info.localIp)).append("\"");
        sb.append(",\"hostPort\":").append(info.hostPort);
        sb.append(",\"currentPlayers\":").append(info.currentPlayers);
        sb.append(",\"maxPlayers\":").append(info.maxPlayers);
        sb.append(",\"status\":\"").append(escapeJson(info.status)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /** 简易 JSON 字符串转义 */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
}
