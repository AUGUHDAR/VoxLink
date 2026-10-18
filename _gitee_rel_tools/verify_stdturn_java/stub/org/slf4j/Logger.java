package org.slf4j;

// 桩：验证编译用（生产环境 MC 自带 slf4j）
public interface Logger {
   void info(String fmt, Object... args);
   void warn(String fmt, Object... args);
   void debug(String fmt, Object... args);
}
