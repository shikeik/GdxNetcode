package com.goldsprite.gdengine.netcode.common.headless;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.GdxTestRunner;

/**
 * {@link ServerConfig} 命令行参数解析单元测试。
 * <p>
 * 需要 GdxTestRunner（因为 ServerConfig.load() 内调用 DLog）。
 * 覆盖默认值、单参数、多参数组合。
 */
@RunWith(GdxTestRunner.class)
public class ServerConfigTest {

    @Test
    public void testDefaults() {
        ServerConfig config = ServerConfig.load(new String[]{});
        assertEquals(20001, config.port);
        assertEquals(8, config.maxPlayers);
        assertEquals(10, config.timeoutSec);
        assertEquals(60, config.tickRate);
        assertEquals("default", config.map);
        assertFalse(config.friendlyFire);
        assertEquals("INFO", config.logLevel);
        assertEquals("", config.logFile);
    }

    @Test
    public void testPortArg() {
        ServerConfig config = ServerConfig.load(new String[]{"--port", "12345"});
        assertEquals(12345, config.port);
        // 其他保持默认
        assertEquals("default", config.map);
    }

    @Test
    public void testMapArg() {
        ServerConfig config = ServerConfig.load(new String[]{"--map", "荒漠战场"});
        assertEquals("荒漠战场", config.map);
    }

    @Test
    public void testMultipleArgs() {
        ServerConfig config = ServerConfig.load(new String[]{
            "--port", "9999",
            "--tickrate", "30",
            "--max-players", "16",
            "--timeout", "20",
            "--map", "ice_world",
            "--friendly-fire", "true",
            "--log-level", "DEBUG",
            "--log-file", "server.log"
        });

        assertEquals(9999, config.port);
        assertEquals(30, config.tickRate);
        assertEquals(16, config.maxPlayers);
        assertEquals(20, config.timeoutSec);
        assertEquals("ice_world", config.map);
        assertTrue(config.friendlyFire);
        assertEquals("DEBUG", config.logLevel);
        assertEquals("server.log", config.logFile);
    }

    @Test
    public void testUnknownArgDoesNotCrash() {
        // 未知参数应记录警告但不抛异常
        ServerConfig config = ServerConfig.load(new String[]{"--unknown-flag"});
        // 所有字段保持默认
        assertEquals(20001, config.port);
    }

    @Test
    public void testToString() {
        ServerConfig config = ServerConfig.load(new String[]{});
        String str = config.toString();
        assertTrue("toString 应包含 port", str.contains("port=20001"));
        assertTrue("toString 应包含 tickRate", str.contains("tickRate=60"));
        assertTrue("toString 应包含 map", str.contains("map='default'"));
    }

    // ── B1 TDD: UTF-8 编码 + log.file 规范化 ──

