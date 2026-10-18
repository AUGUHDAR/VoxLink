package org.slf4j;

// 桩：验证编译用
public class LoggerFactory {
   public static Logger getLogger(String name) {
      return new Logger() {
         public void info(String fmt, Object... args) { System.out.println("[INFO] " + fmt(fmt, args)); }
         public void warn(String fmt, Object... args) { System.out.println("[WARN] " + fmt(fmt, args)); }
         public void debug(String fmt, Object... args) { }
         private String fmt(String f, Object... args) {
            if (args == null) return f;
            String out = f;
            for (Object a : args) { out = out.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(a))); }
            return out;
         }
      };
   }
}
