package com.goldsprite.gdengine.netcode.common.supabase;

/**
 * Supabase 云大厅配置接口。
 * <p>
 * 通用网络库仅定义契约，具体的 URL / Key 等由游戏项目实现。
 * <p>
 * 典型用法:
 * <pre>
 *   // 游戏项目中
 *   public class MySupabaseConfig implements SupabaseConfig {
 *       public String getRealtimeUrl() { return "wss://xxx.supabase.co/realtime/v1/websocket"; }
 *       public String getPublishableKey() { return "sb_publishable_xxx"; }
 *       public String getLobbyChannel() { return "game_lobby"; }
 *   }
 *
 *   // 使用
 *   new PresenceLobbyManager(new MySupabaseConfig());
 * </pre>
 */
public interface SupabaseConfig {

    /**
     * Supabase Realtime WebSocket 端点
     * <p>
     * 格式: wss://{PROJECT_REF}.supabase.co/realtime/v1/websocket
     */
    String getRealtimeUrl();

    /**
     * Supabase Publishable Key (客户端操作云端资源的密钥，不是后端私钥)
     */
    String getPublishableKey();

    /**
     * 大厅 Presence 频道名称 (所有用户共享同一频道)
     */
    String getLobbyChannel();
}
