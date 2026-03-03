package com.goldsprite;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.backends.headless.HeadlessNet;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;

public class GdxTestRunner extends BlockJUnit4ClassRunner {

	private static boolean initialized = false;

	// [新增] 用于控制 internal() 的根路径
	public static String mockAssetsRoot = null;

	public GdxTestRunner(Class<?> klass) throws InitializationError {
		super(klass);
		initGdxEnvironment();
	}

	private synchronized void initGdxEnvironment() {
		if (initialized) return;

		// 1. Application Mock
		Gdx.app = (Application) Proxy.newProxyInstance(
			Application.class.getClassLoader(),
			new Class[]{Application.class},
			(proxy, method, args) -> {
				String name = method.getName();
				if (name.equals("getType")) return Application.ApplicationType.Desktop;
				if (name.equals("getPreferences")) return new MemoryPreferences();
				if (name.equals("log") || name.equals("error") || name.equals("debug")) {
					String tag = args.length > 0 ? String.valueOf(args[0]) : "";
					String msg = args.length > 1 ? String.valueOf(args[1]) : "";
					System.out.println("[GDX-" + tag + "] " + msg);
				}
				// [关键] 在测试环境中，postRunnable 立即执行（无主循环）
				if (name.equals("postRunnable") && args != null && args.length > 0 && args[0] instanceof Runnable) {
					((Runnable) args[0]).run();
					return null;
				}
				return defaultValue(method.getReturnType());
			}
		);

		// 2. Graphics Mock
		Gdx.graphics = (Graphics) Proxy.newProxyInstance(
			Graphics.class.getClassLoader(),
			new Class[]{Graphics.class},
			(proxy, method, args) -> {
				if (method.getName().equals("getDeltaTime")) return 0.016f;
				if (method.getName().equals("getFrameId")) return 1L;
				return defaultValue(method.getReturnType());
			}
		);

		// 3. GL20 Mock
		Gdx.gl = (GL20) Proxy.newProxyInstance(
			GL20.class.getClassLoader(),
			new Class[]{GL20.class},
			(proxy, method, args) -> defaultValue(method.getReturnType())
		);
		Gdx.gl20 = Gdx.gl;

		// 4. Files Implementation
		Gdx.files = new Files() {
			@Override
			public FileHandle getFileHandle(String path, FileType type) {
				// 简单的透传，如果是 Internal 且设置了 Root，转给 internal() 处理
				if (type == FileType.Internal) return internal(path);
				return new FileHandle(new File(path));
			}

			@Override public FileHandle classpath(String path) { return new FileHandle(new File(path)); }

			@Override
			public FileHandle internal(String path) {
				// [核心修改] 支持重定向 Internal 路径到沙盒
				if (mockAssetsRoot != null) {
					return new FileHandle(new File(mockAssetsRoot, path));
				}
				return new FileHandle(new File(path));
			}

			@Override public FileHandle external(String path) { return new FileHandle(new File(path)); }
			@Override public FileHandle absolute(String path) { return new FileHandle(new File(path)); }
			@Override public FileHandle local(String path) { return new FileHandle(new File(path)); }
			@Override public String getExternalStoragePath() { return ""; }
			@Override public boolean isExternalStorageAvailable() { return true; }
			@Override public String getLocalStoragePath() { return ""; }
			@Override public boolean isLocalStorageAvailable() { return true; }
		};

		// 5. Net Mock (Headless Implementation)
		Gdx.net = new HeadlessNet(new HeadlessApplicationConfiguration());

		initialized = true;
	}

	/**
	 * 返回基本类型的默认值，防止空指针或类型转换错误
	 */
	private Object defaultValue(Class<?> type) {
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == float.class) return 0f;
		if (type == long.class) return 0L;
		if (type == double.class) return 0.0;
		return null;
	}

	// [新增] 内存版 Preferences 实现 (仅用于测试)
	public static class MemoryPreferences implements Preferences {
		private final Map<String, Object> values = new HashMap<>();

		@Override public Preferences putBoolean(String key, boolean val) { values.put(key, val); return this; }
		@Override public Preferences putInteger(String key, int val) { values.put(key, val); return this; }
		@Override public Preferences putLong(String key, long val) { values.put(key, val); return this; }
		@Override public Preferences putFloat(String key, float val) { values.put(key, val); return this; }
		@Override public Preferences putString(String key, String val) { values.put(key, val); return this; }
		@Override public Preferences put(Map<String, ?> vals) { values.putAll(vals); return this; }
		@Override public boolean getBoolean(String key) { return getBoolean(key, false); }
		@Override public int getInteger(String key) { return getInteger(key, 0); }
		@Override public long getLong(String key) { return getLong(key, 0); }
		@Override public float getFloat(String key) { return getFloat(key, 0); }
		@Override public String getString(String key) { return getString(key, ""); }
		@Override public boolean getBoolean(String key, boolean defValue) { Object v = values.get(key); return v instanceof Boolean ? (Boolean) v : defValue; }
		@Override public int getInteger(String key, int defValue) { Object v = values.get(key); return v instanceof Integer ? (Integer) v : defValue; }
		@Override public long getLong(String key, long defValue) { Object v = values.get(key); return v instanceof Long ? (Long) v : defValue; }
		@Override public float getFloat(String key, float defValue) { Object v = values.get(key); return v instanceof Float ? (Float) v : defValue; }
		@Override public String getString(String key, String defValue) { Object v = values.get(key); return v instanceof String ? (String) v : defValue; }
		@Override public Map<String, ?> get() { return values; }
		@Override public boolean contains(String key) { return values.containsKey(key); }
		@Override public void clear() { values.clear(); }
		@Override public void remove(String key) { values.remove(key); }
		@Override public void flush() { /* Do nothing in memory */ }
	}

	@Override
	public void run(RunNotifier notifier) {
		super.run(notifier);
	}
}
