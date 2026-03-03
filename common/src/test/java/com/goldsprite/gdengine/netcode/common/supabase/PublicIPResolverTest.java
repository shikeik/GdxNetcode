package com.goldsprite.gdengine.netcode.common.supabase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.backends.headless.HeadlessNet;
import com.goldsprite.GdxTestRunner;

@RunWith(GdxTestRunner.class)
public class PublicIPResolverTest {

    @BeforeClass
    public static void setup() {
        // Mock Gdx.net for testing outside of a Libgdx Application
        Gdx.net = new HeadlessNet(new HeadlessApplicationConfiguration());
    }

    @Test
    public void testResolvePublicIP() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        // Match a standard IPv4 address format
        Pattern ipv4Pattern = Pattern.compile("^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");

        PublicIPResolver.resolvePublicIP(new PublicIPResolver.ResolveCallback() {
            @Override
            public void onSuccess(String ip) {
                System.out.println("成功获取公网 IP: " + ip);
                assertTrue("IP格式不正确: " + ip, ipv4Pattern.matcher(ip).matches());
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
                latch.countDown();
                fail("不应抛出异常: " + t.getMessage());
            }
        });

        // Wait for asynchronous request
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue("获取公网IP请求超时", completed);
    }
}