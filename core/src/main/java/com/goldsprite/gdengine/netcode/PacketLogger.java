package com.goldsprite.gdengine.netcode;

import com.goldsprite.gdengine.log.DLog;

/**
 * 轻量级网络数据包日志拦截器。
 * <p>
 * 默认关闭，零开销。可在运行时通过 {@link #setEnabled(boolean)} 切换。
 * 开启后记录每个收发数据包的方向、通道类型、序列号、确认号、包类型及字节数，
 * 并周期性输出统计摘要（发送/接收/重传计数）。
 * </p>
 * <p>
 * 日志格式: [PacketLog] TX Reliable seq=42 ack=38 type=0x11 64B
 * </p>
 */
public class PacketLogger {

    private static final String TAG = "PacketLog";

    /** 统计摘要输出间隔（毫秒），默认 10 秒 */
    public static final long SUMMARY_INTERVAL_MS = 10_000;

    /** 统计数据快照 */
    public static class Stats {
        public int txCount;
        public int rxCount;
        public int retransmitCount;
        public long txBytes;
        public long rxBytes;

        /** 重置所有计数器 */
        public void reset() {
            txCount = 0;
            rxCount = 0;
            retransmitCount = 0;
            txBytes = 0;
            rxBytes = 0;
        }

        /** 复制当前快照 */
        public Stats copy() {
            Stats s = new Stats();
            s.txCount = this.txCount;
            s.rxCount = this.rxCount;
            s.retransmitCount = this.retransmitCount;
            s.txBytes = this.txBytes;
            s.rxBytes = this.rxBytes;
            return s;
        }
    }

    // ── 状态 ──

    private boolean enabled = false;
    private final Stats stats = new Stats();
    private long lastSummaryTime = 0;

    // ── 开关 ──

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ── 统计 ──

    /** 获取当前统计快照（副本） */
    public Stats getStats() {
        return stats.copy();
    }

    /** 重置统计计数器 */
    public void resetStats() {
        stats.reset();
    }

    // ── 格式化（纯函数，方便测试） ──

    /**
     * 格式化 TX 日志条目。
     *
     * @param channelType 通道类型名称（Reliable/Unreliable/ACK/Ping/Pong）
     * @param seqNum      序列号（-1 表示不适用）
     * @param ackNum      确认号（-1 表示不适用）
     * @param packetType  包类型标识（首 4 字节 int）
     * @param byteCount   数据包总字节数
     * @return 格式化后的日志字符串
     */
    public String formatTx(String channelType, int seqNum, int ackNum, int packetType, int byteCount) {
        return formatEntry("TX", channelType, seqNum, ackNum, packetType, byteCount);
    }

    /**
     * 格式化 RX 日志条目。
     */
    public String formatRx(String channelType, int seqNum, int ackNum, int packetType, int byteCount) {
        return formatEntry("RX", channelType, seqNum, ackNum, packetType, byteCount);
    }

    /**
     * 格式化统计摘要。
     */
    public String formatSummary() {
        return String.format("[统计] TX: %d包/%dB  RX: %d包/%dB  重传: %d",
            stats.txCount, stats.txBytes,
            stats.rxCount, stats.rxBytes,
            stats.retransmitCount);
    }

    // ── 日志方法（需要 enabled 才生效） ──

    /**
     * 记录发送数据包。
     */
    public void logTx(String channelType, int seqNum, int ackNum, int packetType, int byteCount) {
        if (!enabled) return;
        stats.txCount++;
        stats.txBytes += byteCount;
        DLog.logT(TAG, formatTx(channelType, seqNum, ackNum, packetType, byteCount));
    }

    /**
     * 记录接收数据包。
     */
    public void logRx(String channelType, int seqNum, int ackNum, int packetType, int byteCount) {
        if (!enabled) return;
        stats.rxCount++;
        stats.rxBytes += byteCount;
        DLog.logT(TAG, formatRx(channelType, seqNum, ackNum, packetType, byteCount));
    }

    /**
     * 记录重传。
     *
     * @param seqNum     重传的序列号
     * @param delayMs    本次退避延迟（毫秒）
     */
    public void logRetransmit(int seqNum, long delayMs) {
        if (!enabled) return;
        stats.retransmitCount++;
        DLog.logT(TAG, String.format("RETRANSMIT seq=%d delay=%dms", seqNum, delayMs));
    }

    /**
     * 周期性检查是否需要输出统计摘要。在 tick 中调用。
     *
     * @param nowMs 当前时间（毫秒）
     */
    public void tickSummary(long nowMs) {
        if (!enabled) return;
        if (lastSummaryTime == 0) {
            lastSummaryTime = nowMs;
            return;
        }
        if (nowMs - lastSummaryTime >= SUMMARY_INTERVAL_MS) {
            DLog.logT(TAG, formatSummary());
            lastSummaryTime = nowMs;
        }
    }

    // ── 内部 ──

    private String formatEntry(String direction, String channelType, int seqNum, int ackNum,
                               int packetType, int byteCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(direction).append(' ').append(channelType);
        if (seqNum >= 0) sb.append(" seq=").append(seqNum);
        if (ackNum >= 0) sb.append(" ack=").append(ackNum);
        if (packetType >= 0) sb.append(String.format(" type=0x%02X", packetType));
        sb.append(' ').append(byteCount).append('B');
        return sb.toString();
    }
}
