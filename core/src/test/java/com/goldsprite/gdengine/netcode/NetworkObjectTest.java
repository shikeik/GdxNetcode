package com.goldsprite.gdengine.netcode;

import org.junit.Test;

import com.goldsprite.CLogAssert;

public class NetworkObjectTest {

    @Test
    public void testVariableRegistrationAndDirtyCollection() {
        // 创建一个宿主网络体，设身份ID为 1001
        NetworkObject netObj = new NetworkObject(1001L);

        CLogAssert.assertEquals("分配全局唯一指纹 ID 正确", 1001L, netObj.getNetworkId());

        // 模拟挂载在它身上的业务组件所包含的同步变量
        NetworkVariable<Integer> hp = new NetworkVariable<>(100);
        NetworkVariable<String> playerName = new NetworkVariable<>("Hero");
        NetworkVariable<Integer> mp = new NetworkVariable<>(50); // 一上来默认就带有初次的dirty
        
        // 我们将其手工改为净白状态用来测试变更效果
        mp.clearDirty();

        netObj.registerVariable(hp);
        netObj.registerVariable(playerName);
        netObj.registerVariable(mp);

        // 初始化 hp 和 playerName 都是 dirty
        CLogAssert.assertEquals("初始生成时拥有2个待首发同步的脏标记变量 (hp 和 playerName)", 2, netObj.countDirtyVariables());

        // 模拟底层发完包了，一键清理
        netObj.clearAllDirtyVariables();
        CLogAssert.assertEquals("发包完毕清场后，待执行脏变量应彻底归零", 0, netObj.countDirtyVariables());

        // 模拟第一帧业务逻辑受击操作
        hp.setValue(80);
        playerName.setValue("Hero"); // 设置相同名字，不应触发 dirty

        // 检验脏收集器能否正确捕捉异动
        CLogAssert.assertEquals("当仅有血量发生骤减时，只能捕获收集到 1 个异动变量", 1, netObj.countDirtyVariables());

        // 模拟第二帧又产生扣篮行为
        mp.setValue(30); 
        CLogAssert.assertEquals("当蓝量进一步遭受磨损时，应能合并收束到 2 个异动变量待包", 2, netObj.countDirtyVariables());
    }
}
