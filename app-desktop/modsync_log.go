package main

// modsync_log.go — VoxLink ModSync 的日志出口 + 用户可见文案的语言键。
//
// 逐句对应 Java `modsync/ModSyncLog.java`（21 行）：
//   - 三级出口 info/warn/error，统一前缀 "[ModSync] "（ModSyncLog.java:8,12,16）
//   - 消息里的 `{}` 由参数依次替换（Java 走 LOGGER.info(msg, args)，即 SLF4J 语义；
//     Go 无 vararg logger，这里显式实现同一替换语义，见 modSyncFormat）。
//
// 出口选择：Java 走 `VoxLinkMod.LOGGER`（游戏日志）；本端对应 `App.netLog`
// （同时写本地日志与前端"连接日志"面板，app.go:117-125）。netLog 会做 IP/端口脱敏，
// ModSync 的日志行同样受此约束（应用侧隐私策略优先于逐字日志文本）。
//
// 语言键表：模组侧的用户可见字符串一律是 `voxlink.modsync.*` / `voxlink.logui.modsync_*`
// 语言键（zh_cn 共 50 个 modsync 键 + 2 个 logui 键，
// `fabric/1.20_1.20.1/src/main/resources/assets/voxlink/lang/zh_cn.json:318-378,425-426`）。
// Java 用 `Component.translatable(key, args).getString()` 就地渲染成字符串塞进 diff 列表
// （ModSyncGuestService.java:168,177,524,540）。移植后本端只传 {key,args}，
// 由前端按同一张 zh_cn 表渲染（HTML 渲染层，见 frontend/dist/assets/js/modsync.js），
// 这样语言切换归界面层，后端不抄写中文。

import (
	"fmt"
	"log"
	"strings"
)

// 语言键常量：值必须与 zh_cn.json 的键逐字一致。
const (
	// ModSyncGuestService.java:168（房主 MC 与本端不同）
	langModSyncMcMismatch = "voxlink.modsync.mc_mismatch"
	// ModSyncGuestService.java:177（加载器不同）
	langModSyncLoaderMismatch = "voxlink.modsync.loader_mismatch"
	// ModSyncGuestService.java:524（已装但被禁用）
	langModSyncDisabledHint = "voxlink.modsync.disabled_hint"
	// ModSyncGuestService.java:540（同类模组版本不一致）
	langModSyncDiffLine = "voxlink.modsync.diff_line"
	// ModSyncGuestService.java:222（门控自身异常 → 强提示后放行）
	langModSyncGateFailedWarn = "voxlink.modsync.gate_failed_warn"
	// ModSyncSelectScreen.java:318/388（下载失败）
	langModSyncDownloadFailed = "voxlink.modsync.download_failed"
	// ModSyncSelectScreen.java:398（玩家取消下载）
	langModSyncDownloadCancelled = "voxlink.modsync.download_cancelled"
	// ModSyncSelectScreen.java:408（失败后追加重试提示）
	langModSyncRetryHint = "voxlink.modsync.retry_hint"
	// ModSyncGuestService.java:148（UNSUPPORTED 直通 → 面板提示行）
	langLogUIModSyncUnsupported = "voxlink.logui.modsync_unsupported"
	// ModSyncGuestService.java:151（NOT_READY 直通 → 面板警告行）
	langLogUIModSyncFetchFailed = "voxlink.logui.modsync_fetch_failed"
)

// modSyncLangLine 一条"尚未渲染"的用户可见文案：语言键 + 位置参数。
// 对应 Java 的 Component.translatable(key, args)。
type modSyncLangLine struct {
	Key  string   `json:"key"`
	Args []string `json:"args,omitempty"`
}

func newModSyncLangLine(key string, args ...string) modSyncLangLine {
	return modSyncLangLine{Key: key, Args: args}
}

// modSyncFormat 把 `{}` 占位按顺序换成对应参数（SLF4J 语义的等价实现：
// 参数用尽后剩余的 `{}` 原样保留；多余的参数忽略）。
func modSyncFormat(msg string, args ...any) string {
	if !strings.Contains(msg, "{}") {
		return msg
	}
	var sb strings.Builder
	idx := 0
	for len(msg) > 0 {
		at := strings.Index(msg, "{}")
		if at < 0 {
			sb.WriteString(msg)
			break
		}
		sb.WriteString(msg[:at])
		msg = msg[at+2:]
		if idx < len(args) {
			sb.WriteString(modSyncArgToString(args[idx]))
			idx++
		} else {
			sb.WriteString("{}")
		}
	}
	return sb.String()
}

func modSyncArgToString(a any) string {
	switch v := a.(type) {
	case nil:
		return "null"
	case string:
		return v
	case error:
		return v.Error()
	default:
		return fmt.Sprint(v)
	}
}

func modSyncLogInfo(format string, args ...any)  { modSyncLog("info", format, args...) }
func modSyncLogWarn(format string, args ...any)  { modSyncLog("warn", format, args...) }
func modSyncLogError(format string, args ...any) { modSyncLog("error", format, args...) }

func modSyncLog(level, format string, args ...any) {
	msg := "[ModSync] " + modSyncFormat(format, args...)
	if app := modSyncCurrentApp(); app != nil {
		app.netLog(level, msg)
		return
	}
	log.Printf("[%s] %s", level, msg)
}

// modSyncClipRune 对应 ModSyncSelectScreen.clip(s, max)（:679-685）：
// 超长时截到 max-1 并补 "…"。Java 以 UTF-16 码元计数，Go 以字节计会切碎 rune
// （产出非法 UTF-8 后 JSON 里变 U+FFFD），这里按 rune 计数 —— 与 Java 仅在
// 非 BMP 字符上有 ±1 的差异，属日志/文案裁剪显示层，不影响任何判定。
func modSyncClipRune(s string, max int) string {
	if s == "" || max <= 1 {
		return s
	}
	r := []rune(s)
	if len(r) <= max {
		return s
	}
	return string(r[:max-1]) + "…"
}

// modSyncResolveClip 对应 ModSyncResolver.clip(s)（ModSyncResolver.java:865-871）：
// 固定 48 上限，超长截到 47 并补 "…"。
func modSyncResolveClip(s string) string {
	return modSyncClipRune(s, 48)
}
