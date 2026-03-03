package com.goldsprite;

import org.junit.Assert;

import java.util.Objects;

// ==========================================
// 🛠️ 自定义话唠断言工具
// ==========================================
public class CLogAssert {
	public static void assertTrue(String msg, boolean condition) {
		if (condition) {
			System.out.println("✅ PASS: " + msg);
		} else {
			System.err.println("❌ FAIL: " + msg);
			Assert.assertTrue(msg, false); // 触发 JUnit 失败
		}
	}

	public static void assertFalse(String msg, boolean condition) {
		if (!condition) {
			System.out.println("✅ PASS: " + msg);
		} else {
			System.err.println("❌ FAIL: " + msg + " (Expected False, got True)");
			Assert.assertFalse(msg, true);
		}
	}

	public static void assertEquals(String msg, Object expected, Object actual) {
		// [修复] 使用 Objects.equals 安全比较 null
		if (Objects.equals(expected, actual)) {
			System.out.println("✅ PASS: " + msg + " [Value: " + actual + "]");
		} else {
			System.err.println("❌ FAIL: " + msg + " (Expected: " + expected + ", Actual: " + actual + ")");
			Assert.assertEquals(msg, expected, actual);
		}
	}

	/** 浮点数比较（允许误差 delta） */
	public static void assertEquals(String msg, float expected, float actual, float delta) {
		if (Math.abs(expected - actual) <= delta) {
			System.out.println("✅ PASS: " + msg + " [Value: " + actual + "]");
		} else {
			System.err.println("❌ FAIL: " + msg + " (Expected: " + expected + ", Actual: " + actual + ", Delta: " + delta + ")");
			Assert.assertEquals(msg, expected, actual, delta);
		}
	}
}
