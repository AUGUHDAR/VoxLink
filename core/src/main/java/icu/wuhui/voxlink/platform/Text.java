package icu.wuhui.voxlink.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 平台无关的聊天/界面文案值类型, 替代 net.minecraft.network.chat.Component。
 * 翻译渲染由宿主注入, key/args 原样透传, 语言文件零改动。
 */
public final class Text {
   private final String key;
   private final Object[] args;
   private final String literal;
   private final Style style;

   private Text(String key, Object[] args, String literal, Style style) {
      this.key = key;
      this.args = args == null ? new Object[0] : args;
      this.literal = literal;
      this.style = style == null ? Style.EMPTY : style;
   }

   public static Text translatable(String key, Object... args) {
      return new Text(Objects.requireNonNull(key, "key"), args, null, null);
   }

   public static Text literal(String value) {
      return new Text(null, null, value == null ? "" : value, null);
   }

   public static Text empty() {
      return new Text(null, null, "", null);
   }

   public String getKey() {
      return this.key;
   }

   /** 恒非 null; 调用方可能注入后修改, 返回副本防外部污染。 */
   public Object[] getArgs() {
      return Arrays.copyOf(this.args, this.args.length);
   }

   public boolean isLiteral() {
      return this.key == null;
   }

   public String getLiteral() {
      return this.literal;
   }

   public Style getStyle() {
      return this.style;
   }

   public Text withStyle(Style s) {
      if (s == null || s == Style.EMPTY) {
         return this;
      }
      return new Text(this.key, this.args, this.literal, this.style.merge(s));
   }

   public String toPlainString() {
      if (this.literal != null) {
         return this.literal;
      }
      // 未注入渲染时兜底: 裸 key 原样返回, 保证 UI 侧旧 getString() 调用永不 NPE/不丢参语义
      return this.key == null ? "" : this.key;
   }

   // 拼接语义保留本端样式: 顺序渲染后合并为 literal, 合法覆盖 .append 链的现存用途
   public Text append(Text other) {
      if (other == null || other.isEmpty()) {
         return this;
      }
      if (this.isEmpty()) {
         return other;
      }
      return new Text(null, null, this.toPlainString() + other.toPlainString(), this.style);
   }

   private boolean isEmpty() {
      return this.literal != null && this.literal.isEmpty() && this.style == Style.EMPTY;
   }

   public static Text join(String sep, List<Text> parts) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Text t : parts) {
         if (t == null) {
            continue;
         }
         if (!first) {
            sb.append(sep);
         }
         first = false;
         sb.append(t.toPlainString());
      }
      return literal(sb.toString());
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }
      if (!(o instanceof Text t)) {
         return false;
      }
      return Objects.equals(this.key, t.key)
         && Objects.equals(this.literal, t.literal)
         && Arrays.equals(this.args, t.args)
         && Objects.equals(this.style, t.style);
   }

   @Override
   public int hashCode() {
      int h = Objects.hash(this.key, this.literal, this.style);
      return 31 * h + Arrays.hashCode(this.args);
   }

   @Override
   public String toString() {
      return "Text{" + (this.isLiteral() ? "literal='" + this.literal + "'" : "key=" + this.key + ", args=" + Arrays.toString(this.args)) + "}";
   }

   /** 样式值类型: 字段与设计文档 1.1 一致, 仅承载现存用值。 */
   public record Style(Text.ChatFormat color, Boolean bold, Boolean italic, Boolean underLine, Boolean strikethrough,
      Boolean obfuscated, String clickAction, String clickValue, Text hoverText) {
      public static final Style EMPTY = new Style(null, null, null, null, null, null, null, null, null);

      public Style merge(Style other) {
         return new Style(
            other.color != null ? other.color : this.color,
            other.bold != null ? other.bold : this.bold,
            other.italic != null ? other.italic : this.italic,
            other.underLine != null ? other.underLine : this.underLine,
            other.strikethrough != null ? other.strikethrough : this.strikethrough,
            other.obfuscated != null ? other.obfuscated : this.obfuscated,
            other.clickAction != null ? other.clickAction : this.clickAction,
            other.clickValue != null ? other.clickValue : this.clickValue,
            other.hoverText != null ? other.hoverText : this.hoverText
         );
      }

      public static Builder builder() {
         return new Builder();
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof Style s)) {
            return false;
         }
         return this.color == s.color
            && Objects.equals(this.bold, s.bold)
            && Objects.equals(this.italic, s.italic)
            && Objects.equals(this.underLine, s.underLine)
            && Objects.equals(this.strikethrough, s.strikethrough)
            && Objects.equals(this.obfuscated, s.obfuscated)
            && Objects.equals(this.clickAction, s.clickAction)
            && Objects.equals(this.clickValue, s.clickValue)
            && Objects.equals(this.hoverText, s.hoverText);
      }

      @Override
      public int hashCode() {
         return Objects.hash(
            this.color, this.bold, this.italic, this.underLine, this.strikethrough, this.obfuscated,
            this.clickAction, this.clickValue, this.hoverText
         );
      }
   }

   public static final class Builder {
      private Text.ChatFormat color;
      private Boolean bold;
      private Boolean italic;
      private Boolean underLine;
      private Boolean strikethrough;
      private Boolean obfuscated;
      private String clickAction;
      private String clickValue;
      private Text hoverText;

      private Builder() {
      }

      public Builder color(Text.ChatFormat c) {
         this.color = c;
         return this;
      }

      public Builder bold(Boolean b) {
         this.bold = b;
         return this;
      }

      public Builder italic(Boolean b) {
         this.italic = b;
         return this;
      }

      public Builder underline(Boolean b) {
         this.underLine = b;
         return this;
      }

      public Builder strikethrough(Boolean b) {
         this.strikethrough = b;
         return this;
      }

      public Builder obfuscated(Boolean b) {
         this.obfuscated = b;
         return this;
      }

      public Builder click(String action, String value) {
         this.clickAction = action;
         this.clickValue = value;
         return this;
      }

      public Builder hover(Text text) {
         this.hoverText = text;
         return this;
      }

      public Style build() {
         return new Style(this.color, this.bold, this.italic, this.underLine, this.strikethrough, this.obfuscated, this.clickAction, this.clickValue, this.hoverText);
      }
   }

   /** 仅复刻现存用到的格式值; 未列出的格式暂无消费方。 */
   public enum ChatFormat {
      AQUA, YELLOW, GRAY, RED, BLUE, DARK_RED, GREEN, WHITE, BLACK, GOLD, UNDERLINE, BOLD;

      public static ChatFormat parse(String name) {
         try {
            return ChatFormat.valueOf(name);
         } catch (IllegalArgumentException e) {
            return null;
         }
      }
   }
}
