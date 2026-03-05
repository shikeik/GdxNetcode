# A2: 重连以 playerToken 识别 — CONNECT_REQUEST/ACCEPT 握手协议

## 📅 版本信息
- **阶段**: Phase A — 网络地基加固
- **子任务**: A2
- **提交**: `71d1a46`
- **日期**: 2026-03-05
- **涉及文件**: NetworkManager.java, NetworkManagerTest.java

## 🎯 目标
解决 UDP 重连后获得新 clientId、导致旧实体变鬼影的问题。通过 playerToken 识别机制让 Server 在重连时将新 clientId 绑定到原有玩家实体上。

## 🛠️ 变更详情

### 新增封包协议
| 类型 | 值 | 方向 | 载荷 |
|------|-----|------|------|
| `CONNECT_REQUEST` | 0x30 | Client → Server | `[playerToken: UTF string]` |
| `CONNECT_ACCEPT` | 0x31 | Server → Client | `[clientId: 4B][isReconnect: 1B]` |

### 连接流程改造
```
旧流程: UDP握手完成 → onClientConnected(newId) → 游戏层生成实体
新流程: UDP握手完成 → Client自动发CONNECT_REQUEST(token)
        → Server查表:
          - 新token → 注册映射 → CONNECT_ACCEPT(isReconnect=false) → onClientConnected
          - 已有token → 更新映射 → 重绑实体ownerClientId → CONNECT_ACCEPT(isReconnect=true) → onClientConnected
```

### 核心实现
1. **NetworkManager 新增字段**:
   - `localPlayerToken` — 客户端的玩家标识
   - `tokenToClientId` / `clientIdToToken` — Server 端双向映射表
   - `lastConnectionReconnect` — 上一次连接是否为重连

2. **`setTransport()` 改造**:
   - Server 端: 拦截 `onClientConnected`，不立即通知游戏层（等待 CONNECT_REQUEST）
   - Client 端: 连接完成后自动发送 `sendConnectRequest()`

3. **新增方法**:
   - `handleConnectRequest(token, clientId)` — Server 核心重连逻辑
   - `sendConnectAccept(clientId, isReconnect)` — Server 回复
   - `getClientIdByToken()` / `getTokenByClientId()` — 映射查询
   - `setPlayerToken()` / `getPlayerToken()` / `isLastConnectionReconnect()`

4. **兼容模式**: 若 `localPlayerToken` 为 null，走旧流程（直接 `onClientConnected`）

## 🧪 TDD 测试
| 测试名 | 验证内容 |
|--------|----------|
| `testA2_firstConnectRequest` | 首次连接: 正确注册 token→clientId 映射 |
| `testA2_reconnectSameToken` | 重连: 相同 token 更新 clientId，重绑实体 ownerClientId |
| `testA2_differentTokenIsNewPlayer` | 不同 token: 视为全新玩家 |
| `testA2_clientReceivesConnectAccept` | Client 正确解析 CONNECT_ACCEPT 并更新 localClientId |

- **总计**: 全部 51 个测试通过，零回归

## 📊 影响分析
- **兼容性**: 完全向后兼容（null token 走旧流程）
- **安全**: playerToken 目前明文传输，未来可加 HMAC
- **上层集成**: SandTank 需在 `NetcodeTankOnlineScreen` 调用 `setPlayerToken()` 并在 `TankServerHandler` 处理 isReconnect
