package com.goldsprite.gdengine.netcode.supabase;

/**
 * 集中管理 Supabase 云大厅的全局配置，包括 URL 和 Publishable Key 等。
 */
public class SupabaseConfig {

    /**
     * 全局 Supabase 接口地址 (REST API)
     */
    public static final String URL = "https://ijiuncznaasfbamjuinm.supabase.co";

    /**
     * 全局 Supabase Publishable Key (用于客户端操作云端资源的密钥，不是后端私钥)
     */
    public static final String PUBLISHABLE_KEY = "sb_publishable_pEqL-0pZ4m6Q8zU5vUtTJg_bO2g2oSE";

    /**
     * Supabase Realtime WebSocket 端点
     * 格式: wss://{PROJECT_REF}.supabase.co/realtime/v1/websocket
     */
    public static final String REALTIME_URL = "wss://ijiuncznaasfbamjuinm.supabase.co/realtime/v1/websocket";

    /**
     * 大厅 Presence 频道名称 (所有用户共享同一频道)
     */
    public static final String LOBBY_CHANNEL = "game_lobby";
}
