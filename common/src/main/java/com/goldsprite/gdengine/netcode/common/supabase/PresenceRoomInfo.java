package com.goldsprite.gdengine.netcode.common.supabase;

/**
 * Presence 频道中，每个房主发布的房间元数据。
 * <p>
 * 不再是数据库行映射，而是 Presence Track 时发送/接收的 JSON 载荷。
 * 当房主 track 到频道后，所有已连接客户端都能实时收到这些信息。
 */
public class PresenceRoomInfo {

    /** 房间显示名 (如 "大马猴的坦克战场") */
    public String roomName = "";

    /** 房主公网 IP */
    public String hostIp = "";

    /** 房主局域网 IP（同网络时使用） */
    public String localIp = "";

    /** 房主 UDP 监听端口 */
    public int hostPort = 20001;

    /** 当前玩家数 */
    public int currentPlayers = 1;

    /** 最大玩家数 */
    public int maxPlayers = 6;

    /** 房间状态: "waiting" | "playing" | "full" */
    public String status = "waiting";

    /** Presence Key (频道内唯一标识，由 PresenceLobbyManager 自动填充) */
    public String presenceKey = "";

    public PresenceRoomInfo() {
    }

    public PresenceRoomInfo(String roomName, String hostIp, int hostPort, int currentPlayers, int maxPlayers) {
        this(roomName, hostIp, "", hostPort, currentPlayers, maxPlayers);
    }

    public PresenceRoomInfo(String roomName, String hostIp, String localIp, int hostPort, int currentPlayers, int maxPlayers) {
        this.roomName = roomName;
        this.hostIp = hostIp;
        this.localIp = localIp != null ? localIp : "";
        this.hostPort = hostPort;
        this.currentPlayers = currentPlayers;
        this.maxPlayers = maxPlayers;
        this.status = "waiting";
    }

    /** 返回人数显示 (如 "2/6") */
    public String getPlayerCountDisplay() {
        return currentPlayers + "/" + maxPlayers;
    }

    /** 是否可加入 (状态为 waiting 且人未满) */
    public boolean isJoinable() {
        return "waiting".equals(status) && currentPlayers < maxPlayers;
    }

    @Override
    public String toString() {
        return "PresenceRoomInfo{" +
            "roomName='" + roomName + '\'' +
            ", hostIp='" + hostIp + '\'' +
            ", localIp='" + localIp + '\'' +
            ", hostPort=" + hostPort +
            ", players=" + currentPlayers + "/" + maxPlayers +
            ", status='" + status + '\'' +
            ", key='" + presenceKey + '\'' +
            '}';
    }
}
