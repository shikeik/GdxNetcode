# GdxNetcode

**通用网络联机框架** — 基于 GdxCore 的底层网络基础设施库。

## 宗旨

> **GdxNetcode 是一个纯底层网络框架，只提供通用的网络基础 API，严禁包含任何业务逻辑或上层封装。**

### 严格边界定义

**允许包含（底层网络基础设施）：**
- 传输层（Transport、UdpSocketTransport、ReliableUdpTransport）
- 网络管理（NetworkManager、NetworkObject、NetworkBehaviour）
- RPC 机制（ServerRpc、ClientRpc、RpcScanner）
- 网络变量同步（NetworkVariable）
- 预制体工厂（NetworkPrefabFactory）
- 连接管理（NetworkConnectionListener）
- 数据序列化（NetBuffer）
- 云服务基础（Supabase Presence、PublicIPResolver）
- 无头服务器骨架（HeadlessGameServer、ServerConsole、ServerConfig）

**严禁包含（业务逻辑 / 上层封装）：**
- 聊天系统（属于业务层，应在游戏项目中实现）
- 游戏玩法逻辑（战斗、移动等）
- 特定游戏的网络对象或预制体
- 任何与具体游戏项目耦合的代码

### 修改原则

1. **通用性** — 新增 API 必须对所有需要联机的 libGDX 项目通用
2. **零业务耦合** — ServerConsole 只提供命令注册机制，不预置业务命令
3. **向后兼容** — 公开 API 的破坏性变更必须走大版本号

## 依赖关系

```
GdxNetcode  ──依赖──→  GdxCore:core (mavenLocal / JitPack)
```

## 使用方式

### mavenLocal（开发环境）

```gradle
dependencies {
    api 'com.github.shikeik.GdxCore:core:1.0.0'
    api 'com.github.shikeik.GdxNetcode:netcode:0.9.4'
}
```

### JitPack（远端）

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    api 'com.github.shikeik.GdxCore:core:1.0.0'
    api 'com.github.shikeik.GdxNetcode:netcode:0.9.4'
}
```

## 版本

| 版本 | 说明 |
|------|------|
| 0.9.4 | 从 GdxCore 独立为单独项目；ServerConsole 移除 chat 业务耦合 |

## License

Apache License 2.0
