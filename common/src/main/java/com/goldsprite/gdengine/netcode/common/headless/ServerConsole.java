package com.goldsprite.gdengine.netcode.common.headless;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import com.goldsprite.gdengine.log.DLog;

/**
 * 服务器命令行控制台（守护线程）。
 * <p>
 * 从 stdin 循环读取管理员指令并分发到已注册的处理器。
 * 内置命令: status, players, kick, stop, help, loglevel。
 * 子类或外部可通过 {@link #registerCommand(String, String, Consumer)} 注册自定义命令。
 * <p>
 * 所有输入（无论是否带 {@code /} 前缀）统一走命令表路由。
 * 业务层（如聊天、say）应在下游项目通过 registerCommand 注册，不内置于框架。
 */
public class ServerConsole extends Thread {

    /** 命令处理器: 命令名 → 处理函数（参数为去除命令名后的剩余字符串） */
    private final Map<String, CommandEntry> commands = new HashMap<>();

    /** 关联的服务器实例（用于内置命令的状态查询） */
    private final HeadlessGameServer server;

    /** 控制台运行标志 */
    private volatile boolean running = true;

    /** 服务器启动时间（毫秒） */
    private final long startTimeMs;

    /** JLine 终端 */
    private Terminal terminal;

    /** JLine 行读取器（提供补全、输入行固定等能力） */
    private LineReader reader;

    public ServerConsole(HeadlessGameServer server) {
        super("ServerConsole");
        setDaemon(true); // 守护线程，JVM 退出时自动终止
        this.server = server;
        this.startTimeMs = System.currentTimeMillis();

        // 注册内置命令（需在构建 LineReader 之前完成，以便补全器能拿到命令列表）
        registerBuiltinCommands();

        // 构建 JLine Terminal + LineReader
        try {
            terminal = TerminalBuilder.builder()
                .system(true)
                .build();
            reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(buildCommandCompleter())
                .build();
            // 候选列表自动展示，不需要按两次 Tab
            reader.setOpt(LineReader.Option.AUTO_LIST);
            // Tab 补全进入交互式菜单，方向键可选择
            reader.setOpt(LineReader.Option.AUTO_MENU);
            // 输入时自动显示行内候选建议（类似 fish shell，安全不阻塞）
            reader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);
        } catch (IOException e) {
            DLog.logWarn("Console", "JLine 初始化失败，回退到基础模式: " + e.getMessage());
        }