    @Test
    public void testB1_utf8PropertiesLoading() throws Exception {
        // 准备含中文的 properties 文件（UTF-8 编码）
        java.io.File tmpFile = java.io.File.createTempFile("server-test-utf8", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("server.map=荒漠战场\n");
            w.write("server.room-name=中文房间名\n");
            w.write("server.port=12345\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("UTF-8 中文 map 应正确读取", "荒漠战场", config.map);
        assertEquals("UTF-8 中文 roomName 应正确读取", "中文房间名", config.roomName);
        assertEquals(12345, config.port);
    }

    @Test
    public void testB1_logFileTrueNormalization() throws Exception {
        // log.file=true 应被规范化为 "server.log"
        java.io.File tmpFile = java.io.File.createTempFile("server-test-logfile", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("log.file=true\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("log.file=true 应规范化为 server.log", "server.log", config.logFile);
    }

    @Test
    public void testB1_logFileExplicitPath() throws Exception {
        // log.file=myserver.log 应保持原值
        java.io.File tmpFile = java.io.File.createTempFile("server-test-logpath", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("log.file=myserver.log\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("显式路径应保持原值", "myserver.log", config.logFile);
    }

    @Test
    public void testB1_logFileEmpty() throws Exception {
        // log.file= 留空应保持空（仅控制台）
        java.io.File tmpFile = java.io.File.createTempFile("server-test-logempty", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("log.file=\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("log.file 留空应保持空", "", config.logFile);
    }

    // ── B3 TDD: lobby.hostAddress 别名 + Host 配置 ──

    @Test
    public void testB3_lobbyHostAddressAlias() throws Exception {
        // lobby.hostAddress 是 server.public-address 的别名（host.properties 常用写法）
        java.io.File tmpFile = java.io.File.createTempFile("host-test-alias", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("lobby.hostAddress=frp.example.com:12345\n");
            w.write("server.port=19200\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("lobby.hostAddress 应映射到 publicAddress",
                "frp.example.com:12345", config.publicAddress);
        assertEquals(19200, config.port);
    }

    @Test
    public void testB3_serverPublicAddressTakesPrecedence() throws Exception {
        // 同时设置 server.public-address 和 lobby.hostAddress 时，
        // server.public-address 优先（更明确的配置覆盖别名）
        java.io.File tmpFile = java.io.File.createTempFile("host-test-precedence", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("server.public-address=official.server.com:9999\n");
            w.write("lobby.hostAddress=frp.example.com:12345\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("server.public-address 应优先于 lobby.hostAddress",
                "official.server.com:9999", config.publicAddress);
    }

    @Test
    public void testB3_hostPropertiesWithoutAddress() throws Exception {
        // host.properties 不设置地址时，publicAddress 保持空（走自动检测）
        java.io.File tmpFile = java.io.File.createTempFile("host-test-noaddr", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("server.port=19300\n");
            w.write("server.room-name=我的房间\n");
        }

        ServerConfig config = ServerConfig.loadFromFile(tmpFile, new String[]{});
        assertEquals("未设置地址时应为空", "", config.publicAddress);
        assertEquals(19300, config.port);
        assertEquals("我的房间", config.roomName);
    }

    @Test
    public void testB3_loadForHost_noFile() {
        // host.properties 不存在时，返回纯默认配置
        ServerConfig config = ServerConfig.loadForHost(new java.io.File("nonexistent_host.properties"));
        assertEquals(20001, config.port);
        assertEquals("", config.publicAddress);
        assertEquals("Dedicated Server", config.roomName);
    }

    @Test
    public void testB3_loadForHost_withFile() throws Exception {
        // host.properties 存在时，加载其中的配置（Host 模式无 CLI 参数）
        java.io.File tmpFile = java.io.File.createTempFile("host-test-load", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("server.port=19200\n");
            w.write("server.room-name=测试房间\n");
            w.write("lobby.hostAddress=frp.test.com:8888\n");
        }

        ServerConfig config = ServerConfig.loadForHost(tmpFile);
        assertEquals(19200, config.port);
        assertEquals("测试房间", config.roomName);
        assertEquals("frp.test.com:8888", config.publicAddress);
    }

    @Test
    public void testB3_uiOverridesConfig() throws Exception {
        // 模拟 Host 模式: 先从文件加载，再由 UI 覆写字段
        java.io.File tmpFile = java.io.File.createTempFile("host-test-ui", ".properties");
        tmpFile.deleteOnExit();
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("server.port=19200\n");
            w.write("server.room-name=文件房间名\n");
        }

        ServerConfig config = ServerConfig.loadForHost(tmpFile);
        assertEquals("文件房间名", config.roomName);
        assertEquals(19200, config.port);

        // UI 覆写（模拟用户在大厅界面修改）
        config.roomName = "用户修改的房间名";
        config.port = 25000;

        // 覆写后 config 实例反映最新值，可传递给游戏屏幕
        assertEquals("用户修改的房间名", config.roomName);
        assertEquals(25000, config.port);
        // 文件中的 publicAddress 仍保持原值（UI 不覆写此项）
        assertEquals("", config.publicAddress);
    }

    @Test
    public void testB3_parsePublicAddress() {
        // 解析 publicAddress 中的 ip:port
        ServerConfig config = new ServerConfig();
        config.publicAddress = "frp.example.com:12345";

        String[] parsed = ServerConfig.parseHostPort(config.publicAddress);
        assertEquals("frp.example.com", parsed[0]);
        assertEquals("12345", parsed[1]);
    }

    @Test
    public void testB3_parsePublicAddress_ipOnly() {
        // 只有 IP 无端口时，端口部分为空
        String[] parsed = ServerConfig.parseHostPort("192.168.1.1");
        assertEquals("192.168.1.1", parsed[0]);
        assertEquals("", parsed[1]);
    }

    @Test
    public void testB3_parsePublicAddress_empty() {
        // 空字符串
        String[] parsed = ServerConfig.parseHostPort("");
        assertEquals("", parsed[0]);
        assertEquals("", parsed[1]);
    }
}
