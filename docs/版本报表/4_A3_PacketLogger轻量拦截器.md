# A3: PacketLogger 轻量拦截器 — 收发日志 + 统计摘要

## 📅 版本信息
- **阶段**: Phase A — 网络地基加固
- **子任务**: A3
- **提交**: `5786930`
- **日期**: 2026-03-05
- **涉及文件**: PacketLogger.java(新), PacketLoggerTest.java(新), ReliableUdpTransport.java

## 🎯 目标
在 ReliableUdpTransport 的收发路径上嵌入轻量日志拦截器，默认关闭零开销，运行时可切换，用于网络调试和性能分析。

## 🛠️ 变更详情

### 新增 PacketLogger.java
| 方法 | 功能 |
|------|------|
| `setEnabled(boolean)` | 运行时开关（默认 false） |
| `logTx(channel, seq, ack, type, bytes)` | 记录发送包 |
| `logRx(channel, seq, ack, type, bytes)` | 记录接收包 |
| `logRetransmit(seq, delayMs)` | 记录重传事件 |
| `tickSummary(nowMs)` | 每 10s 输出统计摘要 |
| `getStats()` → `Stats` | 获取统计快照 (txCount/rxCount/retransmitCount/txBytes/rxBytes) |
| `resetStats()` | 重置所有计数器 |
| `formatTx()` / `formatRx()` / `formatSummary()` | 纯函数格式化（方便测试） |

### 日志格式
```
[PacketLog] TX Reliable seq=42 ack=38 type=0x11 64B
[PacketLog] RX Unreliable 128B
[PacketLog] RETRANSMIT seq=5 delay=300ms
[PacketLog] [统计] TX: 120包/8640B  RX: 115包/7820B  重传: 3
```

### ReliableUdpTransport 集成点
| 位置 | 拦截内容 |
|------|----------|
| `wrapPayload()` | TX — Reliable / Unreliable 发送 |
| `onRawReceiveInternal()` UNRELIABLE case | RX — 非可靠接收 |
| `onRawReceiveInternal()` RELIABLE case | RX — 可靠接收 (含 seq/ack) |
| `onRawReceiveInternal()` ACK case | RX — ACK 确认 |
| `tickReliable()` 重传循环 | 重传事件 + delay |
| `tickReliable()` 开头 | 周期摘要 tickSummary |

### 零开销设计
- `enabled` 默认 false
- 每个 log 方法首行 `if (!enabled) return;` — 关闭时仅一次布尔判断
- 无内存分配、无字符串拼接

## 🧪 TDD 测试
| 测试名 | 验证内容 |
|--------|----------|
| `testDisabledByDefault` | 默认关闭，统计不累计 |
| `testEnabledStatsAccumulate` | 开启后 tx/rx/retransmit/bytes 正确累计 |
| `testRuntimeToggle` | 运行时开→关→开切换，统计仅在开启期间累计 |
| `testStatsReset` | resetStats() 清零所有计数器 |
| `testLogFormat` | formatTx/formatRx/formatSummary 输出格式正确 |

- **总计**: 全量测试通过，零回归

## 📊 影响分析
- **兼容性**: 新增字段/方法，无已有 API 变更
- **性能**: 关闭状态零开销（单次 boolean 检查）
- **可观测性**: 通过 `getPacketLogger().setEnabled(true)` 即可开启网络诊断
- **扩展**: 未来可接入文件日志 / 远程遥测
