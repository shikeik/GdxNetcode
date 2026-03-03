package com.goldsprite.gdengine.netcode.supabase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.net.HttpRequestBuilder;
import com.goldsprite.gdengine.log.DLog;

/**
 * IP 地址工具类：获取公网 IP 和局域网 IP。
 * <p>
 * 公网 IP 获取优先使用 libGDX {@code Gdx.net} 实现，
 * 若不可用（HeadlessApplication 等场景）则自动降级为纯 Java {@link HttpURLConnection}。
 */
public class PublicIPResolver {

    private static final String TAG = "PublicIPResolver";

    /**
     * 获取本机局域网 IPv4 地址（排除 127.0.0.1）。
     * 优先返回非虚拟网卡的地址；找不到时返回 "127.0.0.1"。
     */
    public static String getLocalIP() {
        try {
            // 第一轮：找 siteLocal 且非虚拟、非回环的网卡 IP
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // 忽略，走 fallback
        }
        return "127.0.0.1";
    }
    
    private static final String API_URL = "https://checkip.amazonaws.com";
    
    public interface ResolveCallback {
        void onSuccess(String ip);
        void onError(Throwable t);
    }
    
    /**
     * 异步获取当前机器的公网 IPv4 地址。
     * <p>
     * 优先尝试 {@code Gdx.net}（GUI 客户端），失败或不可用时降级为
     * 纯 Java {@link HttpURLConnection}（兼容 HeadlessApplication）。
     */
    public static void resolvePublicIP(final ResolveCallback callback) {
        // 尝试 Gdx.net（可能在 HeadlessApplication 中不可用或实现不完整）
        try {
            if (Gdx.net != null) {
                HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
                Net.HttpRequest httpRequest = requestBuilder.newRequest()
                    .method(Net.HttpMethods.GET)
                    .url(API_URL)
                    .timeout(5000)
                    .build();
                    
                Gdx.net.sendHttpRequest(httpRequest, new Net.HttpResponseListener() {
                    @Override
                    public void handleHttpResponse(Net.HttpResponse httpResponse) {
                        int statusCode = httpResponse.getStatus().getStatusCode();
                        if (statusCode == 200) {
                            String ip = httpResponse.getResultAsString().trim();
                            DLog.logT(TAG, "通过 Gdx.net 获取公网IP成功: " + ip);
                            if (callback != null) {
                                callback.onSuccess(ip);
                            }
                        } else {
                            DLog.logWarnT(TAG, "Gdx.net HTTP 状态码异常: " + statusCode + ", 降级为原生 HTTP");
                            resolvePublicIPNative(callback);
                        }
                    }

                    @Override
                    public void failed(Throwable t) {
                        DLog.logWarnT(TAG, "Gdx.net HTTP 失败: " + t.getMessage() + ", 降级为原生 HTTP");
                        resolvePublicIPNative(callback);
                    }
                    
                    @Override
                    public void cancelled() {
                        DLog.logWarnT(TAG, "Gdx.net HTTP 被取消, 降级为原生 HTTP");
                        resolvePublicIPNative(callback);
                    }
                });
                return; // Gdx.net 路径已启动，等待回调
            }
        } catch (Exception e) {
            DLog.logWarnT(TAG, "Gdx.net 不可用: " + e.getMessage() + ", 降级为原生 HTTP");
        }
        
        // Gdx.net 不可用，直接走原生 HTTP
        resolvePublicIPNative(callback);
    }
    
    /**
     * 使用纯 Java {@link HttpURLConnection} 获取公网 IP（在后台线程执行）。
     * <p>
     * 适用于 HeadlessApplication 等 {@code Gdx.net} 不可用的环境。
     */
    public static void resolvePublicIPNative(final ResolveCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int statusCode = conn.getResponseCode();
                if (statusCode == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String ip = reader.readLine().trim();
                        DLog.logT(TAG, "通过原生 HTTP 获取公网IP成功: " + ip);
                        if (callback != null) {
                            // 如果有 Gdx.app 就 postRunnable，否则直接回调
                            if (Gdx.app != null) {
                                Gdx.app.postRunnable(() -> callback.onSuccess(ip));
                            } else {
                                callback.onSuccess(ip);
                            }
                        }
                    }
                } else {
                    Throwable t = new RuntimeException("获取公网IP失败，HTTP状态码: " + statusCode);
                    DLog.logWarnT(TAG, t.getMessage());
                    if (callback != null) {
                        if (Gdx.app != null) {
                            Gdx.app.postRunnable(() -> callback.onError(t));
                        } else {
                            callback.onError(t);
                        }
                    }
                }
            } catch (Exception e) {
                DLog.logWarnT(TAG, "原生 HTTP 获取公网IP异常: " + e.getMessage());
                if (callback != null) {
                    if (Gdx.app != null) {
                        Gdx.app.postRunnable(() -> callback.onError(e));
                    } else {
                        callback.onError(e);
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, "PublicIPResolver").start();
    }
}