package com.goldsprite.gdengine.netcode;

import org.junit.Test;

import com.goldsprite.CLogAssert;

public class NetworkVariableTest {

    @Test
    public void testDirtyFlag() {
        // 初始值 100
        NetworkVariable<Integer> hp = new NetworkVariable<>(100);
        
        // 1. 初始化时，应该被标记为 dirty，以便在第一次 Update 时同步给所有人
        CLogAssert.assertTrue("初始状态应为Dirty，保证能向新加入的玩家同步状态", hp.isDirty());
        
        // 2. 模拟服务器清理 dirty 状态 (在 LateUpdate 里广播后)
        hp.clearDirty();
        CLogAssert.assertFalse("网络包发送完毕清理后，Dirty标记应当被重置", hp.isDirty());
        
        // 3. 设置相同的值，不应该触发 dirty
        hp.setValue(100);
        CLogAssert.assertFalse("设置完全相同的值，应当拦截冗余网络同步发包操作", hp.isDirty());
        
        // 4. 设置不同的值，触发 dirty
        hp.setValue(90);
        CLogAssert.assertTrue("当属性发生实质变化时，应当成功触发Dirty状态以供网络收集", hp.isDirty());
        CLogAssert.assertEquals("内部数据值应当被成功更新为90", Integer.valueOf(90), hp.getValue());
    }
}
