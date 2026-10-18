package org.slf4j.simple;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.SLF4JServiceProvider;
public class SimpleProvider implements SLF4JServiceProvider {
   static void log(String name, String lvl, String msg, Object... a) {
      String m = a != null && a.length > 0 ? MessageFormatter.arrayFormat(msg, a).getMessage() : msg;
      System.out.println("[" + name + "/" + lvl + "] " + m);
   }
   private final ILoggerFactory f = new ILoggerFactory() {
      public org.slf4j.Logger getLogger(String name) {
         return new L(name);
      }
   };
   static class L implements org.slf4j.Logger {
      final String n;
      L(String n) { this.n = n; }
      public String getName() { return n; }
      public boolean isTraceEnabled() { return false; }
      public boolean isTraceEnabled(Marker m) { return false; }
      public boolean isDebugEnabled(Marker m) { return true; }
      public boolean isInfoEnabled(Marker m) { return true; }
      public boolean isWarnEnabled(Marker m) { return true; }
      public boolean isErrorEnabled(Marker m) { return true; }
      public void trace(Marker m, String s) {}
      public void trace(Marker m, String s, Object o) {}
      public void trace(Marker m, String s, Object o, Object o2) {}
      public void trace(Marker m, String s, Object... a) {}
      public void trace(Marker m, String s, Throwable t) {}
      public void trace(String s) {}
      public void trace(String s, Object o) {}
      public void trace(String s, Object o, Object o2) {}
      public void trace(String s, Object... a) {}
      public void trace(String s, Throwable t) {}
      public boolean isDebugEnabled() { return true; }
      public void debug(Marker m, String s) { log(n, "DEBUG", s); }
      public void debug(Marker m, String s, Object o) { log(n, "DEBUG", s, o); }
      public void debug(Marker m, String s, Object o, Object o2) { log(n, "DEBUG", s, o, o2); }
      public void debug(Marker m, String s, Object... a) { log(n, "DEBUG", s, a); }
      public void debug(Marker m, String s, Throwable t) { log(n, "DEBUG", s); }
      public void debug(String s) { log(n, "DEBUG", s); }
      public void debug(String s, Object o) { log(n, "DEBUG", s, o); }
      public void debug(String s, Object o, Object o2) { log(n, "DEBUG", s, o, o2); }
      public void debug(String s, Object... a) { log(n, "DEBUG", s, a); }
      public void debug(String s, Throwable t) { log(n, "DEBUG", s); }
      public boolean isInfoEnabled() { return true; }
      public void info(Marker m, String s) { log(n, "INFO", s); }
      public void info(Marker m, String s, Object o) { log(n, "INFO", s, o); }
      public void info(Marker m, String s, Object o, Object o2) { log(n, "INFO", s, o, o2); }
      public void info(Marker m, String s, Object... a) { log(n, "INFO", s, a); }
      public void info(Marker m, String s, Throwable t) { log(n, "INFO", s); }
      public void info(String s) { log(n, "INFO", s); }
      public void info(String s, Object o) { log(n, "INFO", s, o); }
      public void info(String s, Object o, Object o2) { log(n, "INFO", s, o, o2); }
      public void info(String s, Object... a) { log(n, "INFO", s, a); }
      public void info(String s, Throwable t) { log(n, "INFO", s); }
      public boolean isWarnEnabled() { return true; }
      public void warn(Marker m, String s) { log(n, "WARN", s); }
      public void warn(Marker m, String s, Object o) { log(n, "WARN", s, o); }
      public void warn(Marker m, String s, Object o, Object o2) { log(n, "WARN", s, o, o2); }
      public void warn(Marker m, String s, Object... a) { log(n, "WARN", s, a); }
      public void warn(Marker m, String s, Throwable t) { log(n, "WARN", s); }
      public void warn(String s) { log(n, "WARN", s); }
      public void warn(String s, Object o) { log(n, "WARN", s, o); }
      public void warn(String s, Object o, Object o2) { log(n, "WARN", s, o, o2); }
      public void warn(String s, Object... a) { log(n, "WARN", s, a); }
      public void warn(String s, Throwable t) { log(n, "WARN", s); }
      public boolean isErrorEnabled() { return true; }
      public void error(Marker m, String s) { log(n, "ERROR", s); }
      public void error(Marker m, String s, Object o) { log(n, "ERROR", s, o); }
      public void error(Marker m, String s, Object o, Object o2) { log(n, "ERROR", s, o, o2); }
      public void error(Marker m, String s, Object... a) { log(n, "ERROR", s, a); }
      public void error(Marker m, String s, Throwable t) { log(n, "ERROR", s); }
      public void error(String s) { log(n, "ERROR", s); }
      public void error(String s, Object o) { log(n, "ERROR", s, o); }
      public void error(String s, Object o, Object o2) { log(n, "ERROR", s, o, o2); }
      public void error(String s, Object... a) { log(n, "ERROR", s, a); }
      public void error(String s, Throwable t) { log(n, "ERROR", s); }
   }
   public ILoggerFactory getLoggerFactory() { return f; }
   public IMarkerFactory getMarkerFactory() { return new BasicMarkerFactory(); }
   public String getRequestedApiVersion() { return "2.0.99"; }
   public void initialize() {}
   public org.slf4j.spi.MDCAdapter getMDCAdapter() { return new org.slf4j.helpers.NOPMDCAdapter(); }
}
