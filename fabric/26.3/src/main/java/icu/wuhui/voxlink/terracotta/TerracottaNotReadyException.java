package icu.wuhui.voxlink.terracotta;

public final class TerracottaNotReadyException extends RuntimeException {
   private static final long serialVersionUID = 1L;

   public TerracottaNotReadyException(String message) {
      super(message);
   }

   public TerracottaNotReadyException(String message, Throwable cause) {
      super(message, cause);
   }
}
