# Phase B: 服务器配置与稳定性修复

## 📅 版本信息
- **阶段**: Phase B — 服务器配置与稳定性
- **子任务**: B1 + B3 + B4-3（B2 已修复跳过，B4 其余项非 Bug 或已修复）
- **提交**: GdxNetcode `ad17831` / SandTank `ac0785c`
- **日期**: 2026-03-05
- **涉及文件**: ServerConfig.java, ServerConfigTest.java, HeadlessGameServer.java, SupabaseLobbyScreen.java, host.properties(新)

## 🎯 目标
修复 ServerConfig 编码与日志 Bug，建立 Host/Server 统一配置架构（以 ServerConfig 实例为数据源），修复 HeadlessServer 关闭时 dispose 重入问题。

---

## B1: ServerConfig UTF-8 编码 + log.file 规范化

### 问题
1. `Properties.load(InputStream)` 使用 ISO-8859-1 → 中文配置乱码
2. `log.file=true` 被当作文件名创建名为 "true" 的文件

### 修复
| 变更 | 说明 |
|------|------|
| `loadFromFile()` | 使用 `InputStreamReader(fis, UTF_8)` 替代 `FileInputStream` |
| `normalizeLogFile()` | `"true"→"server.log"`, `"false"/null/""→""`, 其他保持原值 |
| `load(args)` → `loadFromFile()` | 提取可测试方法，接收 `File` 参数 |

### TDD 测试 (4 项)
| 测试名 | 验证内容 |
|--------|----------|
| `testB1_utf8PropertiesLoading` | 写入 UTF-8 中文 → 正确读取 map/roomName |
| `testB1_logFileTrueNormalization` | `log.file=true` → `"server.log"` |
| `testB1_logFileExplicitPath` | `log.file=myserver.log` → 保持原值 |
| `testB1_logFileEmpty` | `log.file=` → `""` (仅控制台) |

---

## B3: Host 模式配置支持 — ServerConfig 统一数据源

### 设计思路
Host（房主）和 Server（专用服务器）都以 `ServerConfig` 实例作为配置数据源：
- **Server 模式**: `ServerConfig.load(args)` → 从 `server.properties` + CLI 加载，只读
- **Host 模式**: `ServerConfig.loadForHost(file)` → 从 `host.properties` 加载，UI 可覆写

```
host.properties → ServerConfig 实例 → UI 展示/编辑 → 回写 config → 建房/进入游戏
```

此架构为后续**玩家元数据 / 房间元数据持久化**提供统一的配置数据源。

### GdxNetcode 变更
| 新增方法 | 说明 |
|----------|------|
| `ServerConfig.loadForHost(File)` | Host 专用加载（无 CLI 参数），返回可被 UI 修改的实例 |
| `ServerConfig.parseHostPort(String)` | 解析 `"ip:port"` → `[host, port]` 数组 |
| `lobby.hostAddress` 别名 | properties 中 `lobby.hostAddress=` 映射到 `publicAddress`（FRP 常用写法） |

### SandTank 变更
| 变更 | 说明 |
|------|------|
| `SupabaseLobbyScreen.hostConfig` 字段 | Host 配置实例（screen 级生命周期） |
| `showCreateRoomPanel()` | 打开建房面板时从 `{StorageRoot}/host.properties` 加载 config，UI 字段初始化自 config |
| `createAndPublishRoom()` | UI 值回写到 config → config 驱动建房逻辑；FRP 场景用 `parseHostPort()` 解析 |
| `host.properties` 模板 | 新增 `assets/SandTank/host.properties` 模板文件（含 FRP 配置注释） |

### FRP 场景数据流
```
host.properties           UI 面板            建房逻辑
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ lobby.host   │───▶│publicAddress │    │ 跳过公网检测 │
│ Address=     │    │ (不可见)     │───▶│ 直接使用 FRP │
│ frp:12345    │    │              │    │ 地址发布房间 │
└──────────────┘    │ port=19200   │    └──────────────┘
                    │ roomName=... │
                    └──────────────┘
```

### TDD 测试 (9 项)
| 测试名 | 验证内容 |
|--------|----------|
| `testB3_lobbyHostAddressAlias` | `lobby.hostAddress` → `publicAddress` 映射 |
| `testB3_serverPublicAddressTakesPrecedence` | `server.public-address` 优先于别名 |
| `testB3_hostPropertiesWithoutAddress` | 无地址时 publicAddress 为空 |
| `testB3_loadForHost_noFile` | 文件不存在返回纯默认配置 |
| `testB3_loadForHost_withFile` | 正常加载 port/roomName/publicAddress |
| `testB3_uiOverridesConfig` | 文件加载 → UI 覆写字段 → config 反映最新值 |
| `testB3_parsePublicAddress` | 解析 `ip:port` 格式 |
| `testB3_parsePublicAddress_ipOnly` | 只有 IP 无端口 |
| `testB3_parsePublicAddress_empty` | 空字符串处理 |

---

## B4-3: HeadlessGameServer dispose() 重入守卫

### 问题
`performShutdown()` 调用 `dispose()` + `Gdx.app.exit()`，而框架 `exit()` 又会触发 `dispose()` → 双重执行引发异常或"正在关闭"打印两次。

### 修复
```java
private final AtomicBoolean disposed = new AtomicBoolean(false);

@Override
public void dispose() {
    if (!disposed.compareAndSet(false, true)) return;
    // ... 原有清理逻辑
}
```

---

## 📊 测试总计
| 模块 | 测试数 | 结果 |
|------|--------|------|
| common (ServerConfig + Chat + PublicIP) | 19+ | ✅ 全部通过 |
| core | 全量 | ✅ 全部通过 |
| SandTank:examples 编译 | — | ✅ 编译通过 |

## 📊 影响分析
- **兼容性**: 新增方法/字段，无已有 API 变更
- **持久化准备**: `ServerConfig` 实例贯穿 Host 会话生命周期，后续可扩展保存/恢复房间元数据
- **FRP 支持**: 用户只需编辑 `host.properties` 一个文件即可配置内网穿透
- **稳定性**: dispose 重入守卫消除关闭时异常
