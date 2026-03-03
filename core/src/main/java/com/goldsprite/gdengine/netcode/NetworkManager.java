package com.goldsprite.gdengine.netcode;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.goldsprite.gdengine.log.DLog;

/**
 * 网络层管理器，负责统筹所有的 NetworkObject 生命周期（Spawn / Despawn），
 * 并在每一帧（Net Tick）中汇总脏数据，通过 Transport 接口将数据序列化下发到物理网络层。
 */
public class NetworkManager {
    
    // 网络对象注册表（使用统一分配的网络ID作为Key）
    private final Map<Integer, NetworkObject> networkObjects = new HashMap<>();
    
    // 预制体工厂注册表（prefabId -> factory）
    private final Map<Integer, NetworkPrefabFactory> prefabRegistry = new HashMap<>();
    
    // 自增网络ID分配器
    private int nextNetworkId = 1;
    
    // 传输层
    private Transport transport;

    // ── 可配置的 Tick Rate（网络同步频率）──
    /** 每秒网络同步次数（Hz），默认 60。可通过 setTickRate() 动态调整。 */
    private int tickRate = 60;
    /** 每次网络同步的时间间隔（秒），由 tickRate 自动计算 */
    private float tickInterval = 1f / 60f;
    /** Tick 累加器（积累 delta 直到达到 tickInterval 触发一次网络同步） */
    private float tickAccumulator = 0f;

    /** 设置网络同步频率（Hz）。常用值: 20(低带宽), 30(平衡), 60(高精度) */
    public void setTickRate(int hz) {
        this.tickRate = Math.max(1, Math.min(hz, 128));
        this.tickInterval = 1f / this.tickRate;
        DLog.logT("Netcode", "[NetworkManager] Tick rate 已设为 " + this.tickRate + " Hz (间隔 " + String.format("%.1f", tickInterval * 1000) + " ms)");
    }

    /** 获取当前网络同步频率（Hz） */
    public int getTickRate() { return tickRate; }

    /** 获取当前网络同步间隔（秒） */
    public float getTickInterval() { return tickInterval; }

    // 游戏层连接事件监听器
    private NetworkConnectionListener connectionListener;

    // 待处理的断开事件队列（IO 线程写入，主线程 tick() 中消费，线程安全）
    private final CopyOnWriteArrayList<Integer> pendingDisconnects = new CopyOnWriteArrayList<>();

    public void setTransport(Transport transport) {
        this.transport = transport;
        // 自动注册数据接收回调，无需手动 transport.setManager()
        transport.setReceiveCallback(this::onReceiveData);
        // 自动注册连接事件回调（包含连接和断开）
        transport.setConnectionListener(new NetworkConnectionListener() {
            @Override
            public void onClientConnected(int clientId) {
                // Client 端收到分配的 clientId 时，自动设置
                if (transport.isClient()) {
                    localClientId = clientId;
                    DLog.logT("Netcode", "[NetworkManager] Client 被分配 clientId=" + clientId);
                }
                // 转发给游戏层监听器
                if (connectionListener != null) {
                    connectionListener.onClientConnected(clientId);
                }
            }

            @Override
            public void onClientDisconnected(int clientId) {
                // IO 线程回调，不直接操作 networkObjects，改为入队列，由主线程 tick() 统一处理
                pendingDisconnects.add(clientId);
            }
        });
    }