        // 优化日志输出：移除默认输出，使用 JLine 感知的输出
        DLog.removeLogOutput(DLog.StandardOutput.class);
        DLog.registerLogOutput(new ConsoleLogOutput(reader));
    }

    /**
     * 注册自定义命令。
     *
     * @param name        命令名称（全小写）
     * @param description 命令说明（显示在 help 中）
     * @param handler     处理函数，参数为命令名之后的剩余文本
     */
    public void registerCommand(String name, String description, Consumer<String> handler) {
        commands.put(name.toLowerCase(), new CommandEntry(description, handler));
    }

    @Override
    public void run() {
        while (running) {
            try {
                String line;
                if (reader != null) {
                    line = reader.readLine("> ");
                } else {
                    // 回退：无 JLine 时用 System.in 直接读
                    java.util.Scanner scanner = new java.util.Scanner(System.in);
                    if (!scanner.hasNextLine()) break;
                    line = scanner.nextLine();
                }
                if (line == null) break;
                line = line.trim();

                if (line.isEmpty()) continue;

                // ── / 前缀自动剥除（兼容 MC 风格输入） ──
                if (line.startsWith("/")) {
                    line = line.substring(1).trim();
                    if (line.isEmpty()) continue;
                }

                // ── 统一走命令表路由 ──
                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toLowerCase();
                String argsStr = parts.length > 1 ? parts[1] : "";

                CommandEntry entry = commands.get(cmd);
                if (entry != null) {
                    entry.handler.accept(argsStr);
                } else {
                    System.out.println("未知命令: " + cmd + "。输入 'help' 查看可用命令。");
                }

            } catch (UserInterruptException e) {
                // Ctrl+C — 优雅退出
                System.out.println("  正在优雅关闭服务器...");
                server.requestShutdown();
                break;
            } catch (EndOfFileException e) {
                // stdin 被关闭（非交互环境）
                break;
            } catch (Exception e) {
                if (running) {
                    DLog.logWarn("Console", "命令处理异常: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 构建带描述的命令补全器。
     * Tab 时显示候选列表，每个候选项附带命令说明。
     */
    private Completer buildCommandCompleter() {
        return (reader, line, candidates) -> {
            String word = line.word().toLowerCase();
            for (Map.Entry<String, CommandEntry> e : commands.entrySet()) {
                String name = e.getKey();
                if (name.startsWith(word)) {
                    // 将命令名与描述合并为显示文本，确保候选列表始终单列竖排
                    String displayText = String.format("%-12s (%s)", name, e.getValue().description);
                    candidates.add(new Candidate(
                        name,           // value（实际插入值）
                        displayText,    // display（带描述的显示文本）
                        null, null, null, null, true
                    ));
                }
            }
        };
    }

    /** 停止控制台线程 */
    public void shutdown() {
        running = false;
        this.interrupt();
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException ignored) {}
        }
    }

    // ══════════════ 内置命令 ══════════════

    private void registerBuiltinCommands() {

        registerCommand("help", "显示所有可用命令", args -> {
            System.out.println("═══════ 可用命令 ═══════");
            for (Map.Entry<String, CommandEntry> e : commands.entrySet()) {
                System.out.printf("  %-12s %s%n", e.getKey(), e.getValue().description);
            }
            System.out.println("  ── 支持 / 前缀 ──");
            System.out.println("  /命令名       等同于不带 / 的命令");
            System.out.println("════════════════════════");
        });

        registerCommand("status", "显示服务器状态（玩家数、内存、运行时长）", args -> {
            long uptimeMs = System.currentTimeMillis() - startTimeMs;
            long sec = (uptimeMs / 1000) % 60;
            long min = (uptimeMs / 1000 / 60) % 60;
            long hr = uptimeMs / 1000 / 3600;
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMB = rt.maxMemory() / (1024 * 1024);

            int playerCount = server.getOnlinePlayerCount();
            int maxPlayers = server.getConfig().maxPlayers;

            System.out.printf("  在线玩家: %d/%d%n", playerCount, maxPlayers);
            System.out.printf("  已运行: %02d:%02d:%02d%n", hr, min, sec);
            System.out.printf("  内存: %dMB / %dMB%n", usedMB, maxMB);
            System.out.printf("  TickRate: %d Hz%n", server.getConfig().tickRate);
        });

        registerCommand("players", "列出所有在线玩家", args -> {
            java.util.Map<Integer, String> playerNames = server.getPlayerNames();
            if (playerNames.isEmpty()) {
                System.out.println("  当前无玩家在线");
                return;
            }
            System.out.println("  ── 在线玩家 ──");
            for (java.util.Map.Entry<Integer, String> entry : playerNames.entrySet()) {
                System.out.printf("  #%-4d  %s%n", entry.getKey(), entry.getValue());
            }
        });

        registerCommand("kick", "踢出指定玩家 (用法: kick <clientId>)", args -> {
            if (args.isEmpty()) {
                System.out.println("  用法: kick <clientId>");
                return;
            }
            try {
                int clientId = Integer.parseInt(args.trim());
                server.kickPlayer(clientId);
                System.out.println("  已踢出玩家 #" + clientId);
            } catch (NumberFormatException e) {
                System.out.println("  无效的 clientId: " + args);
            }
        });

        registerCommand("stop", "优雅关闭服务器", args -> {
            System.out.println("  正在优雅关闭服务器...");
            server.requestShutdown();
        });

        registerCommand("loglevel", "查看/修改日志等级 (用法: loglevel [等级] [标签])", args -> {
            String[] parts = args.trim().split("\\s+");
            if (args.trim().isEmpty()) {
                // 无参数: 显示当前等级
                System.out.println("  全局日志等级: " + DLog.getGlobalLogLevel().name());
                System.out.println("  可用等级: DEBUG / INFO / WARN / ERROR");
                System.out.println("  用法: loglevel <等级>          设置全局等级");
                System.out.println("        loglevel <等级> <标签>   设置指定标签等级");
                System.out.println("        loglevel reset <标签>   重置标签等级为全局");
                return;
            }

            String levelStr = parts[0].toUpperCase();

            // reset 子命令: 重置指定标签的等级覆盖
            if ("RESET".equals(levelStr)) {
                if (parts.length < 2) {
                    DLog.clearTagLogLevels();
                    System.out.println("  已清除所有标签等级覆盖");
                } else {
                    DLog.setTagLogLevel(parts[1], null);
                    System.out.println("  已重置标签 [" + parts[1] + "] 的等级覆盖");
                }
                return;
            }

            DLog.Level level = DLog.parseLevel(levelStr);
            if (level == null) {
                System.out.println("  无效的日志等级: " + levelStr);
                System.out.println("  可用等级: DEBUG / INFO / WARN / ERROR");
                return;
            }

            if (parts.length >= 2) {
                // 按标签设置
                String tag = parts[1];
                DLog.setTagLogLevel(tag, level);
                System.out.println("  标签 [" + tag + "] 日志等级已设置为: " + level.name());
            } else {
                // 全局设置
                DLog.setGlobalLogLevel(level);
                System.out.println("  全局日志等级已设置为: " + level.name());
            }
        });

        registerCommand("role", "显示当前服务器角色", args -> {
            System.out.println("  当前角色: Server (Dedicated)");
        });
    }

    // ══════════════ 内部类 ══════════════

    /**
     * JLine 感知的日志输出端。
     * 通过 {@code reader.printAbove()} 将日志插入到输入行上方，
     * 保证用户正在编辑的命令行始终固定在最底部不被冲走。
     */
    private static class ConsoleLogOutput extends DLog.StandardOutput {
        private final LineReader reader;

        ConsoleLogOutput(LineReader reader) {
            this.reader = reader;
        }

        @Override
        public void onLog(DLog.Level level, String tag, String msg) {
            String formatted = formatLog(level, tag, msg);
            if (reader != null) {
                reader.printAbove(formatted);
            } else {
                System.out.println(formatted);
            }
        }
    }

    /** 命令注册表条目 */
    private static class CommandEntry {
        final String description;
        final Consumer<String> handler;

        CommandEntry(String description, Consumer<String> handler) {
            this.description = description;
            this.handler = handler;
        }
    }
}
