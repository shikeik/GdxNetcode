# A1: 可靠 UDP — ACK 持续确认制（指数退避 + 总超时断连）

## 📅 版本信息
- **阶段**: Phase A — 网络地基加固
- **子任务**: A1
- **提交**: `6d7df28`
- **日期**: 2026-03-05
- **涉及文件**: ReliableUdpTransport.java, ReliableUdpTransportTest.java

## 🎯 目标
将原有「固定最大重传次数 5 次」的可靠 UDP 机制，改为「指数退避 + 总超时断连」模式，避免弱网下过早放弃、丢包后无法恢复。

## 🛠️ 变更详情

### 常量替换
| 旧常量 | 新常量 | 值 |
|--------|--------|-----|
| `RETRANSMIT_TIMEOUT_MS` | `RETRANSMIT_BASE_MS` | 200ms |
| `MAX_RETRANSMIT_COUNT(5)` | _(移除)_ | — |
| `FIRST_PKT_BASE_TIMEOUT_MS` | `RETRANSMIT_BACKOFF` | 1.5 |
| `FIRST_PKT_MAX_RETRANSMIT(10)` | `RETRANSMIT_MAX_INTERVAL_MS` | 2000ms |
| `FIRST_PKT_MAX_TIMEOUT_MS` | `RELIABLE_TIMEOUT_MS` | 15000ms |

### 核心改造
1. **PendingEntry** 新增 `firstSendTime` 字段（final，仅在首次发送时设置，重传不修改）
2. **`isMaxRetriesExceeded()`** → 替换为 `isTotalTimeoutExceeded(seqNum, now, timeoutMs)`
3. **`tickReliable()`** 全面重写：
   - 每次重传间隔 = `min(BASE × BACKOFF^n, MAX_INTERVAL)`
   - 总超时 = `now - firstSendTime >= RELIABLE_TIMEOUT_MS` → 触发 `onReliableTimeout()`
4. **新增** 静态方法 `calcBackoffInterval(retransmitCount, baseMs, backoff, maxIntervalMs)`
5. **新增** `ReliableTimeoutCallback` 接口 + `setReliableTimeoutCallback()` 注册方法

### 退避曲线示例
```
重传次数:  0     1     2     3     4     5     6     ...
间隔(ms): 200   300   450   675  1012  1519  2000(封顶)
```

## 🧪 TDD 测试
| 测试名 | 验证内容 |
|--------|----------|
| `testPendingEntryFirstSendTime` | firstSendTime 不随重传变化 |
| `testTotalTimeoutExceeded` | 总超时判定逻辑正确 |
| `testExponentialBackoffIntervals` | 退避公式 + 封顶正确 |
| `testNoMaxRetryLimit_ContinuesUntilTimeout` | 无次数上限，仅受总超时控制 |

- **旧测试 7** (`testMaxRetransmissions`) → 替换为 `testTotalTimeoutExceeded`
- **总计**: 13 个测试全部通过，零回归

## 📊 影响分析
- **兼容性**: 不影响上层 API，`tickReliable()` 签名不变
- **性能**: 退避机制减少了弱网下的无效重传风暴
- **可观测性**: `ReliableTimeoutCallback` 为上层提供了精确的超时通知入口
