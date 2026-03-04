package com.goldsprite.gdengine.netcode.common.headless;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.goldsprite.gdengine.log.DLog;
import com.goldsprite.gdengine.log.DLog.Level;
import com.goldsprite.gdengine.log.DLog.LogOutput;

/**
 * 文件日志输出器。
 * <p>
 * 将 DLog 日志实时追加写入指定文件。
 * 主要用于 Headless Server 的日志持久化。
 */
public class FileLogOutput implements LogOutput {

	private final File logFile;
	private PrintWriter writer;
	private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

	public FileLogOutput(String filePath) {
		this.logFile = new File(filePath);
		try {
			// 确保父目录存在
			File parent = logFile.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			// 追加模式
			this.writer = new PrintWriter(new FileWriter(logFile, true), true); // autoFlush = true
			
			logSystem("=== Log Started: " + dateFormat.format(new Date()) + " ===");
		} catch (IOException e) {
			System.err.println("[FileLogOutput] Failed to open log file: " + filePath);
			e.printStackTrace();
		}
	}

	@Override
	public void onLog(Level level, String tag, String msg) {
		if (writer == null) return;

		// 格式: [Time] [Level] [Tag] Message
		// DLog 传入的 msg 已经包含了部分格式（由 DLog.formatString 生成），但通常不含 Time/Tag/Level
		// 实际上 DLog 的 StandardOutput 是自己拼装的。
		// 这里我们也自己拼装，保持一致性。
		
		StringBuilder sb = new StringBuilder();
		sb.append("[").append(dateFormat.format(new Date())).append("] ");
		sb.append("[").append(level.name()).append("] ");
		sb.append("[").append(tag).append("] ");
		sb.append(msg);

		writer.println(sb.toString());
	}
	
	private void logSystem(String msg) {
		if (writer != null) writer.println(msg);
	}

	/** 关闭文件流 */
	public void close() {
		if (writer != null) {
			logSystem("=== Log Ended ===");
			writer.close();
			writer = null;
		}
	}
}