    /** 设置游戏层连接事件监听器（用于动态 Spawn 实体等场景逻辑） */
    public void setConnectionListener(NetworkConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * 断开连接事件转发监听器（在 setTransport 中注册）。
     * 仅在 setTransport 中受 Transport 层的回调使用。
     */
    private NetworkConnectionListener disconnectionListener;

    /** 当前本机的 clientId（Client 端由 Server 握手分配，Server 端无意义） */
    private int localClientId = -1;

    public void setLocalClientId(int id) { this.localClientId = id; }
    public int getLocalClientId() { return localClientId; }

    public Transport getTransport() {
        return transport;
    }

    /**
     * 注册预制体工厂，Server 和 Client 两端都需要注册相同的 prefabId 对应的工厂。
     */
    public void registerPrefab(int prefabId, NetworkPrefabFactory factory) {
        prefabRegistry.put(prefabId, factory);
    }

    /**
     * 生成一个新的网络对象，并分配网络ID（旧接口，保留兼容）
     */
    public void spawn(NetworkObject obj) {
        if (transport == null || !transport.isServer()) {
            throw new IllegalStateException("只有服务器可以(或者在此模式下) Spawn 对象！");
        }
        int assignedId = nextNetworkId++;
        obj.setNetworkId(assignedId);
        networkObjects.put(assignedId, obj);
        // 为所有 Behaviour 绑定 Manager 引用
        for (NetworkBehaviour b : obj.getBehaviours()) {
            b.setManager(this);
        }
    }

    /**
     * 通过预制体ID创建并生成网络对象（无拥有者，Server 拥有）。
     */
    public NetworkObject spawnWithPrefab(int prefabId) {
        return spawnWithPrefab(prefabId, -1);
    }

    /**
     * 通过预制体ID创建并生成网络对象，并指定拥有者 clientId。
     * Server 端会创建本地实例，并即时广播 SpawnPacket(0x11) 给所有客户端，
     * 客户端收到后会通过相同 prefabId 的工厂自动派生本地副本。
     * @param prefabId       预制体ID
     * @param ownerClientId  拥有者 clientId（-1 表示 Server 拥有）
     */
    public NetworkObject spawnWithPrefab(int prefabId, int ownerClientId) {
        if (transport == null || !transport.isServer()) {
            throw new IllegalStateException("只有服务器可以 Spawn 对象！");
        }
        NetworkPrefabFactory factory = prefabRegistry.get(prefabId);
        if (factory == null) {
            throw new IllegalArgumentException("未注册的预制体ID: " + prefabId);
        }

        // Server 端本地创建
        NetworkObject obj = factory.create();
        int assignedId = nextNetworkId++;
        obj.setNetworkId(assignedId);
        obj.setPrefabId(prefabId);
        obj.setOwnerClientId(ownerClientId);
        networkObjects.put(assignedId, obj);
        // 为所有 Behaviour 绑定 Manager 引用
        for (NetworkBehaviour b : obj.getBehaviours()) {
            b.setManager(this);
        }

        // 广播 SpawnPacket 给所有客户端
        byte[] spawnPacket = buildSpawnPacket(assignedId, prefabId, ownerClientId);
        transport.broadcast(spawnPacket);

        return obj;
    }

    /**
     * 销毁一个已 Spawn 的网络对象。
     * Server 端移除本地注册表，并广播 DespawnPacket(0x12) 给所有客户端。
     */
    public void despawn(int networkId) {
        if (transport == null || !transport.isServer()) {
            throw new IllegalStateException("只有服务器可以 Despawn 对象！");
        }
        NetworkObject removed = networkObjects.remove(networkId);
        if (removed == null) {
            DLog.logErr("[NetworkManager] Despawn 失败，找不到实体: netId=" + networkId);
            return;
        }
        // 广播 DespawnPacket 给所有客户端
        NetBuffer buffer = new NetBuffer();
        buffer.writeInt(0x12); // DespawnPacket 魔法头
        buffer.writeInt(networkId);
        transport.broadcast(buffer.toByteArray());
        DLog.logT("Netcode", "[NetworkManager] Server Despawn 实体: netId=" + networkId);
    }

    /**
     * 移除指定 ownerClientId 拥有的所有网络实体（Server 端）。
     * 通常在客户端断开连接时调用。
     */
    public void despawnByOwner(int ownerClientId) {
        if (transport == null || !transport.isServer()) return;
        // 收集要移除的 netId（避免遍历中修改 map）
        java.util.List<Integer> toRemove = new java.util.ArrayList<>();
        for (Map.Entry<Integer, NetworkObject> entry : networkObjects.entrySet()) {
            if (entry.getValue().getOwnerClientId() == ownerClientId) {
                toRemove.add(entry.getKey());
            }
        }
        for (int netId : toRemove) {
            despawn(netId);
        }
        if (!toRemove.isEmpty()) {
            DLog.logT("Netcode", "[NetworkManager] 已移除 Client #" + ownerClientId + " 拥有的 " + toRemove.size() + " 个实体");
        }
    }

    /**
     * 构建 Spawn 封包：[0x11][networkId][prefabId][ownerClientId]
     */
    private byte[] buildSpawnPacket(int networkId, int prefabId, int ownerClientId) {
        NetBuffer buffer = new NetBuffer();
        buffer.writeInt(0x11); // SpawnPacket 魔法头
        buffer.writeInt(networkId);
        buffer.writeInt(prefabId);
        buffer.writeInt(ownerClientId);
        return buffer.toByteArray();
    }

    /**
     * 服务器专用心跳机制（无参版本，保持向后兼容）。
     * 每次调用等同于 tick(tickInterval)，即视为恰好一次 tick 间隔。
     * <p>
     * 推荐使用 {@link #tick(float)} 并传入帧 delta，由累加器自动控制发送频率。
     */
    public void tick() {
        tickInternal();
    }

    /**
     * 服务器专用心跳机制（推荐版本）。
     * 使用累加器模式，根据 tickRate 自动控制网络同步频率。
     * 每帧调用一次，传入帧 delta，当累计时间 >= tickInterval 时触发一次网络同步。
     * @param delta 当前帧的时间间隔（秒）
     */
    public void tick(float delta) {
        if (transport == null || !transport.isServer()) return;
        tickAccumulator += delta;
        // 累加器模式：可能一帧内触发多次 tick（低帧率补偿）
        while (tickAccumulator >= tickInterval) {
            tickAccumulator -= tickInterval;
            tickInternal();
        }
    }

    /**
     * 内部实际执行一次网络同步的逻辑。
     */
    private void tickInternal() {
        if (transport == null || !transport.isServer()) return;

        // === 处理待处理的断开事件（主线程安全） ===
        while (!pendingDisconnects.isEmpty()) {
            int clientId = pendingDisconnects.remove(0);
            despawnByOwner(clientId);
            if (connectionListener != null) {
                connectionListener.onClientDisconnected(clientId);
            }
        }

        for (Map.Entry<Integer, NetworkObject> entry : networkObjects.entrySet()) {
            NetworkObject obj = entry.getValue();
            int dirties = obj.countDirtyVariables();
            if (dirties > 0) {
                // 如果发现该实体有脏数据，将其打包序列化发送给客户端。
                byte[] payload = serializeObjectState(entry.getKey(), obj);
                transport.broadcast(payload);
                // 重置所有对象的脏检查位
                obj.clearAllDirtyVariables();
            }
        }
    }
    
    /**
     * 将带有脏变标志的对象转成字节
     */
    private byte[] serializeObjectState(int networkId, NetworkObject obj) {
        NetBuffer buffer = new NetBuffer();
        // 封包头：先写一个魔法数字标识这是一个【状态同步包】 (暂定0x10表示StatusSync)
        buffer.writeInt(0x10);
        buffer.writeInt(networkId);
        
        int dirtyCount = obj.countDirtyVariables();
        buffer.writeInt(dirtyCount); 
        
        // 遍历所有变量，如果被标记为脏则进行序列化
        java.util.List<NetworkVariable<?>> vars = obj.getNetworkVariables();
        for (int i = 0; i < vars.size(); i++) {
            NetworkVariable<?> var = vars.get(i);
            if (var.isDirty()) {
                // 写入当前变量的标识位 (Index)
                buffer.writeInt(i);
                // 将变量具体的二进制数值序列化到 buffer 中
                var.serialize(buffer);
            }
        }
        
        return buffer.toByteArray();
    }

    /**
     * Transport 收到字节流后回调此方法。
     * 是从字节码还原为内存逻辑对象的必经之路。
     * @param payload  原始字节数据
     * @param clientId 发送方 clientId（Server 端 >= 0，Client 端为 -1）
     */
    public void onReceiveData(byte[] payload, int clientId) {
        try {
            onReceiveDataInternal(payload, clientId);
        } catch (NetBuffer.NetBufferUnderflowException e) {
            DLog.logErr("[NetworkManager] 封包解析失败（数据损坏/截断），已安全丢弃: " + e.getMessage()
                + " | 来源 clientId=" + clientId + ", 封包长度=" + payload.length);
        } catch (Exception e) {
            DLog.logErr("[NetworkManager] 封包处理异常，已安全丢弃: " + e.getClass().getSimpleName()
                + " - " + e.getMessage() + " | 来源 clientId=" + clientId + ", 封包长度=" + payload.length);
        }
    }

    /** 内部封包分发（由 onReceiveData try-catch 保护） */
    private void onReceiveDataInternal(byte[] payload, int clientId) {
        NetBuffer inBuffer = new NetBuffer(payload);
        int packetType = inBuffer.readInt();
        
        // SpawnPacket: 客户端收到后自动通过预制体工厂派生本地实体
        if (packetType == 0x11) {
            int netId = inBuffer.readInt();
            int prefabId = inBuffer.readInt();
            int ownerClientId = inBuffer.readInt();
            
            NetworkPrefabFactory factory = prefabRegistry.get(prefabId);
            if (factory == null) {
                DLog.logErr("[NetworkManager] Client 收到 SpawnPacket 但未注册对应的预制体工厂: prefabId=" + prefabId);
                return;
            }
            
            // 通过工厂创建本地副本
            NetworkObject localObj = factory.create();
            localObj.setNetworkId(netId);
            localObj.setPrefabId(prefabId);
            localObj.setOwnerClientId(ownerClientId);
            // 判断是否为本地玩家实体
            if (ownerClientId >= 0 && ownerClientId == localClientId) {
                localObj.isLocalPlayer = true;
            }
            // 为所有 Behaviour 绑定 Manager 引用
            for (NetworkBehaviour b : localObj.getBehaviours()) {
                b.setManager(this);
            }
            networkObjects.put(netId, localObj);
            
            DLog.logT("Netcode", "[NetworkManager] Client 自动派生实体成功: netId=" + netId + ", prefabId=" + prefabId + ", owner=" + ownerClientId + (localObj.isLocalPlayer ? " [本地玩家]" : ""));
            return;
        }
        
        // DespawnPacket: 客户端收到后移除本地实体
        if (packetType == 0x12) {
            int netId = inBuffer.readInt();
            NetworkObject removed = networkObjects.remove(netId);
            if (removed != null) {
                DLog.logT("Netcode", "[NetworkManager] Client 移除实体: netId=" + netId);
            }
            return;
        }

        // RPC 包: ServerRpc(0x20) 或 ClientRpc(0x21)
        if (packetType == 0x20 || packetType == 0x21) {
            handleRpcPacket(inBuffer);
            return;
        }
        
        // 状态同步包
        if (packetType == 0x10) {
            int netId = inBuffer.readInt();
            int modifiedCount = inBuffer.readInt();

            // 防御性校验：modifiedCount 不能是负数或超过合理上限（单个实体的变量数不可能超过 256）
            if (modifiedCount < 0 || modifiedCount > 256) {
                DLog.logErr("[NetworkManager] 状态同步包 modifiedCount 异常: " + modifiedCount + " (netId=" + netId + ")，丢弃此包");
                return;
            }
            
            NetworkObject localObj = networkObjects.get(netId);
            if (localObj == null) {
                // 当为 Unreliable 同步先于 Spawn 到达时可能触发，属正常现象，不需要 logErr
                return;
            }
            
            java.util.List<NetworkVariable<?>> vars = localObj.getNetworkVariables();
            for (int i = 0; i < modifiedCount; i++) {
                int varIndex = inBuffer.readInt();
                if (varIndex >= 0 && varIndex < vars.size()) {
                    NetworkVariable<?> var = vars.get(varIndex);
                    // 执行反序列化，覆盖本地值
                    var.deserialize(inBuffer);
                } else {
                    DLog.logErr("[NetworkManager] 反序列化失败，越界的变量索引: " + varIndex + " (netId=" + netId + ", vars.size=" + vars.size() + ")");
                    // 由于协议强顺序性，一旦发现反序列化索引错乱，后面整个包的读取都将失效，必须直接阻断或者丢弃。
                    break; 
                }
            }
        }
    }
    
    /**
     * 构建并发送 RPC 封包。
     * 封包格式: [packetType(0x20/0x21)][netId][behaviourIndex][methodNameLength][methodNameBytes][argCount][arg1Type][arg1Value]...
     * @param packetType 0x20=ServerRpc, 0x21=ClientRpc
     */
    public void sendRpcPacket(int packetType, int netId, int behaviourIndex, String methodName, Object... args) {
        NetBuffer buffer = new NetBuffer();
        buffer.writeInt(packetType);
        buffer.writeInt(netId);
        buffer.writeInt(behaviourIndex);
        buffer.writeString(methodName);
        
        // 序列化参数
        buffer.writeInt(args.length);
        for (Object arg : args) {
            serializeRpcArg(buffer, arg);
        }
        
        byte[] payload = buffer.toByteArray();
        
        if (packetType == 0x20) {
            // ServerRpc: Client -> Server
            if (transport != null) {
                transport.sendToServer(payload);
            }
        } else if (packetType == 0x21) {
            // ClientRpc: Server -> Client
            if (transport != null) {
                transport.broadcast(payload);
            }
        }
    }

    /**
     * 将单个 RPC 参数序列化到 buffer 中。
     * 类型标记: 1=int, 2=float, 3=boolean, 4=String
     */
    private void serializeRpcArg(NetBuffer buffer, Object arg) {
        if (arg instanceof Integer) {
            buffer.writeInt(1);
            buffer.writeInt((Integer) arg);
        } else if (arg instanceof Float) {
            buffer.writeInt(2);
            buffer.writeFloat((Float) arg);
        } else if (arg instanceof Boolean) {
            buffer.writeInt(3);
            buffer.writeBoolean((Boolean) arg);
        } else if (arg instanceof String) {
            buffer.writeInt(4);
            buffer.writeString((String) arg);
        } else {
            throw new IllegalArgumentException("RPC 暂不支持此参数类型: " + (arg != null ? arg.getClass() : "null"));
        }
    }

    /**
     * 从 buffer 中反序列化单个 RPC 参数
     */
    private Object deserializeRpcArg(NetBuffer buffer) {
        int typeTag = buffer.readInt();
        switch (typeTag) {
            case 1: return buffer.readInt();
            case 2: return buffer.readFloat();
            case 3: return buffer.readBoolean();
            case 4: return buffer.readString();
            default: throw new IllegalArgumentException("未知的 RPC 参数类型标记: " + typeTag);
        }
    }

    /**
     * 处理接收到的 RPC 封包，反射调用目标方法
     */
    private void handleRpcPacket(NetBuffer inBuffer) {
        int netId = inBuffer.readInt();
        int behaviourIndex = inBuffer.readInt();
        String methodName = inBuffer.readString();
        int argCount = inBuffer.readInt();
        
        Object[] args = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            args[i] = deserializeRpcArg(inBuffer);
        }
        
        // 在本地找到对应的实体和行为组件
        NetworkObject localObj = networkObjects.get(netId);
        if (localObj == null) {
            DLog.logErr("[NetworkManager] RPC 目标实体不存在: netId=" + netId);
            return;
        }
        
        java.util.List<NetworkBehaviour> behaviours = localObj.getBehaviours();
        if (behaviourIndex < 0 || behaviourIndex >= behaviours.size()) {
            DLog.logErr("[NetworkManager] RPC 目标行为组件索引越界: " + behaviourIndex);
            return;
        }
        
        NetworkBehaviour target = behaviours.get(behaviourIndex);
        
        // 通过反射找到并调用目标方法
        try {
            // 构建参数类型数组用于精确匹配方法
            Class<?>[] paramTypes = new Class<?>[argCount];
            for (int i = 0; i < argCount; i++) {
                if (args[i] instanceof Integer) paramTypes[i] = int.class;
                else if (args[i] instanceof Float) paramTypes[i] = float.class;
                else if (args[i] instanceof Boolean) paramTypes[i] = boolean.class;
                else if (args[i] instanceof String) paramTypes[i] = String.class;
                else paramTypes[i] = args[i].getClass();
            }
            
            java.lang.reflect.Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (Exception e) {
            DLog.logErr("[NetworkManager] RPC 反射调用失败: " + methodName + " -> " + e.getMessage());
        }
    }

    /**
     * 向指定客户端补发所有已存在的 SpawnPacket。
     * 通常在新 Client 连入时调用，确保后来者能看到之前已 Spawn 的实体。
     */
    public void sendExistingSpawnsToClient(int clientId) {
        if (transport == null || !transport.isServer()) return;
        for (Map.Entry<Integer, NetworkObject> entry : networkObjects.entrySet()) {
            NetworkObject obj = entry.getValue();
            byte[] spawnPacket = buildSpawnPacket((int) obj.getNetworkId(), obj.getPrefabId(), obj.getOwnerClientId());
            transport.sendToClient(clientId, spawnPacket);
        }
        DLog.logT("Netcode", "[NetworkManager] 向 Client #" + clientId + " 补发 " + networkObjects.size() + " 个已有实体的 SpawnPacket");
    }

    /**
     * 向指定客户端发送所有已有实体的全量状态快照。
     * 确保新加入的 Client 能看到正确的位置、颜色、HP 等。
     */
    public void sendFullStateToClient(int clientId) {
        if (transport == null || !transport.isServer()) return;
        for (Map.Entry<Integer, NetworkObject> entry : networkObjects.entrySet()) {
            NetworkObject obj = entry.getValue();
            byte[] payload = serializeFullObjectState(entry.getKey(), obj);
            transport.sendToClient(clientId, payload);
        }
        DLog.logT("Netcode", "[NetworkManager] 向 Client #" + clientId + " 发送 " + networkObjects.size() + " 个实体的全量状态快照");
    }

    /**
     * 将对象的所有变量全量序列化（不仅是脏变量），用于新客户端状态补发。
     */
    private byte[] serializeFullObjectState(int networkId, NetworkObject obj) {
        NetBuffer buffer = new NetBuffer();
        buffer.writeInt(0x10); // StatusSync
        buffer.writeInt(networkId);

        java.util.List<NetworkVariable<?>> vars = obj.getNetworkVariables();
        int totalCount = vars.size();
        buffer.writeInt(totalCount);

        for (int i = 0; i < totalCount; i++) {
            buffer.writeInt(i);
            vars.get(i).serialize(buffer);
        }

        return buffer.toByteArray();
    }

    public NetworkObject getNetworkObject(int id) {
        return networkObjects.get(id);
    }

    /** 返回当前注册的网络对象数量 */
    public int getNetworkObjectCount() {
        return networkObjects.size();
    }

    /** 返回所有已注册网络对象的只读集合 */
    public java.util.Collection<NetworkObject> getAllNetworkObjects() {
        return java.util.Collections.unmodifiableCollection(networkObjects.values());
    }
}
