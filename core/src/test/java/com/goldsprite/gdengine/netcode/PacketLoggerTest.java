package com.goldsprite.gdengine.netcode;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.goldsprite.GdxTestRunner;
import com.goldsprite.gdengine.test.CLogAssert;

/**
 * TDD 驱动：验证 PacketLogger 拦截器的核心功能。
 * A3: 轻量 PacketLogger — 默认关闭、零开销、运行时可切换、统计摘要。
 */
@RunWith(GdxTestRunner.class)
public class PacketLoggerTest {

    /**
     * 测试1: 默认关闭时统计不累计
     */
    @Test
    public void testDisabledByDefault() {
        System.out.println("======= [TDD] PacketLogger 默认关闭 =======");

        PacketLogger logger = new PacketLogger();

        CLogAssert.assertFalse("默认应关闭", logger.isEnabled());

        // 关闭状态下调用 log 方法，统计不应累计
        logger.logTx("Reliable", 42, 38, 0x11, 64);
        logger.logRx("Reliable", 39, 42, 0x10, 128);

        PacketLogger.Stats stats = logger.getStats();
        CLogAssert.assertEquals("关闭时 txCount 应为 0", 0, stats.txCount);
        CLogAssert.assertEquals("关闭时 rxCount 应为 0", 0, stats.rxCount);

        System.out.println("======= [TDD] PacketLogger 默认关闭 通过 =======");
    }

    /**
     * 测试2: 开启后统计正确累计
     */
    @Test
    public void testEnabledStatsAccumulate() {
        System.out.println("======= [TDD] PacketLogger 统计累计 =======");

        PacketLogger logger = new PacketLogger();
        logger.setEnabled(true);

        logger.logTx("Reliable", 1, 0, 0x11, 64);
        logger.logTx("Reliable", 2, 1, 0x20, 48);
        logger.logTx("Unreliable", -1, -1, 0x10, 128);
        logger.logRx("Reliable", 5, 2, 0x11, 72);
        logger.logRx("ACK", -1, -1, -1, 3);
        logger.logRetransmit(1, 300);

        PacketLogger.Stats stats = logger.getStats();
        CLogAssert.assertEquals("txCount 应为 3", 3, stats.txCount);
        CLogAssert.assertEquals("rxCount 应为 2", 2, stats.rxCount);
        CLogAssert.assertEquals("retransmitCount 应为 1", 1, stats.retransmitCount);
        CLogAssert.assertEquals("txBytes 应为 240", 240L, stats.txBytes);
        CLogAssert.assertEquals("rxBytes 应为 75", 75L, stats.rxBytes);

        System.out.println("======= [TDD] PacketLogger 统计累计 通过 =======");
    }

    /**
     * 测试3: 运行时开关切换
     */
    @Test
    public void testRuntimeToggle() {
        System.out.println("======= [TDD] PacketLogger 运行时切换 =======");

        PacketLogger logger = new PacketLogger();

        // 关闭 → 不累计
        logger.logTx("Reliable", 1, 0, 0x11, 50);
        CLogAssert.assertEquals("关闭时不累计", 0, logger.getStats().txCount);

        // 开启 → 累计
        logger.setEnabled(true);
        logger.logTx("Reliable", 2, 1, 0x11, 50);
        CLogAssert.assertEquals("开启后累计", 1, logger.getStats().txCount);

        // 再关闭 → 不累计（但旧统计保留）
        logger.setEnabled(false);
        logger.logTx("Reliable", 3, 2, 0x11, 50);
        CLogAssert.assertEquals("关闭后不再累计", 1, logger.getStats().txCount);

        System.out.println("======= [TDD] PacketLogger 运行时切换 通过 =======");
    }

    /**
     * 测试4: 统计重置
     */
    @Test
    public void testStatsReset() {
        System.out.println("======= [TDD] PacketLogger 统计重置 =======");

        PacketLogger logger = new PacketLogger();
        logger.setEnabled(true);

        logger.logTx("Reliable", 1, 0, 0x11, 100);
        logger.logRx("Reliable", 2, 1, 0x11, 200);
        CLogAssert.assertEquals("重置前 txCount", 1, logger.getStats().txCount);

        logger.resetStats();

        PacketLogger.Stats stats = logger.getStats();
        CLogAssert.assertEquals("重置后 txCount 应为 0", 0, stats.txCount);
        CLogAssert.assertEquals("重置后 rxCount 应为 0", 0, stats.rxCount);
        CLogAssert.assertEquals("重置后 txBytes 应为 0", 0L, stats.txBytes);

        System.out.println("======= [TDD] PacketLogger 统计重置 通过 =======");
    }

    /**
     * 测试5: 日志格式化输出正确
     */
    @Test
    public void testLogFormat() {
        System.out.println("======= [TDD] PacketLogger 日志格式 =======");

        PacketLogger logger = new PacketLogger();
        logger.setEnabled(true);

        // 测试 TX 格式
        String txLog = logger.formatTx("Reliable", 42, 38, 0x11, 64);
        CLogAssert.assertTrue("TX 日志应包含 TX",
            txLog.contains("TX"));
        CLogAssert.assertTrue("TX 日志应包含 Reliable",
            txLog.contains("Reliable"));
        CLogAssert.assertTrue("TX 日志应包含 seq=42",
            txLog.contains("seq=42"));
        CLogAssert.assertTrue("TX 日志应包含 64B",
            txLog.contains("64B"));

        // 测试 RX 格式
        String rxLog = logger.formatRx("Reliable", 39, 42, 0x10, 128);
        CLogAssert.assertTrue("RX 日志应包含 RX",
            rxLog.contains("RX"));
        CLogAssert.assertTrue("RX 日志应包含 128B",
            rxLog.contains("128B"));

        // 测试摘要格式
        String summary = logger.formatSummary();
        CLogAssert.assertTrue("摘要应包含 TX",
            summary.contains("TX:"));
        CLogAssert.assertTrue("摘要应包含 RX",
            summary.contains("RX:"));

        System.out.println("======= [TDD] PacketLogger 日志格式 通过 =======");
    }
}
