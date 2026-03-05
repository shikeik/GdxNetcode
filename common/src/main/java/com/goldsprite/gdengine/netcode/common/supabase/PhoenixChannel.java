package com.goldsprite.gdengine.netcode.common.supabase;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.Timer;
import com.goldsprite.gdengine.log.DLog;

/**
 * Supabase Realtime Phoenix Channel 客户端
 * <p>
 * 封装了底层 WebSocket 连接及 Phoenix Channel 协议的核心交互：
 * <ul>
 *   <li>phx_join / phx_leave —— 加入/离开频道</li>
 *   <li>heartbeat —— 每 30 秒 Phoenix 心跳保活</li>
 *   <li>presence track / untrack —— Presence 状态发布/撤销</li>
 *   <li>presence_state / presence_diff —— 接收实时 Presence 事件</li>
 * </ul>
 *
 * 使用方式: 由 {@link PresenceLobbyManager} 在上层调用，业务方不应直接使用本类。
 */
public class PhoenixChannel {

    private static final String TAG = "PhoenixChannel";
    /** 心跳专用标签（默认黑名单，避免每30秒刷屏） */
    private static final String TAG_HEARTBEAT = "PhoenixHB";
    /** 协议线路级别日志标签（>>>/<< 原始 JSON 报文） */
    private static final String TAG_WIRE = "PhoenixWire";

    // === Phoenix 协议常量 ===
    private static final String EVENT_PHX_JOIN = "phx_join";
    private static final String EVENT_PHX_REPLY = "phx_reply";
    private static final String EVENT_HEARTBEAT = "heartbeat";
    private static final String EVENT_PRESENCE_STATE = "presence_state";
    private static final String EVENT_PRESENCE_DIFF = "presence_diff";
    private static final String EVENT_SYSTEM = "system";
    private static final String TOPIC_PHOENIX = "phoenix";

    // === 配置 ===
    private final String realtimeUrl;
    private final String apiKey;
    private final String topic; // "realtime:{channelName}"

    // === 状态 ===
    private WebSocketClient wsClient;
    private final AtomicInteger refCounter = new AtomicInteger(0);
    private String joinRef;
    private boolean joined = false;
    private Timer.Task heartbeatTask;
    private static final float HEARTBEAT_INTERVAL = 30f; // Phoenix 标准值

    // === 重连 ===
    private boolean shouldReconnect = true;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final float BASE_RECONNECT_DELAY = 1f; // 秒
    private static final float MAX_RECONNECT_DELAY = 30f;
    private Timer.Task reconnectTask;
    private boolean reconnecting = false;      // 是否处于重连周期中
    private long disconnectTimestamp = 0;       // 断开时间戳
    private int lastReconnectAttempts = 0;      // 重连成功时的尝试次数

    // === 心跳监控 ===
    private int heartbeatSent = 0;
    private int heartbeatReceived = 0;
    private int consecutiveMisses = 0;
    private boolean awaitingHeartbeatReply = false;
    private static final int HEARTBEAT_MONITOR_INTERVAL = 10; // 每N次输出摘要

    // === 回调 ===
    private ChannelListener listener;

    private final JsonReader jsonReader = new JsonReader();

    /**
     * 频道事件监听器
     */
    public interface ChannelListener {
        /** WebSocket 连接成功 */
        void onConnected();

        /** 已成功加入频道 */
        void onJoined();

        /** 收到 Presence 全量同步 (presence_state) */
        void onPresenceState(JsonValue payload);

        /** 收到 Presence 增量更新 (presence_diff) */
        void onPresenceDiff(JsonValue payload);

        /** 发生错误 */
        void onError(String message, Throwable cause);

        /** 连接已关闭 (仅首次断开触发，重连周期中不重复触发) */
        void onDisconnected(int code, String reason);

        /** 自动重连成功，已重新加入频道 */
        default void onReconnected(int totalAttempts, long disconnectedMs) {}
    }

    /**
     * @param realtimeUrl Supabase Realtime WebSocket 端点 (wss://xxx.supabase.co/realtime/v1/websocket)
     * @param apiKey      Supabase Publishable Key
     * @param channelName 频道名称 (如 "game_lobby")
     */
    public PhoenixChannel(String realtimeUrl, String apiKey, String channelName) {
        this.realtimeUrl = realtimeUrl;
        this.apiKey = apiKey;
        this.topic = "realtime:" + channelName;
    }

