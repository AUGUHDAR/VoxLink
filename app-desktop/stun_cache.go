package main

// stun_cache.go — 模组 network/StunCache.java（1.2.0，141 行）的磁盘缓存移植。
//
// 逐值对齐：
//   - CACHE_TTL_MS = 300000L（StunCache.java:16），isExpired 用的就是这个数（:138）
//   - 文件名 stun_cache.json（:17），目录 <gameDir>/voxlink/（:20-29）
//   - 网络指纹 = 非回环且 up 的网卡"名字 + MAC 十六进制"顺序拼接（:31-54），空/异常 → "unknown"
//   - 读缓存：文件不存在 → nil（:59-61）；过期或 JSON 损坏 → nil（:83-90）；
//     指纹变了 → 作废并 log（:67-70）；命中但映射地址为空 → 同样作废（:77-82）
//   - 写缓存：映射地址为空直接跳过（:103/:113）
//   - 目录创建失败只 warn，不中断（:23-26）—— 随后写文件自然失败并被 catch
//
// 落盘位置：模组写在 Minecraft 的 gameDir/voxlink/ 下；独立客户端没有 gameDirectory，
// 这里按本仓 modsync 的同一惯例退回应用配置目录（%APPDATA%/VoxLinkApp）下的 voxlink/，
// 见 modsync_env.go:270-282。差异已在交付报告里说明，不是自作主张的语义改动。
//
// 重要事实（源码为准，与文档不同）：StunCache.load() 在模组里**没有任何调用点**
// （全类引用只有 StunProbe.java:544 clear 与 :594 save）。也就是说模组只写盘、
// 从不读盘；跨会话复用实际走 StunProbe 的 20s 进程内热缓存。这里仍把 load 完整移植，
// 但接线时不要"顺手"把它接成 cache-before-probe —— 那不是模组行为。

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	stunCacheTTLMS = 300000 // StunCache.java:16 CACHE_TTL_MS（=300s）
	stunCacheFile  = "stun_cache.json"
	stunCacheDir   = "voxlink" // StunCache.java:22 new File(gameDir, "voxlink")
	stunCacheAgeMs = 3600000   // 日志里 age 的除数（:75 / 3600000L，整数小时）
)

// stunCacheEntry = StunCache.Entry（:117-140）。JSON 键与 Java 字段名逐字一致（Gson 用字段名）。
type stunCacheEntry struct {
	NatType            string   `json:"natType"`
	MappedIP           string   `json:"mappedIp"`
	MappedPort         int      `json:"mappedPort"`
	StunURLs           []string `json:"stunUrls"`
	Timestamp          int64    `json:"timestamp"`
	NetworkFingerprint string   `json:"networkFingerprint"`
}

// isExpired = Entry.isExpired()（:137-139）。
func (e *stunCacheEntry) isExpired() bool {
	return time.Now().UnixMilli()-e.Timestamp > stunCacheTTLMS
}

// stunCacheDirOverride 测试注入用（t.TempDir()）；生产为 nil，走默认路径。
var (
	stunCachePathMu       sync.Mutex
	stunCacheDirOverrideV string
)

// SetStunCacheDirForTest 仅供测试改落盘目录，避免写进真实用户配置目录。
func SetStunCacheDirForTest(dir string) {
	stunCachePathMu.Lock()
	stunCacheDirOverrideV = dir
	stunCachePathMu.Unlock()
}

// stunCachePath = getCachePath()（:20-29）：<base>/voxlink/stun_cache.json。
func stunCachePath() string {
	stunCachePathMu.Lock()
	override := stunCacheDirOverrideV
	stunCachePathMu.Unlock()
	base := override
	if base == "" {
		base = filepath.Dir(defaultSettingsPath())
	}
	dir := filepath.Join(base, stunCacheDir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		// 对应 Java 检查 mkdirs 返回值后只 warn（:23-26），不 return
		stunCacheLog("warn", "Failed to create cache directory: %s", dir)
	}
	return filepath.Join(dir, stunCacheFile)
}

// ---- 日志出口：与 stun_probe.go 同一注入通道，只换前缀 ----

