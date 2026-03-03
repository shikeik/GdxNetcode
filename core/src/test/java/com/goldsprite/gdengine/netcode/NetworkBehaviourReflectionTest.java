package com.goldsprite.gdengine.netcode;

import org.junit.Test;

import com.goldsprite.CLogAssert;

public class NetworkBehaviourReflectionTest {

    // 充当我们用于测试的业务逻辑类，如坦克实体
    public static class TankLogic extends NetworkBehaviour {
        public NetworkVariable<Integer> hp = new NetworkVariable<>(100);
        public NetworkVariable<Float> speed = new NetworkVariable<>(5.5f);
        
        // 非网络变量，不应被管理
        public String localSkinId = "GoldTank";
        
        // 我们甚至模拟一个带有私有访问权限的变量也能被反射抓取
        private NetworkVariable<Boolean> isShielded = new NetworkVariable<>(false);

        public NetworkVariable<Boolean> getIsShielded() {
            return isShielded;
        }

        @ServerRpc
        public void requestMove(float x, float y) {
            // ...
        }
    }

    @Test
    public void testAutoRegisterNetworkVariables() {
        // 创建容器
        NetworkObject netObj = new NetworkObject(999L);
        
        // 创建业务组件
        TankLogic logic = new TankLogic();
        
        // == 核心步骤：直接使用 addComponent 挂载业务组件 ==
        netObj.addComponent(logic);
        
        // 初始因为没有被打包清理过，所有的变量都会默认是 dirty，应该反射扫出 3 个 (hp, speed, 内部的isShielded)
        CLogAssert.assertEquals("自动反射装配应当成功抓取到组件内声明的 3 个 NetworkVariable（包含私有的）", 3, netObj.countDirtyVariables());
        
        // 执行一波清场模拟发送
        netObj.clearAllDirtyVariables();
        CLogAssert.assertEquals("模拟网络层执行清理", 0, netObj.countDirtyVariables());
        
        // 此时业务层只需要对它的普通对象做赋值
        logic.hp.setValue(50);
        
        // NetworkObject就能完全从底层感知到！
        CLogAssert.assertEquals("当业务层修改对象内数值时，装配了该逻辑的宿主能够正确感知到1个异动", 1, netObj.countDirtyVariables());
        
        // 再改私有变量
        logic.getIsShielded().setValue(true);
        CLogAssert.assertEquals("进一步变动私有包装变量，宿主应清点出2个异动", 2, netObj.countDirtyVariables());
    }
}
