package com.goldsprite.gdengine.netcode;

import java.util.Objects;

import com.badlogic.gdx.graphics.Color;

/**
 * 自动状态同步变量 (类似 Unity NGO 的 NetworkVariable)。
 * 负责追踪数据的变化 (Dirty Flag)，只在发生改变时被动打成网络包。
 * <p>
 * 支持 <b>平滑调和模式</b>（Server Reconciliation）：
 * 当客户端使用 {@link #setLocal(Object)} 进行本地预测时，
 * 服务端状态到达后不再硬覆盖，而是通过插值平滑过渡到权威值，
 * 从根本上消除"被拉回/扯着走"的手感问题。
 * @param <T> 内部持有的数据类型
 */
public class NetworkVariable<T> {
    private T value;
    private boolean isDirty = true; // 默认初始化时必须为 dirty，这样才能进行初始同步

    // ── 平滑调和（Server Reconciliation）相关 ──
    /** 是否启用平滑调和模式（仅对 Float 类型变量生效） */
    private boolean smoothReconciliation = false;
    /** 服务端权威值（调和模式下，deserialize 写入此处而非直接覆盖 value） */
    private T serverAuthoritativeValue;
    /** 调和插值速度（每秒向权威值靠近的比例，0~1 之间；越大越快收敛，越小越平滑） */
    private float reconcileSpeed = 10f;
    /** 硬拉回阈值（当本地值与服务端差值超过此值时直接 snap，防止明显偏离） */
    private float snapThreshold = 50f;
    /** 调和容差（当差值小于此值时直接吸附，避免无限小幅抖动） */
    private float reconcileTolerance = 0.5f;
    /** 标记: 当前是否有未消化的服务端权威值（避免在无新数据时也做插值） */
    private boolean hasPendingAuthority = false;

    // ── 客户端预测模式 ──
    /**
     * 客户端预测标记。
     * 为 true 时，{@link #deserialize} 仅记录服务端值，不覆盖本地预测值（{@link #value}）。
     * 只有当本地值与服务端值偏差超过 {@link #clientPredictSnapDist} 时才硬 snap
     * （撞墙修正 / 复活传送等）。正常移动预测漂移由 {@link #reconcileTick} 每帧微量修正。
     */
    private boolean clientPredicted = false;
    /** 客户端预测模式下的硬拉回阈值（偏差超过此值 → 直接 snap） */
    private float clientPredictSnapDist = 60f;
    /** 客户端预测模式下的漂移修正速率（每秒收敛比例） */
    private float clientPredictCorrectionRate = 3f;

    public NetworkVariable(T initialValue) {
        this.value = initialValue;
        this.serverAuthoritativeValue = initialValue;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T newValue) {
        // 如果值没有实质变化，不要触发网络同步
        if (Objects.equals(this.value, newValue)) {
            return;
        }
        this.value = newValue;
        this.isDirty = true;
    }