func stunCacheLog(level, format string, args ...any) {
	msg := "[StunCache] " + fmt.Sprintf(format, args...)
	stunLogMu.RLock()
	fn := stunLogFn
	stunLogMu.RUnlock()
	fn(level, msg)
}

// stunNetworkFingerprint = buildNetworkFingerprint()（:31-54）：
// 非回环 + 已启用 + 有 MAC 的网卡，按系统顺序拼 "网卡名 + MAC 各字节 %02x"。
// 一个都不满足（或枚举异常）→ "unknown"。
func stunNetworkFingerprint() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "unknown"
	}
	var sb strings.Builder
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagLoopback != 0 {
			continue // ni.isLoopback()
		}
		if ifi.Flags&net.FlagUp == 0 {
			continue // !ni.isUp()
		}
		mac := ifi.HardwareAddr
		if len(mac) == 0 {
			continue // getHardwareAddress() 返回 null（Go 侧 nil/空切片同一语义）
		}
		sb.WriteString(ifi.Name)
		for _, b := range mac {
			sb.WriteString(fmt.Sprintf("%02x", b))
		}
	}
	if sb.Len() > 0 {
		return sb.String()
	}
	return "unknown"
}

// stunCacheLoad = load()（:56-91）。任何异常/过期/指纹不符/地址为空都返回 nil（= 需要重探）。
// 注意：模组侧此函数无调用点，见文件头说明。
func stunCacheLoad() *stunCacheEntry {
	path := stunCachePath()
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil // Files.exists(path)==false → null（:59-61），不打日志
		}
		stunCacheLog("debug", "Read failed: %s", err.Error())
		return nil
	}
	var entry stunCacheEntry
	if err := json.Unmarshal(data, &entry); err != nil {
		stunCacheLog("debug", "Read failed: %s", err.Error()) // 损坏文件（Gson JsonSyntaxException 同路径）
		return nil
	}
	if entry.isExpired() {
		stunCacheLog("info", "Cache expired or invalid")
		return nil
	}
	if current := stunNetworkFingerprint(); current != entry.NetworkFingerprint {
		stunCacheLog("info", "Network changed, cache invalidated")
		return nil
	}
	stunCacheLog("info", "Hit: NAT=%s, mapped=%s:%d, age=%dh",
		entry.NatType, entry.MappedIP, entry.MappedPort,
		(time.Now().UnixMilli()-entry.Timestamp)/stunCacheAgeMs)
	if entry.MappedIP != "" && entry.MappedPort > 0 {
		return &entry
	}
	stunCacheLog("info", "mapped address empty, invalidate for re-probe")
	return nil
}

// stunCacheClear = clear()（:93-100）。
func stunCacheClear() {
	path := stunCachePath()
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		stunCacheLog("debug", "Clear failed: %s", err.Error())
	}
}

// stunCacheSave = save(natType, mappedIp, mappedPort, stunUrls)（:102-115）。
// 由 StunProbe.probeAsync 在探测完成后调用（StunProbe.java:594）。
func stunCacheSave(natType, mappedIP string, mappedPort int, stunURLs []string) {
	if mappedIP == "" || mappedPort <= 0 {
		stunCacheLog("info", "Skip save: mapped address empty")
		return
	}
	entry := stunCacheEntry{
		NatType:            natType,
		MappedIP:           mappedIP,
		MappedPort:         mappedPort,
		StunURLs:           stunURLs,
		Timestamp:          time.Now().UnixMilli(), // Entry 构造器里赋值（:133）
		NetworkFingerprint: stunNetworkFingerprint(),
	}
	data, err := json.MarshalIndent(entry, "", "  ") // GsonBuilder().setPrettyPrinting()（:18）
	if err != nil {
		stunCacheLog("debug", "Save failed: %s", err.Error())
		return
	}
	if err := os.WriteFile(stunCachePath(), data, 0o644); err != nil {
		stunCacheLog("debug", "Save failed: %s", err.Error())
		return
	}
	stunCacheLog("info", "Saved: NAT=%s, mapped=%s:%d", natType, mappedIP, mappedPort)
}
