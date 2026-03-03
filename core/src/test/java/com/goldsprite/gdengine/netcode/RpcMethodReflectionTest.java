package com.goldsprite.gdengine.netcode;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import com.goldsprite.CLogAssert;

public class RpcMethodReflectionTest {

    // 充当我们需要测试的业务逻辑类
    public static class GunBehaviour {
        public boolean bulletFired = false;
        
        @ServerRpc
        public void requestFire(int bulletId, float x, float y) {
            bulletFired = true;
        }

        @ClientRpc
        public void playExplosion(String fixName) {
            // client only
        }
        
        public void normalMethod() {
            // Not a RPC
        }
    }

    @Test
    public void testScanRpcMethods() {
        GunBehaviour logic = new GunBehaviour();
        
        // 我们期望有一个 RpcScanner 工具类能提取所有的 ServerRpc
        List<Method> serverRpcs = RpcScanner.getServerRpcs(logic.getClass());
        List<Method> clientRpcs = RpcScanner.getClientRpcs(logic.getClass());
        
        // 核心验证 1：扫描数量是否正确
        CLogAssert.assertEquals("应当能在业务类中正确扫描出1个 @ServerRpc 标注的方法", 1, serverRpcs.size());
        CLogAssert.assertEquals("被扫描出的ServerRpc方法名称应当匹配", "requestFire", serverRpcs.get(0).getName());
        
        CLogAssert.assertEquals("应当能在业务类中正确扫描出1个 @ClientRpc 标注的方法", 1, clientRpcs.size());
        CLogAssert.assertEquals("被扫描出的ClientRpc方法名称应当匹配", "playExplosion", clientRpcs.get(0).getName());

        // 核心验证 2：参数类型签名是否匹配
        Class<?>[] paramTypes = serverRpcs.get(0).getParameterTypes();
        CLogAssert.assertEquals("该RPC方法签名参数数量应当为3个", 3, paramTypes.length);
        CLogAssert.assertEquals("第一个参数类型校验应当为int", int.class, paramTypes[0]);
        CLogAssert.assertEquals("第二个参数类型校验应当为float", float.class, paramTypes[1]);
        CLogAssert.assertEquals("第三个参数类型校验应当为float", float.class, paramTypes[2]);
    }

    @Test
    public void testInvokeRpc() throws Exception {
        GunBehaviour logic = new GunBehaviour();
        List<Method> serverRpcs = RpcScanner.getServerRpcs(logic.getClass());
        Method rpcMethod = serverRpcs.get(0);
        
        // 发送前 bulletFired 是 false
        CLogAssert.assertFalse("模拟收到网络请求前，受保护的业务状态应当为原始的 false", logic.bulletFired);
        
        // 模拟收到了网络包反序列化后（这里用反射硬调用演示结果）
        rpcMethod.invoke(logic, 999, 10.5f, 20.0f);
        
        // 调用后应当变为 true
        CLogAssert.assertTrue("通过反射动态分发方法后，开火业务状态应当成功变更为 true", logic.bulletFired);
    }
}