    /**
     * 仅设置本地值，不触发脏标记（不会被网络同步广播）。
     * 典型用途: 客户端预测——本地立即更新，等待 Server 权威值通过调和机制平滑修正。
     */
    public void setLocal(T newValue) {
        this.value = newValue;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public void clearDirty() {
        this.isDirty = false;
    }

    // ── 平滑调和配置 API ──

    /**
     * 启用平滑调和模式（仅对 Float 类型变量有效，如坐标 x/y）。
     * 启用后，服务端状态到达时不再硬覆盖本地值，而是通过插值平滑过渡。
     * @param speed       调和速度（每秒收敛比例，推荐 8~15，越大越快但越生硬）
     * @param snapDist    硬拉回阈值（差值超过此值则直接 snap，推荐 30~100）
     * @param tolerance   容差（差值小于此值直接吸附，推荐 0.1~1.0）
     * @return this（支持链式调用）
     */
    public NetworkVariable<T> enableSmooth(float speed, float snapDist, float tolerance) {
        this.smoothReconciliation = true;
        this.reconcileSpeed = speed;
        this.snapThreshold = snapDist;
        this.reconcileTolerance = tolerance;
        return this;
    }

    /** 使用默认参数启用平滑调和 */
    public NetworkVariable<T> enableSmooth() {
        return enableSmooth(10f, 50f, 0.5f);
    }

    /** 禁用平滑调和（恢复硬覆盖行为） */
    public NetworkVariable<T> disableSmooth() {
        this.smoothReconciliation = false;
        return this;
    }

    // ── 客户端预测模式 API ──

    /**
     * 启用客户端预测模式（仅对 Float 类型变量有效，如坐标 x/y）。
     * <p>
     * 启用后，{@link #deserialize} 不再覆盖本地预测值，
     * 仅在偏差超过 snapDist 时硬 snap（墙体推回 / 复活传送）。
     * 正常移动漂移由 {@link #reconcileTick} 每帧微量修正。
     *
     * @param snapDist       硬拉回阈值（像素，推荐 25~40）
     * @param correctionRate 漂移修正速率（每秒收敛比例，推荐 2~5）
     */
    public NetworkVariable<T> enableClientPrediction(float snapDist, float correctionRate) {
        this.clientPredicted = true;
        this.clientPredictSnapDist = snapDist;
        this.clientPredictCorrectionRate = correctionRate;
        return this;
    }

    /** 使用默认参数启用客户端预测模式 (snapDist=30, correctionRate=3) */
    public NetworkVariable<T> enableClientPrediction() {
        return enableClientPrediction(30f, 3f);
    }

    /** 禁用客户端预测模式（恢复标准 deserialize 行为） */
    public NetworkVariable<T> disableClientPrediction() {
        this.clientPredicted = false;
        return this;
    }

    /** 是否启用了客户端预测模式 */
    public boolean isClientPredicted() { return clientPredicted; }

    /** 是否启用了平滑调和 */
    public boolean isSmoothEnabled() { return smoothReconciliation; }

    /** 获取服务端权威值（调和模式下可用于调试显示） */
    public T getServerValue() { return serverAuthoritativeValue; }

    /**
     * 每帧调用一次，驱动平滑调和插值 或 客户端预测漂移修正。
     * @param delta 帧间隔（秒）
     */
    public void reconcileTick(float delta) {
        if (!hasPendingAuthority) return;
        if (!(value instanceof Float)) return;

        float current = (Float) value;
        float target = (Float) serverAuthoritativeValue;
        float diff = Math.abs(current - target);

        // ── 客户端预测模式：微量漂移修正 ──
        if (clientPredicted) {
            if (diff <= 1.0f) {
                // 已足够接近，停止修正
                hasPendingAuthority = false;
                return;
            }
            // 每帧向服务端值微量靠拢，玩家几乎无感知
            float alpha = Math.min(1f, clientPredictCorrectionRate * delta);
            float corrected = current + (target - current) * alpha;
            @SuppressWarnings("unchecked")
            T result = (T) Float.valueOf(corrected);
            this.value = result;
            return;
        }

        // ── 平滑调和模式 ──
        if (!smoothReconciliation) return;

        if (diff <= reconcileTolerance) {
            this.value = serverAuthoritativeValue;
            hasPendingAuthority = false;
            return;
        }

        if (diff > snapThreshold) {
            this.value = serverAuthoritativeValue;
            hasPendingAuthority = false;
            return;
        }

        float alpha2 = 1f - (float) Math.exp(-reconcileSpeed * delta);
        float lerped = current + (target - current) * alpha2;
        @SuppressWarnings("unchecked")
        T result = (T) Float.valueOf(lerped);
        this.value = result;
    }

    /**
     * 将本变量包装到 ByteBuffer 中发送
     */
    public void serialize(NetBuffer buffer) {
        if (value instanceof Float) {
            buffer.writeFloat((Float) value);
        } else if (value instanceof Integer) {
            buffer.writeInt((Integer) value);
        } else if (value instanceof Boolean) {
            buffer.writeBoolean((Boolean) value);
        } else if (value instanceof String) {
            buffer.writeString((String) value);
        } else if (value instanceof Color) {
            buffer.writeInt(Color.rgba8888((Color) value));
        } else {
            throw new IllegalArgumentException("NetworkVariable 暂不支持此类型序列化: " + (value != null ? value.getClass() : "null"));
        }
    }

    /**
     * 从 ByteBuffer 按当前类型指引解析数据并覆盖。
     * <p>
     * 若启用了平滑调和模式（仅 Float），新值写入 {@code serverAuthoritativeValue}，
     * 由 {@link #reconcileTick(float)} 渐进地将 value 拉向权威值。
     * 否则直接覆盖 value（旧行为）。
     */
    @SuppressWarnings("unchecked")
    public void deserialize(NetBuffer buffer) {
        Object newVal = null;
        if (value instanceof Float) {
            newVal = buffer.readFloat();
        } else if (value instanceof Integer) {
            newVal = buffer.readInt();
        } else if (value instanceof Boolean) {
            newVal = buffer.readBoolean();
        } else if (value instanceof String) {
            newVal = buffer.readString();
        } else if (value instanceof Color) {
            newVal = new Color(buffer.readInt());
        } else {
            throw new IllegalArgumentException("NetworkVariable 暂不支持此类型反序列化: " + (value != null ? value.getClass() : "null"));
        }
        
        // 客户端预测模式：不覆盖 value，仅记录服务端值，大偏差时硬 snap
        if (clientPredicted && value instanceof Float) {
            this.serverAuthoritativeValue = (T) newVal;
            this.hasPendingAuthority = true;
            float current = (Float) value;
            float server = (Float) newVal;
            if (Math.abs(current - server) > clientPredictSnapDist) {
                // 大偏差（墙体推回 / 复活传送）→ 硬 snap
                this.value = (T) newVal;
                this.hasPendingAuthority = false;
            }
            // 小偏差：信任客户端预测，由 reconcileTick 微量修正
            return;
        }

        // 平滑调和模式：服务端值写入权威缓冲区，由 reconcileTick 驱动插值
        if (smoothReconciliation && value instanceof Float) {
            this.serverAuthoritativeValue = (T) newVal;
            this.hasPendingAuthority = true;
            return;
        }

        // 非调和模式：直接覆盖（旧行为，不激活脏标记，防止反射再广播回去）
        this.value = (T) newVal;
    }
}