    public void setListener(ChannelListener listener) {
        this.listener = listener;
    }

    // ==================== 连接管理 ====================

    /**
     * 建立 WebSocket 连接。
     * 连接成功后会自动发起 phx_join 加入频道。
     *
     * @param presenceKey 本客户端在 Presence 中的唯一标识
     */
    public void connect(final String presenceKey) {
        // 取消任何待处理的重连定时器，防止与新连接冲突
        cancelReconnect();

        // 清理旧的 WebSocket 客户端（不调用 disconnect 以保留重连状态）
        if (wsClient != null) {
            try { wsClient.close(); } catch (Exception ignored) {}
            wsClient = null;
        }

        shouldReconnect = true;
        if (!reconnecting) {
            reconnectAttempts = 0;
        }

        // 构造带 apikey 参数的 WebSocket URL
        String url = realtimeUrl + "?apikey=" + apiKey + "&vsn=1.0.0";
        DLog.logT(TAG, "正在连接 Realtime: " + realtimeUrl);

        try {
            wsClient = new WebSocketClient(new URI(url)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    lastReconnectAttempts = reconnectAttempts;
                    reconnectAttempts = 0;
                    startHeartbeat();

                    if (reconnecting) {
                        DLog.logT(TAG, "WebSocket 重连成功, 正在重新加入频道...");
                    } else {
                        DLog.logT(TAG, "WebSocket 连接成功 (状态码: " + handshake.getHttpStatus() + ")");
                        if (listener != null) {
                            Gdx.app.postRunnable(() -> listener.onConnected());
                        }
                    }

                    // 自动加入频道
                    joinChannel(presenceKey);
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // 如果这不是当前活动的 WebSocket，忽略旧连接的关闭事件
                    if (wsClient != this) return;

                    joined = false;
                    stopHeartbeat();

                    if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        if (!reconnecting) {
                            // 重连周期的首次断开，通知监听器
                            reconnecting = true;
                            disconnectTimestamp = System.currentTimeMillis();
                            DLog.logWarnT(TAG, "连接断开 (code=" + code + "), 正在自动重连...");
                            if (listener != null) {
                                Gdx.app.postRunnable(() -> listener.onDisconnected(code, reason));
                            }
                        }
                        scheduleReconnect(presenceKey);
                    } else {
                        reconnecting = false;
                        DLog.logWarnT(TAG, "连接已关闭 (code=" + code + ", reason=" + reason + ")");
                        if (listener != null) {
                            Gdx.app.postRunnable(() -> listener.onDisconnected(code, reason));
                        }
                    }
                }

                @Override
                public void onError(Exception ex) {
                    DLog.logErrT(TAG, "WebSocket 发生错误: " + ex);
                    if (listener != null) {
                        Gdx.app.postRunnable(() -> listener.onError("WebSocket 错误: " + ex.getMessage(), ex));
                    }
                }
            };

