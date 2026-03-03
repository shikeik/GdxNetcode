package com.goldsprite.gdengine.netcode.common.headless;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.goldsprite.gdengine.log.DLog;
import com.goldsprite.gdengine.netcode.NetworkConnectionListener;
import com.goldsprite.gdengine.netcode.NetworkManager;
import com.goldsprite.gdengine.netcode.ReliableUdpTransport;
import com.goldsprite.gdengine.netcode.UdpSocketTransport;
import com.goldsprite.gdengine.netcode.common.supabase.PresenceLobbyManager;
import com.goldsprite.gdengine.netcode.common.supabase.PresenceRoomInfo;
import com.goldsprite.gdengine.netcode.common.supabase.PublicIPResolver;
import com.goldsprite.gdengine.netcode.common.supabase.SupabaseConfig;

/**
 * 通用无头（Headless）服务器骨架。
 * <p>
 * 子类只需实现 5 个游戏回调即可运行 Dedicated Server，无需关心网络初始化和主循环驱动。
 * <p>
 * <b>生命周期</b>（由 {@code HeadlessApplication} 驱动）：
 * <ol>
 *   <li>{@link #create()} — 初始化 Transport / NetworkManager / ServerConsole → 调用子类
 *       {@link #registerPrefabs} / {@link #initGameLogic}</li>
 *   <li>{@link #render()} — 每帧调用：心跳检测 → {@link #onServerTick} → 网络同步 → 可靠层重传</li>
 *   <li>{@link #dispose()} — 优雅关闭网络、控制台</li>
 * </ol>
 *
 * <b>使用方式</b>（在下游项目 Launcher 中）：
 * <pre>
 * ServerConfig config = ServerConfig.load(args);
 * HeadlessApplicationConfiguration haConfig = new HeadlessApplicationConfiguration();
 * haConfig.updatesPerSecond = config.tickRate;
 * new HeadlessApplication(new MyDedicatedServer(config), haConfig);
 * </pre>
 */
public abstract class HeadlessGameServer extends ApplicationAdapter {

    // ── 配置 ──
    protected ServerConfig config;

    // ── 网络层 ──
    protected UdpSocketTransport rawTransport;
    protected ReliableUdpTransport transport;
    protected NetworkManager manager;

    // ── 控制台 ──
    protected ServerConsole console;

    // ── 云大厅 ──
    protected PresenceLobbyManager lobbyManager;
    protected PresenceRoomInfo roomInfo;
    private SupabaseConfig supabaseConfig;

    // ── 关闭标志 ──
    private volatile boolean shutdownRequested = false;

    // ══════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════

    public HeadlessGameServer(ServerConfig config) {
        this.config = config;
    }

    /**
     * 设置 Supabase 云大厅配置。
     * <p>
     * 若服务器需要注册到云大厅 (config.enableLobby=true)，必须在 create() 之前调用。
     *
     * @param supabaseConfig Supabase 配置实现
     */
    public void setSupabaseConfig(SupabaseConfig supabaseConfig) {
        this.supabaseConfig = supabaseConfig;
    }

    // ══════════════════════════════════════════
    //  抽象方法 — 子类实现游戏特定逻辑
    // ══════════════════════════════════════════

    /**
     * 注册所有预制体工厂到 NetworkManager。
     * <p>示例: {@code manager.registerPrefab(TANK_PREFAB_ID, TankSandboxUtils.createTankFactory());}
     */
    protected abstract void registerPrefabs(NetworkManager manager);

    /**
     * 初始化游戏逻辑（地图生成、物理系统等），在 Transport 启动之后调用。
     */
    protected abstract void initGameLogic(ServerConfig config);

    /**
     * 每帧服务器 tick（在心跳检测之后、网络同步之前调用）。
     * <p>子类在此驱动游戏逻辑（移动、碰撞、死亡判定等）。
     *
     * @param delta 本帧间隔（秒）
     * @param manager NetworkManager 实例
     */
    protected abstract void onServerTick(float delta, NetworkManager manager);

    /**
     * 新玩家连接。子类应在此：
     * <ol>
     *   <li>补发已有实体 + 状态快照</li>
     *   <li>为新玩家 Spawn 实体</li>
     *   <li>广播同步数据（如地图种子）</li>
     * </ol>
     *
     * @param clientId 新连接的客户端 ID
     * @param manager  NetworkManager 实例
     */
    protected abstract void onPlayerConnected(int clientId, NetworkManager manager);

    /**
     * 玩家断开连接。子类应在此清理业务层映射。
     * <p>注意: NetworkManager 已自动调用 {@code despawnByOwner(clientId)} 清理网络对象。
     *
     * @param clientId 断开的客户端 ID
     */
    protected abstract void onPlayerDisconnected(int clientId);

    /**
     * 返回当前在线玩家数量（供控制台 status 命令使用）。
     */
    public abstract int getOnlinePlayerCount();

    /**
     * 返回当前在线玩家 ID 集合（供控制台 players/kick 命令使用）。
     * 默认返回空集，子类应覆写。
     */
    public Set<Integer> getOnlinePlayerIds() {
        return Collections.emptySet();
    }

