package main

// join_retry_test.go — 加入房间瞬态重试（对照 room/ConnectionManager.java:156-157,14891-14930
// 与 room/RoomManager.java:88、network/SignalingClient.java:69,279-283）。

import (
	"errors"
	"regexp"
	"strings"
	"testing"
)

// TestJoinRetryConstants 与 Java 常量逐一对照。
func TestJoinRetryConstants(t *testing.T) {
	if joinTransientMaxAttempts != 3 { // JOIN_TRANSIENT_MAX_ATTEMPTS = 3 (ConnectionManager.java:156)
		t.Errorf("joinTransientMaxAttempts = %d, want 3", joinTransientMaxAttempts)
	}
	if joinRetryBackoffFirstMs != 1500 || joinRetryBackoffSecondMs != 3000 { // JOIN_RETRY_BACKOFF_MS {1500,3000} (:157)
		t.Errorf("退避应为 {1500,3000}ms, got {%d,%d}", joinRetryBackoffFirstMs, joinRetryBackoffSecondMs)
	}
}

// TestIsTransientJoinError 可重试集合 = TRANSIENT_ERRORS（RoomManager.java:88）
// 加上本端传输层等价码 NETWORK（SignalingClient.java:640-650 的 NETWORK_ERROR 映射）。
func TestIsTransientJoinError(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{&APIError{Code: "NETWORK_ERROR"}, true},
		{&APIError{Code: "CDN_ERROR"}, true},
		{&APIError{Code: "RATE_LIMITED", Status: 429}, true},
		{&APIError{Code: "NETWORK"}, true}, // 本端传输层失败等价码
		{&APIError{Code: "ROOM_FULL"}, false},
		{&APIError{Code: "ROOM_NOT_FOUND_OR_WRONG_PASSWORD"}, false},
		{&APIError{Code: "ROOM_EXPIRED"}, false},
		{errors.New("json: cannot unmarshal"), false}, // 非 APIError（本地解析类）不重试
	}
	for i, c := range cases {
		if got := isTransientJoinError(c.err); got != c.want {
			t.Errorf("case %d: isTransientJoinError(%v) = %v, want %v", i, c.err, got, c.want)
		}
	}
}

// TestJoinIdempotencyKeyStable 幂等键：懒生成一次、重试链复用（SignalingClient.java:279-283），
// 32 位 hex（UUID 去横线同形，服务端只按字符串比对）。
func TestJoinIdempotencyKeyStable(t *testing.T) {
	a := NewApp()
	k1 := a.joinIdempotencyKey()
	k2 := a.joinIdempotencyKey()
	if k1 == "" || k1 != k2 {
		t.Fatalf("幂等键应稳定非空: %q vs %q", k1, k2)
	}
	if !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(k1) {
		t.Errorf("幂等键应为 32 位 hex, got %q", k1)
	}
}

// TestJoinGenerationBumpedByLeave 任何 kill of connection attempts 使代数 +1：
// leaveInternal 开头 bump（对齐 killAllConnectionAttempts :13687/13812 无条件 incrementAndGet），
// 旧重试链据 gen != joinAttemptGeneration 立即放弃（:14911）。
func TestJoinGenerationBumpedByLeave(t *testing.T) {
	a := NewApp()
	before := a.joinAttemptGeneration.Load()
	a.leaveInternal() // 无会话也必须 bump（Java 同款无条件递增）
	after := a.joinAttemptGeneration.Load()
	if after != before+1 {
		t.Errorf("leaveInternal 应使代数 +1: %d -> %d", before, after)
	}
}

// TestJoinBodyIdentityFields create/join 请求体的身份标识字段齐备：
// modVersion 必带（模组 SignalingClient.java:192/286 同口径），值恒为 appVersion。
func TestJoinBodyIdentityFields(t *testing.T) {
	if appVersion == "" || !strings.Contains(httpUserAgent, appVersion) {
		t.Error("appVersion 应参与上报标识")
	}
	// clientTag 归一口径与服务端一致（server-go/roommanager.go 的 normalizeClientTag）。
	// 2026-10-11 改：放开中文与空格、按"字"截到 10，不再"不匹配就整串丢空"。
	if got := normalizeClientTag(" KAMUCL "); got != "kamucl" {
		t.Errorf("大写/空白应归一: got %q", got)
	}
	if got := normalizeClientTag("非法 tag!"); got != "非法 tag" {
		t.Errorf("中文应保留、! 应剔掉: got %q", got)
	}
	if got := normalizeClientTag(strings.Repeat("a", 33)); got != strings.Repeat("a", 10) {
		t.Errorf("超过 10 个字应截断: got %q", got)
	}
	if got := normalizeClientTag("my_launcher-2"); got != "my_launche" {
		t.Errorf("13 个字应截到 10 个: got %q", got)
	}
	if got := normalizeClientTag(""); got != "" {
		t.Errorf("默认空值应保持空: got %q", got)
	}
}