            wsClient.connect();
        } catch (Exception e) {
            DLog.logErrT(TAG, "创建 WebSocket 连接失败: " + e);
            if (listener != null) {
                Gdx.app.postRunnable(() -> listener.onError("创建连接失败: " + e.getMessage(), e));
            }
        }
    }

    /**
     * 主动断开连接并释放所有资源。
     * 不会触发自动重连。
     */
    public void disconnect() {
        shouldReconnect = false;
        reconnecting = false;
        joined = false;
        stopHeartbeat();
        cancelReconnect();

        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception e) {
                DLog.logErrT(TAG, "关闭 WebSocket 异常: " + e);
            }
            wsClient = null;
        }
    }

    /** 是否已加入频道 */
    public boolean isJoined() {
        return joined;
    }

    /** WebSocket 是否处于打开状态 */
    public boolean isConnected() {
        return wsClient != null && wsClient.isOpen();
    }

    // ==================== Phoenix 协议消息 ====================

    /**
     * 发起 phx_join 加入频道（含 Presence 配置）
     */
    private void joinChannel(String presenceKey) {
        joinRef = nextRef();
        // 构造 join payload:
        // { "config": { "presence": { "key": "{presenceKey}" } } }
        String payload = "{\"config\":{\"presence\":{\"key\":\"" + escapeJson(presenceKey) + "\"}}}";
        sendMessage(topic, EVENT_PHX_JOIN, payload, joinRef, joinRef);
        DLog.logT(TAG, "发送 phx_join (ref=" + joinRef + ", presenceKey=" + presenceKey + ")");
    }

    /**
     * 在 Presence 中发布 (track) 当前房间信息
     *
     * @param stateJson 要发布的状态 JSON 字符串，如 {"roomName":"xxx","hostIp":"1.2.3.4",...}
     */
    public void trackPresence(String stateJson) {
        if (!joined) {
            DLog.logErrT(TAG, "尚未加入频道，无法 track");
            return;
        }
        // 构造 presence track 消息:
        // { "type": "presence", "event": "track", "payload": {stateJson} }
        String payload = "{\"type\":\"presence\",\"event\":\"track\",\"payload\":" + stateJson + "}";
        sendMessage(topic, "presence", payload, nextRef(), joinRef);
        DLog.logT(TAG, "发送 presence track");
    }

    /**
     * 从 Presence 中撤销 (untrack) 当前状态
     */
    public void untrackPresence() {
        if (!joined) return;
        String payload = "{\"type\":\"presence\",\"event\":\"untrack\"}";
        sendMessage(topic, "presence", payload, nextRef(), joinRef);
        DLog.logT(TAG, "发送 presence untrack");
    }

    // ==================== 消息收发 ====================

    /**
     * 发送 Phoenix 协议格式的 JSON 消息
     * Phoenix Wire Format: { "topic": ..., "event": ..., "payload": ..., "ref": ..., "join_ref": ... }
     */
    private void sendMessage(String topic, String event, String payloadJson, String ref, String joinRefVal) {
        if (wsClient == null || !wsClient.isOpen()) {
            DLog.logErrT(TAG, "WebSocket 未连接，无法发送消息");
            return;
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"topic\":\"").append(escapeJson(topic)).append("\"");
        sb.append(",\"event\":\"").append(escapeJson(event)).append("\"");
        sb.append(",\"payload\":").append(payloadJson);
        sb.append(",\"ref\":\"").append(ref).append("\"");
        if (joinRefVal != null) {
            sb.append(",\"join_ref\":\"").append(joinRefVal).append("\"");
        }
        sb.append("}");

        String msg = sb.toString();
        wsClient.send(msg);
        DLog.logT(TAG_WIRE, ">>> " + msg);
    }

    /**
     * 处理从 WebSocket 收到的消息
     */
    private void handleMessage(String message) {
        DLog.logT(TAG_WIRE, "<<< " + message);

        JsonValue json;
        try {
            json = jsonReader.parse(message);
        } catch (Exception e) {
            DLog.logErrT(TAG, "JSON 解析失败: " + message + " | " + e);
            return;
        }

        String msgTopic = json.getString("topic", "");
        String msgEvent = json.getString("event", "");

        // Phoenix 心跳回复
        if (TOPIC_PHOENIX.equals(msgTopic) && EVENT_PHX_REPLY.equals(msgEvent)) {
            heartbeatReceived++;
            awaitingHeartbeatReply = false;
            DLog.logT(TAG_HEARTBEAT, "收到心跳回复 (" + heartbeatReceived + "/" + heartbeatSent + ")");
            return;
        }

        // System 消息 (如 extension reload)
        if (EVENT_SYSTEM.equals(msgEvent)) {
            DLog.logT(TAG, "收到系统消息: " + message);
            return;
        }

        // 频道不匹配则忽略
        if (!topic.equals(msgTopic)) {
            return;
        }

        // phx_reply —— 只处理 phx_join 的回复（ref 必须匹配 joinRef）
        // 忽略 track/untrack 等操作的 reply，否则会导致 onJoined 重复触发
        if (EVENT_PHX_REPLY.equals(msgEvent)) {
            String ref = json.getString("ref", "");
            if (joinRef != null && joinRef.equals(ref)) {
                handleJoinReply(json);
            }
            return;
        }

        // presence_state —— 全量 Presence 同步
        if (EVENT_PRESENCE_STATE.equals(msgEvent)) {
            JsonValue payload = json.get("payload");
            if (payload != null && listener != null) {
                Gdx.app.postRunnable(() -> listener.onPresenceState(payload));
            }
            return;
        }

        // presence_diff —— 增量 Presence 更新
        if (EVENT_PRESENCE_DIFF.equals(msgEvent)) {
            JsonValue payload = json.get("payload");
            if (payload != null && listener != null) {
                Gdx.app.postRunnable(() -> listener.onPresenceDiff(payload));
            }
            return;
        }
    }

    /**
     * 处理 phx_join 的回复
     */
    private void handleJoinReply(JsonValue json) {
        JsonValue payload = json.get("payload");
        if (payload != null) {
            String status = payload.getString("status", "");
            if ("ok".equals(status)) {
                joined = true;

                if (reconnecting) {
                    // 重连周期完成
                    long elapsed = System.currentTimeMillis() - disconnectTimestamp;
                    final int attempts = lastReconnectAttempts;
                    final long elapsedMs = elapsed;
                    reconnecting = false;
                    DLog.logInfoT(TAG, "重连成功, 已重新加入频道 (重试" + attempts + "次, 断开" + (elapsed / 1000) + "s)");
                    if (listener != null) {
                        Gdx.app.postRunnable(() -> listener.onReconnected(attempts, elapsedMs));
                    }
                } else {
                    DLog.logT(TAG, "成功加入频道: " + topic);
                    if (listener != null) {
                        Gdx.app.postRunnable(() -> listener.onJoined());
                    }
                }
            } else {
                String errorMsg = "加入频道失败";
                JsonValue response = payload.get("response");
                if (response != null) {
                    errorMsg += ": " + response.toString();
                }
                final String msg = errorMsg;
                DLog.logErrT(TAG, msg);
                if (listener != null) {
                    Gdx.app.postRunnable(() -> listener.onError(msg, null));
                }
            }
        }
    }

    // ==================== 心跳 ====================

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatSent = 0;
        heartbeatReceived = 0;
        consecutiveMisses = 0;
        awaitingHeartbeatReply = false;

        heartbeatTask = Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                if (wsClient != null && wsClient.isOpen()) {
                    // 检测上次心跳是否收到回复
                    if (awaitingHeartbeatReply) {
                        consecutiveMisses++;
                        DLog.logWarnT(TAG, "心跳丢失! 连续丢失 " + consecutiveMisses);
                    } else {
                        consecutiveMisses = 0;
                    }

                    sendMessage(TOPIC_PHOENIX, EVENT_HEARTBEAT, "{}", nextRef(), null);
                    heartbeatSent++;
                    awaitingHeartbeatReply = true;

                    // 定期输出心跳监控摘要
                    if (heartbeatSent % HEARTBEAT_MONITOR_INTERVAL == 0) {
                        DLog.logInfoT(TAG, "心跳监控: 已发送 " + heartbeatSent
                            + ", 已回复 " + heartbeatReceived
                            + ", 丢失 " + (heartbeatSent - heartbeatReceived));
                    }
                }
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL);
        DLog.logT(TAG, "Phoenix 心跳已启动 (间隔 " + HEARTBEAT_INTERVAL + "s)");
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    // ==================== 自动重连 ====================

    private void scheduleReconnect(final String presenceKey) {
        reconnectAttempts++;
        // 指数退避: 1s, 2s, 4s, 8s ... 最大 30s
        float delay = Math.min(BASE_RECONNECT_DELAY * (1 << (reconnectAttempts - 1)), MAX_RECONNECT_DELAY);

        // 仅首次重连输出到主标签，后续静默避免刷屏
        if (reconnectAttempts == 1) {
            DLog.logT(TAG, "计划 " + delay + "s 后重连 (最多 " + MAX_RECONNECT_ATTEMPTS + " 次)");
        } else {
            DLog.logT(TAG_HEARTBEAT, "重连 #" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ", " + delay + "s 后");
        }

        cancelReconnect();
        reconnectTask = Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                if (shouldReconnect) {
                    connect(presenceKey);
                }
            }
        }, delay);
    }

    private void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
        }
    }

    // ==================== 工具方法 ====================

    private String nextRef() {
        return String.valueOf(refCounter.incrementAndGet());
    }

    /** 简易 JSON 字符串转义 */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