    /**
     * 返回在线玩家 ID 到名称的映射（供控制台 players 命令显示）。
     * <p>
     * 默认使用 "Player#id" 格式。子类可覆写以提供真实玩家名称。
     */
    public Map<Integer, String> getPlayerNames() {
        Map<Integer, String> names = new HashMap<>();
        for (int id : getOnlinePlayerIds()) {
            names.put(id, "Player#" + id);
        }
        return names;
    }

    // ══════════════════════════════════════════
    //  Application 生命周期
    // ══════════════════════════════════════════

    @Override
    public void create() {
        printBanner();

        // 0. 配置日志等级
        configureLogLevel();

        // 1. 初始化网络传输层
        rawTransport = new UdpSocketTransport(true); // isServer = true
        transport = new ReliableUdpTransport(rawTransport);

        // 2. 初始化 NetworkManager
        manager = new NetworkManager();
        manager.setTickRate(config.tickRate);
        manager.setTransport(transport);

        // 3. 注册连接/断开事件监听
        manager.setConnectionListener(new NetworkConnectionListener() {
            @Override
            public void onClientConnected(int clientId) {
                // 排到主线程执行
                Gdx.app.postRunnable(() -> {
                    if (getOnlinePlayerCount() >= config.maxPlayers) {
                        DLog.logWarnT("Server", "玩家数已满 (" + config.maxPlayers + ")，拒绝 Client #" + clientId);
                        // TODO: 发送拒绝包并断开
                        return;
                    }
                    DLog.logT("Server", "客户端连接 #" + clientId);
                    onPlayerConnected(clientId, manager);
                    updateLobbyPlayerCount();
                });
            }

            @Override
            public void onClientDisconnected(int clientId) {
                // 此回调在 NetworkManager.tickInternal() 主线程中触发
                DLog.logT("Server", "客户端断开 #" + clientId);
                onPlayerDisconnected(clientId);
                updateLobbyPlayerCount();
            }
        });

        // 4. 子类注册预制体
        registerPrefabs(manager);

        // 5. 启动服务器监听
        transport.startServer(config.port);

        // 6. 子类初始化游戏逻辑
        initGameLogic(config);

        // 7. 启动控制台线程
        console = new ServerConsole(this);
        console.start();

        DLog.logT("Server", "服务器已启动 | 端口: " + config.port
                + " | TickRate: " + config.tickRate + "Hz"
                + " | 最大玩家: " + config.maxPlayers);

        // 8. 注册到云大厅（可选）
        if (config.enableLobby) {
            initCloudLobby();
        }
    }

    @Override
    public void render() {
        if (shutdownRequested) {
            performShutdown();
            return;
        }

        float delta = Gdx.graphics.getDeltaTime();

        // ── 心跳超时检测 ──
        if (transport != null) {
            transport.checkHeartbeatTimeouts((long) (config.timeoutSec * 1000));
        }

        // ── 子类游戏逻辑 tick ──
        onServerTick(delta, manager);

        // ── 网络同步（累加器模式，按 tickRate 自动控制频率） ──
        manager.tick(delta);

        // ── 可靠层超时重传 ──
        transport.tickReliable();
    }

    @Override
    public void dispose() {
        if (lobbyManager != null) {
            lobbyManager.disconnect();
            lobbyManager = null;
            DLog.logT("Server", "已从云大厅注销");
        }
        if (console != null) {
            console.shutdown();
        }
        if (transport != null) {
            try {
                transport.disconnect();
            } catch (Exception e) {
                DLog.logWarn("Server", "关闭 Transport 时异常: " + e.getMessage());
            }
        }
        DLog.logT("Server", "服务器已关闭");
    }

    // ══════════════════════════════════════════
    //  公共 API
    // ══════════════════════════════════════════

    /** 获取服务器配置 */
    public ServerConfig getConfig() {
        return config;
    }

    /** 获取 NetworkManager */
    public NetworkManager getManager() {
        return manager;
    }

    /** 获取 Transport */
    public ReliableUdpTransport getTransport() {
        return transport;
    }

    /** 请求优雅关闭（可从控制台线程安全调用） */
    public void requestShutdown() {
        shutdownRequested = true;
    }

    /**
     * 踢出指定玩家（发送踢出通知 → despawn → 触发子类清理 → 断开传输层连接）。
     * 可由控制台命令或业务逻辑调用。
     *
     * @param clientId 要踢出的客户端 ID
     */
    public void kickPlayer(int clientId) {
        // 1. 子类可在此发送踢出通知 RPC（在 despawn 前执行，确保客户端收到）
        onBeforeKick(clientId);
        // 2. 清除该玩家的所有网络对象
        if (manager != null) {
            manager.despawnByOwner(clientId);
        }
        // 3. 触发子类业务清理
        onPlayerDisconnected(clientId);
        // 4. 断开传输层连接（使客户端检测到断线）
        if (transport != null) {
            transport.disconnectClient(clientId);
        }
        updateLobbyPlayerCount();
        DLog.logT("Server", "已踢出玩家 #" + clientId);
    }

