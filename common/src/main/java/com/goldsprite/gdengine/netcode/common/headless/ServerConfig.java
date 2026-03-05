package com.goldsprite.gdengine.netcode.common.headless;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    /** 游戏构建版本号（发布到云大厅，供客户端版本匹配） */
    public String gameVersion = "";
    /**
     * NAT/FRP 公网地址（可选），格式: ip:port。
     * 非空时 publishServerRoom 将使用此地址替代自动探测的公网 IP。
     * 留空则走默认自动检测逻辑。
     */
    public String publicAddress = "";

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
    /**
     * 从默认工作目录下的 server.properties 加载配置（Server 模式）。
     */
    public static ServerConfig load(String[] args) {
        return loadFromFile(new File("server.properties"), args);
    }

    /**
     * Host 模式专用加载：从指定文件加载配置，无命令行参数。
     * <p>
     * Host 配置实例的公共字段可被 UI 覆写（如房间名、端口），
     * 修改后直接传递给游戏屏幕，为后续房间/玩家元数据持久化做准备。
     *
     * @param propsFile host.properties 文件（不存在时返回纯默认配置）
     * @return 可被 UI 继续修改的配置实例
     */
    public static ServerConfig loadForHost(File propsFile) {
        return loadFromFile(propsFile, new String[0]);
    }

    /**
     * 从指定文件加载配置（支持 UTF-8 编码）。
     * <p>
     * 加载优先级: 代码默认值 → properties 文件 → 命令行参数。
     *
     * @param propsFile properties 文件
     * @param args      命令行参数
     * @return 合并后的配置实例
     */
    public static ServerConfig loadFromFile(File propsFile, String[] args) {
        ServerConfig config = new ServerConfig();

        // ── 1. 尝试加载 properties 文件（UTF-8 编码） ──
        if (propsFile != null && propsFile.exists()) {
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(propsFile), StandardCharsets.UTF_8)) {
                Properties props = new Properties();
                props.load(reader);
                
                // 特殊处理端口: 留空则使用随机端口(0)
                String portVal = props.getProperty("server.port");
                if (portVal != null) {
                    if (portVal.trim().isEmpty()) {
                        config.port = 0;
                    } else {
                        try {
                            config.port = Integer.parseInt(portVal.trim());
                        } catch (NumberFormatException e) {
                            DLog.logWarn("ServerConfig", "端口格式错误: " + portVal + "，使用默认值 " + config.port);
                        }
                    }
                }

                config.maxPlayers  = getInt(props, "server.max-players", config.maxPlayers);
                config.timeoutSec  = getInt(props, "server.timeout-sec", config.timeoutSec);
                config.tickRate    = getInt(props, "server.tickrate", config.tickRate);
                config.map         = props.getProperty("server.map", config.map);
                config.friendlyFire = Boolean.parseBoolean(
                        props.getProperty("server.friendly-fire", String.valueOf(config.friendlyFire)));
                config.enableLobby = Boolean.parseBoolean(
                        props.getProperty("server.enable-lobby", String.valueOf(config.enableLobby)));
                config.roomName    = props.getProperty("server.room-name", config.roomName);
                config.gameVersion  = props.getProperty("server.game-version", config.gameVersion);
                config.publicAddress = props.getProperty("server.public-address", config.publicAddress).trim();
                // lobby.hostAddress 是 server.public-address 的别名 (host.properties 常用写法)
                // 仅当 server.public-address 未设置时才生效
                if (config.publicAddress.isEmpty()) {
                    String lobbyHostAddr = props.getProperty("lobby.hostAddress", "").trim();
                    if (!lobbyHostAddr.isEmpty()) {
                        config.publicAddress = lobbyHostAddr;
                    }
                }
                config.logLevel    = props.getProperty("log.level", config.logLevel);
                config.logFile     = normalizeLogFile(props.getProperty("log.file", config.logFile));
                DLog.log("ServerConfig", "已从 " + propsFile.getName() + " 加载配置");
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
                case "--public-address":
                    config.publicAddress = args[++i].trim();
                    break;
                case "--game-version":
                    config.gameVersion = args[++i];
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
        System.out.println("  --game-version <版本> 游戏构建版本号 (默认: 空)");
        System.out.println("  --public-address <地址> NAT/FRP 公网地址 ip:port (默认: 自动检测)");
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
                ", gameVersion='" + gameVersion + '\'' +
                ", publicAddress='" + publicAddress + '\'' +
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

    /**
     * 规范化 log.file 配置值。
     * <ul>
     *   <li>"true" → "server.log"（便捷写法）</li>
     *   <li>"false" / null / 空 → ""（仅控制台）</li>
     *   <li>其他值保持原样（当作文件路径）</li>
     * </ul>
     */
    static String normalizeLogFile(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "false".equalsIgnoreCase(trimmed)) return "";
        if ("true".equalsIgnoreCase(trimmed)) return "server.log";
        return trimmed;
    }

    /**
     * 解析 "ip:port" 格式的地址字符串。
     *
     * @param address 地址字符串（如 "frp.example.com:12345" 或 "192.168.1.1"）
     * @return 长度为 2 的数组: [host, port]。无端口时 port 为空字符串。
     */
    public static String[] parseHostPort(String address) {
        if (address == null || address.trim().isEmpty()) {
            return new String[]{"", ""};
        }
        String trimmed = address.trim();
        int colonIdx = trimmed.lastIndexOf(':');
        if (colonIdx > 0) {
            return new String[]{trimmed.substring(0, colonIdx), trimmed.substring(colonIdx + 1)};
        }
        return new String[]{trimmed, ""};
    }
}
