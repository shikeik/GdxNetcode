package com.goldsprite.gdengine.netcode.headless;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import com.goldsprite.gdengine.log.DLog;

/**
 * 无头服务器启动配置。
 * <p>
 * 加载优先级（后者覆盖前者）:
 * <ol>
 *   <li>代码默认值</li>
 *   <li>server.properties 文件（工作目录下）</li>
 *   <li>命令行参数（--port, --tickrate 等）</li>
 * </ol>
 */
public class ServerConfig {

    // ── 网络 ──
    /** 服务器监听端口 */
    public int port = 20001;
    /** 最大玩家数 */
    public int maxPlayers = 8;
    /** 客户端心跳超时（秒） */
    public int timeoutSec = 10;

    // ── 性能 ──
    /** 服务器逻辑 tick 频率（Hz） */
    public int tickRate = 60;

    // ── 游戏 ──
    /** 地图名称 */
    public String map = "default";
    /** 友军伤害 */
    public boolean friendlyFire = false;

    // ── 云大厅 ──
    /** 是否注册到云大厅（让客户端在房间列表中发现此服务器） */
    public boolean enableLobby = true;
    /** 房间显示名称 */
    public String roomName = "Dedicated Server";

    // ── 日志 ──
    /** 日志级别: DEBUG / INFO / WARN / ERROR */
    public String logLevel = "INFO";
    /** 日志输出文件（空字符串则仅输出到控制台） */
    public String logFile = "";

    /**
     * 按 默认值 → server.properties → 命令行参数 的优先级加载配置。
     *
     * @param args main() 传入的命令行参数
     * @return 合并后的配置实例
     */
    public static ServerConfig load(String[] args) {
        ServerConfig config = new ServerConfig();

        // ── 1. 尝试加载 server.properties ──
        File propsFile = new File("server.properties");
        if (propsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(propsFile)) {
                Properties props = new Properties();
                props.load(fis);
                config.port        = getInt(props, "server.port", config.port);
                config.maxPlayers  = getInt(props, "server.max-players", config.maxPlayers);
                config.timeoutSec  = getInt(props, "server.timeout-sec", config.timeoutSec);
                config.tickRate    = getInt(props, "server.tickrate", config.tickRate);
                config.map         = props.getProperty("server.map", config.map);
                config.friendlyFire = Boolean.parseBoolean(
                        props.getProperty("server.friendly-fire", String.valueOf(config.friendlyFire)));
                config.enableLobby = Boolean.parseBoolean(
                        props.getProperty("server.enable-lobby", String.valueOf(config.enableLobby)));
                config.roomName    = props.getProperty("server.room-name", config.roomName);
                config.logLevel    = props.getProperty("log.level", config.logLevel);
                config.logFile     = props.getProperty("log.file", config.logFile);
                DLog.log("ServerConfig", "已从 server.properties 加载配置");
            } catch (Exception e) {
                DLog.logWarn("ServerConfig", "读取 server.properties 失败: " + e.getMessage());
            }
        }

        // ── 2. 命令行参数覆盖 ──
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--port":
                    config.port = Integer.parseInt(args[++i]);
                    break;
                case "--tickrate":
                    config.tickRate = Integer.parseInt(args[++i]);
                    break;
                case "--max-players":
                    config.maxPlayers = Integer.parseInt(args[++i]);
                    break;
                case "--timeout":
                    config.timeoutSec = Integer.parseInt(args[++i]);
                    break;
                case "--map":
                    config.map = args[++i];
                    break;
                case "--friendly-fire":
                    config.friendlyFire = Boolean.parseBoolean(args[++i]);
                    break;
                case "--log-level":
                    config.logLevel = args[++i];
                    break;
                case "--log-file":
                    config.logFile = args[++i];
                    break;
                case "--room-name":
                    config.roomName = args[++i];
                    break;
                case "--no-lobby":
                    config.enableLobby = false;
                    break;
                case "--help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    DLog.logWarn("ServerConfig", "未知参数: " + arg);
                    break;
            }
        }

        return config;
    }

    /** 打印命令行用法 */
    public static void printUsage() {
        System.out.println("用法: java -jar server.jar [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --port <端口>        服务器监听端口 (默认: 20001)");
        System.out.println("  --tickrate <频率>    服务器 tick 频率 Hz (默认: 60)");
        System.out.println("  --max-players <数量> 最大玩家数 (默认: 8)");
        System.out.println("  --timeout <秒>       客户端心跳超时 (默认: 10)");
        System.out.println("  --map <地图名>       初始地图 (默认: default)");
        System.out.println("  --friendly-fire <bool> 友军伤害 (默认: false)");
        System.out.println("  --log-level <级别>   日志级别: DEBUG/INFO/WARN/ERROR (默认: INFO)");
        System.out.println("  --log-file <路径>    日志输出文件 (默认: 仅控制台)");
        System.out.println("  --room-name <名称>   房间显示名称 (默认: Dedicated Server)");
        System.out.println("  --no-lobby           禁用云大厅注册 (仅局域网/直连)");
        System.out.println("  --help               显示此帮助信息");
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "port=" + port +
                ", tickRate=" + tickRate +
                ", maxPlayers=" + maxPlayers +
                ", timeoutSec=" + timeoutSec +
                ", map='" + map + '\'' +
                ", friendlyFire=" + friendlyFire +
                ", logLevel='" + logLevel + '\'' +
                ", enableLobby=" + enableLobby +
                ", roomName='" + roomName + '\'' +
                '}';
    }

    // ── 工具方法 ──

    private static int getInt(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
