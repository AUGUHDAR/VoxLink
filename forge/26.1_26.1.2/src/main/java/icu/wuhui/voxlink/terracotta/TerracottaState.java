package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;

public abstract class TerracottaState {
   public abstract String name();

   public static TerracottaState.Ready parseFromState(JsonObject json, int port) {
      if (json == null) {
         TerracottaState.Waiting w = new TerracottaState.Waiting();
         w.index = 0;
         w.state = "waiting";
         w.port = port;
         return w;
      }

      String stateName = json.has("state") && !json.get("state").isJsonNull() ? json.get("state").getAsString() : "unknown";
      int index = json.has("index") && !json.get("index").isJsonNull() ? json.get("index").getAsInt() : 0;

      TerracottaState.Ready state = switch (stateName) {
         case "waiting", "idle" -> new TerracottaState.Waiting();
         case "host-scanning" -> new TerracottaState.HostScanning();
         case "host-starting" -> new TerracottaState.HostStarting();
         case "host-ok" -> {
            TerracottaState.HostOK h = new TerracottaState.HostOK();
            h.code = json.has("room") && !json.get("room").isJsonNull() ? json.get("room").getAsString() : null;
            yield h;
         }
         case "guest-connecting" -> new TerracottaState.GuestConnecting();
         case "guest-starting" -> {
            TerracottaState.GuestStarting gs = new TerracottaState.GuestStarting();
            gs.difficulty = json.has("difficulty") && !json.get("difficulty").isJsonNull() ? json.get("difficulty").getAsString() : "UNKNOWN";
            yield gs;
         }
         case "guest-ok" -> {
            TerracottaState.GuestOK g = new TerracottaState.GuestOK();
            g.url = json.has("url") && !json.get("url").isJsonNull() ? json.get("url").getAsString() : null;
            yield g;
         }
         case "exception" -> {
            TerracottaState.Exception e = new TerracottaState.Exception();
            e.type = json.has("type") && !json.get("type").isJsonNull() && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : "UNKNOWN";
            yield e;
         }
         default -> {
            TerracottaState.Waiting w = new TerracottaState.Waiting();
            yield w;
         }
      };
      state.index = index;
      state.state = stateName;
      state.port = port;
      return state;
   }

   public static final class Bootstrap extends TerracottaState {
      public static final TerracottaState.Bootstrap INSTANCE = new TerracottaState.Bootstrap();

      @Override
      public String name() {
         return "bootstrap";
      }

      @Override
      public String toString() {
         return "Bootstrap";
      }
   }

   public static final class Exception extends TerracottaState.Ready {
      public String type = "UNKNOWN";

      @Override
      public String name() {
         return "exception";
      }

      @Override
      public String toString() {
         return "Exception[type=" + this.type + ",index=" + this.index + "]";
      }
   }

   public static final class Fatal extends TerracottaState {
      public final TerracottaState.Fatal.Type type;

      public Fatal(TerracottaState.Fatal.Type type) {
         this.type = type;
      }

      public boolean isRecoverable() {
         return this.type != TerracottaState.Fatal.Type.UNKNOWN;
      }

      @Override
      public String name() {
         return "fatal";
      }

      @Override
      public String toString() {
         return "Fatal[" + this.type + "]";
      }

      public enum Type {
         OS,
         NETWORK,
         INSTALL,
         TERRACOTTA,
         UNKNOWN;
      }
   }

   public static final class GuestConnecting extends TerracottaState.Ready {
      @Override
      public String name() {
         return "guest-connecting";
      }

      @Override
      public String toString() {
         return "GuestConnecting[index=" + this.index + "]";
      }
   }

   public static final class GuestOK extends TerracottaState.Ready {
      public String url;

      @Override
      public String name() {
         return "guest-ok";
      }

      @Override
      public String toString() {
         return "GuestOK[url=" + this.url + ",index=" + this.index + "]";
      }
   }

   public static final class GuestStarting extends TerracottaState.Ready {
      public String difficulty;

      @Override
      public String name() {
         return "guest-starting";
      }

      @Override
      public String toString() {
         return "GuestStarting[difficulty=" + this.difficulty + ",index=" + this.index + "]";
      }
   }

   public static final class HostOK extends TerracottaState.Ready {
      public String code;

      @Override
      public String name() {
         return "host-ok";
      }

      @Override
      public String toString() {
         return "HostOK[code=" + this.code + ",index=" + this.index + "]";
      }
   }

   public static final class HostScanning extends TerracottaState.Ready {
      @Override
      public String name() {
         return "host-scanning";
      }

      @Override
      public String toString() {
         return "HostScanning[index=" + this.index + "]";
      }
   }

   public static final class HostStarting extends TerracottaState.Ready {
      @Override
      public String name() {
         return "host-starting";
      }

      @Override
      public String toString() {
         return "HostStarting[index=" + this.index + "]";
      }
   }

   public static final class Launching extends TerracottaState {
      public static final TerracottaState.Launching INSTANCE = new TerracottaState.Launching();

      @Override
      public String name() {
         return "launching";
      }

      @Override
      public String toString() {
         return "Launching";
      }
   }

   public abstract static class PortSpecific extends TerracottaState {
      public int port;
   }

   public abstract static class Ready extends TerracottaState.PortSpecific {
      public int index;
      public String state;

      public boolean isUIFakeState() {
         return this.index == -1;
      }
   }

   public static final class Uninitialized extends TerracottaState {
      public final boolean hasLegacy;

      public Uninitialized(boolean hasLegacy) {
         this.hasLegacy = hasLegacy;
      }

      @Override
      public String name() {
         return "uninitialized";
      }

      @Override
      public String toString() {
         return "Uninitialized[legacy=" + this.hasLegacy + "]";
      }
   }

   public static final class Unknown extends TerracottaState.PortSpecific {
      @Override
      public String name() {
         return "unknown";
      }

      @Override
      public String toString() {
         return "Unknown[port=" + this.port + "]";
      }
   }

   public static final class Waiting extends TerracottaState.Ready {
      @Override
      public String name() {
         return "waiting";
      }

      @Override
      public String toString() {
         return "Waiting[index=" + this.index + "]";
      }
   }
}
