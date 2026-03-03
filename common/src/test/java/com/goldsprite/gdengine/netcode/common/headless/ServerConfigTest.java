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
}