    /**
     * 踢出玩家前的钩子方法——子类可覆写以发送踢出通知 RPC。
     * <p>
     * 此方法在 {@code despawnByOwner} 调用之前执行，确保客户端仍拥有网络对象可以接收 RPC。
     * 默认空实现。
     *
     * @param clientId 即将被踢出的客户端 ID
     */
    protected void onBeforeKick(int clientId) {
        // 默认无操作，子类覆写以发送 rpcKicked 等通知
    }

    // ══════════════════════════════════════════
    //  内部方法
    // ══════════════════════════════════════════

    /** 执行优雅关闭流程 */
    private void performShutdown() {
        DLog.logT("Server", "正在优雅关闭...");
        dispose();
        Gdx.app.exit();
    }

    /** 打印启动 Banner */
    private void printBanner() {
        System.out.println("========================================");
        System.out.println("  Dedicated Server");
        System.out.println("  端口: " + config.port + " | TickRate: " + config.tickRate + "Hz");
        System.out.println("  输入 'help' 查看可用命令");
        System.out.println("========================================");
    }

    /**
     * 根据 ServerConfig.logLevel 配置 DLog 全局日志等级。
     * 支持 DEBUG / INFO / WARN / ERROR 四级。
     */
    private void configureLogLevel() {
        DLog.Level level = DLog.parseLevel(config.logLevel);
        if (level != null) {
            DLog.setGlobalLogLevel(level);
            DLog.logT("Server", "日志等级已设置为: " + level.name());
        } else {
            DLog.logWarnT("Server", "未知日志等级: " + config.logLevel + "，使用默认 DEBUG");
        }
    }

    // ══════════════════════════════════════════
    //  云大厅集成
    // ══════════════════════════════════════════

    /**
     * 初始化云大厅连接并在频道就绪后自动发布房间。
     * <p>
     * 连接成功且加入频道后，会异步获取公网 IP 并发布房间元数据，
     * 使客户端在 SupabaseLobbyScreen 中能发现此服务器。
     */
    private void initCloudLobby() {
        if (supabaseConfig == null) {
            DLog.logWarnT("Server", "云大厅已启用但未设置 SupabaseConfig，跳过云大厅注册");
            return;
        }
        DLog.logT("Server", "正在连接云大厅...");
        lobbyManager = new PresenceLobbyManager(supabaseConfig);
        lobbyManager.connect(
            // 服务端不关心其他房间的同步事件
            rooms -> { },
            new PresenceLobbyManager.OnStatusListener() {
                @Override
                public void onConnected() {
                    DLog.logT("Server", "云大厅 WebSocket 已连接");
                }

                @Override
                public void onJoined() {
                    DLog.logT("Server", "已加入云大厅频道，正在发布房间...");
                    publishServerRoom();
                }

                @Override
                public void onError(String message) {
                    DLog.logWarnT("Server", "云大厅错误: " + message);
                }

                @Override
                public void onDisconnected(String reason) {
                    DLog.logWarnT("Server", "云大厅连接断开: " + reason);
                }
            }
        );
    }

    /**
     * 获取公网 IP 并发布房间到 Presence 大厅。
     * 若公网 IP 获取失败，则使用局域网 IP 作为 fallback。
     */
    private void publishServerRoom() {
        final String localIp = PublicIPResolver.getLocalIP();

        PublicIPResolver.resolvePublicIP(new PublicIPResolver.ResolveCallback() {
            @Override
            public void onSuccess(final String publicIp) {
                Gdx.app.postRunnable(() -> {
                    roomInfo = new PresenceRoomInfo(
                        config.roomName, publicIp, localIp, config.port,
                        getOnlinePlayerCount(), config.maxPlayers
                    );
                    lobbyManager.publishRoom(roomInfo);
                    DLog.logT("Server", "房间已发布到云大厅: " + config.roomName
                        + " | 公网=" + publicIp + " 局域网=" + localIp
                        + " 端口=" + config.port);
                });
            }

            @Override
            public void onError(final Throwable t) {
                Gdx.app.postRunnable(() -> {
                    DLog.logWarnT("Server", "获取公网IP失败，使用局域网IP注册: " + t.getMessage());
                    roomInfo = new PresenceRoomInfo(
                        config.roomName, localIp, localIp, config.port,
                        getOnlinePlayerCount(), config.maxPlayers
                    );
                    lobbyManager.publishRoom(roomInfo);
                    DLog.logT("Server", "房间已发布到云大厅(局域网): " + config.roomName
                        + " | IP=" + localIp + " 端口=" + config.port);
                });
            }
        });
    }

    /**
     * 更新云大厅中的房间人数。
     * 在玩家连接/断开后自动调用。
     */
    protected void updateLobbyPlayerCount() {
        if (lobbyManager != null && lobbyManager.isReady() && roomInfo != null) {
            int count = getOnlinePlayerCount();
            roomInfo.currentPlayers = count;
            // 人满时更新状态为 full，否则恢复 waiting
            if (count >= config.maxPlayers) {
                roomInfo.status = "full";
            } else {
                roomInfo.status = "waiting";
            }
            lobbyManager.updateRoom(roomInfo);
        }
    }
}
