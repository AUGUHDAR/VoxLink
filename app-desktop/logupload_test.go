package main

// logupload.go 的自检：arm/终局快传/断链无条件传/稳定窗口顺延/单飞/关程序兜底/载荷格式。
//
// 全部走 t.TempDir() + loopback httptest，不碰真网络：
// 服务端契约（头名、gzip、sha256、RATE_LIMITED）按 server-go/loguploader.go:251-320 的判据核对。

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type capturedRequest struct {
	route  string // 解码后的 route（信封形态是 ?route=%2Flog%2Fupload，不能直接字符串包含比较）
	header http.Header
	body   []byte
}

type logCapture struct {
	mu       sync.Mutex
	requests []capturedRequest
}

func (c *logCapture) add(r capturedRequest) {
	c.mu.Lock()
	c.requests = append(c.requests, r)
	c.mu.Unlock()
}

func (c *logCapture) count(route string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.requests {
		if r.route == route {
			n++
		}
	}
	return n
}

func (c *logCapture) last(route string) (capturedRequest, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for i := len(c.requests) - 1; i >= 0; i-- {
		if c.requests[i].route == route {
			return c.requests[i], true
		}
	}
	return capturedRequest{}, false
}

// waitForRoute 等到某条路由出现（异步上报/上传都要给一点点时间）。
func waitForRoute(c *logCapture, route string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if c.count(route) > 0 {
			return true
		}
		time.Sleep(10 * time.Millisecond)
	}
	return c.count(route) > 0
}

// newLogUploadHarness 造一个把 baseURL 指向 loopback 的 App，并在配置目录下备好 logs/latest.log。
func newLogUploadHarness(t *testing.T, responder func(w http.ResponseWriter, r *http.Request)) (*App, *logCapture) {
	t.Helper()
	hc := &logCapture{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<22))
		route := r.URL.Query().Get("route")
		if route == "" {
			route = r.URL.Path
		}
		hc.add(capturedRequest{route: route, header: r.Header.Clone(), body: body})
		w.Header().Set("Content-Type", "application/json")
		if responder != nil {
			responder(w, r)
			return
		}
		_, _ = w.Write([]byte(`{"success":true,"data":{"saved":true}}`))
	}))
	t.Cleanup(srv.Close)

	dir := t.TempDir()
	a := NewApp()
	a.settingsPath = filepath.Join(dir, "VoxLinkApp", "settings.json")
	a.mu.Lock()
	a.settings.ServerURL = srv.URL
	a.mu.Unlock()

	// 备好日志文件（模组的 latest.log 等价物，见 logUploadLogPath 注释）
	if err := os.MkdirAll(filepath.Dir(a.logUploadLogPath()), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(a.logUploadLogPath(), []byte("line-1 voxlink punch sample\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	resetLogUploadState()
	logUploadEnabled.Store(true)
	t.Cleanup(resetLogUploadState)
	return a, hc
}

func resetLogUploadState() {
	logUploadMu.Lock()
	if logUploadFuture != 0 && logUploadSched != nil {
		logUploadSched.cancel(logUploadFuture)
	}
	if logPollFuture != 0 && logUploadSched != nil {
		logUploadSched.cancel(logPollFuture)
	}
	logUploadFuture = 0
	logPollFuture = 0
	logActiveCodePtr = nil
	logActiveCode = ""
	logActiveIsHost = false
	logPunchStartMs = 0
	logConnectedMs = 0
	logLastActiveMs = 0
	logLastReportMs = 0
	logRole = ""
	logFailureArmed = false
	logOnUploaded = nil
	logUploadMu.Unlock()
	logUploaded.Store(false)
	logInFlight.Store(false)
}

// TestLogUploadConstants 与 LogUploadManager.java:29-42 对齐。
func TestLogUploadConstants(t *testing.T) {
	cases := []struct {
		name string
		got  any
		want any
	}{
		{"UPLOAD_DELAY_MS", logUploadDelay, 90 * time.Second},               // :29
		{"STABLE_WINDOW_MS", logStableWindow, 120 * time.Second},            // :30
		{"ACTIVITY_TIMEOUT_MS", logActivityTimeout, 15 * time.Second},       // :31
		{"HOST_WAIT_TIMEOUT_MS", logHostWaitTimeout, 5 * time.Minute},       // :32
		{"FAILURE_UPLOAD_DELAY_MS", logFailureDelay, 5 * time.Second},       // :34
		{"MIN_AGE_FOR_QUIT_UPLOAD_MS", logMinAgeForQuit, 30 * time.Second},  // :36
		{"SHUTDOWN_UPLOAD_TIMEOUT_MS", logShutdownTimeout, 6 * time.Second}, // :37
		{"MAX_LOG_BYTES", logMaxBytes, 4 * 1024 * 1024},                     // :38
		{"MAX_ATTEMPTS", logMaxAttempts, 3},                                 // :39
		{"RETRY_DELAY_MS", logRetryDelay, 15 * time.Second},                 // :40
		{"ROUTE_STATUS", logRouteStatus, "/log/status"},                     // :41
		{"ROUTE_UPLOAD", logRouteUpload, "/log/upload"},                     // :42
		{"orTimeout", logUploadOrTimeout, 90 * time.Second},                 // :376
		{"请求超时=config 连接超时默认值", logRequestTimeout, 20 * time.Second},        // VoxLinkConfig.java:62
		{"429 的 retryAfter", logRateLimitedRetrySec, 15},                    // :578
		{"disarm 补传延时", logFlushOnDisarmDelay, 200 * time.Millisecond},      // :268
		{"稳定窗口内顺延", logPostponeInterval, 15 * time.Second},                  // :344
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s = %v, want %v", c.name, c.got, c.want)
		}
	}
}

// TestLogUploadReadTail 尾部读取的三条分支（:506-521）。
func TestLogUploadReadTail(t *testing.T) {
	dir := t.TempDir()
	a := NewApp()
	a.settingsPath = filepath.Join(dir, "VoxLinkApp", "settings.json")
	resetLogUploadState()
	t.Cleanup(resetLogUploadState)

	// 文件缺失 → nil（模组 "latest.log missing, skip"）
	if got := a.logUploadReadLogTail(); got != nil {
		t.Errorf("缺失时应返回 nil，得 %d 字节", len(got))
	}

	// 空文件 → 0 长度（:510-513），buildPayload 视同无内容跳过
	if err := os.MkdirAll(filepath.Dir(a.logUploadLogPath()), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(a.logUploadLogPath(), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	if got := a.logUploadReadLogTail(); len(got) != 0 {
		t.Errorf("空文件应返回 0 长度，得 %d", len(got))
	}
	if p := a.logUploadBuildPayload(); p != nil { // :487-490
		t.Error("无内容时不应产出 payload")
	}

	// 超过 4MB → 只取尾部 4MB（:514）
	const tailMark = "TAIL-MARKER-OK"
	var buf bytes.Buffer
	// 每 1000B 一个块，块首 10B 是块号：若用纯重复模式，偏移恰好是内容周期的整数倍时
	// "开头不同"就测不出来（1710 % 10 == 0 会伪装成没偏移）
	for i := 0; i < logMaxBytes/1000+2; i++ { // 约 4MB+2KB
		buf.WriteString(fmt.Sprintf("BLK%07d", i)) // 3+7 = 10B，配 990B 填充正好一个块
		buf.Write(bytes.Repeat([]byte("x"), 990))
	}
	buf.WriteString(tailMark)
	if err := os.WriteFile(a.logUploadLogPath(), buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	got := a.logUploadReadLogTail()
	if len(got) != logMaxBytes {
		t.Fatalf("尾部长度 = %d, want %d", len(got), logMaxBytes)
	}
	if !bytes.HasSuffix(got, []byte(tailMark)) {
		t.Error("取到的不是文件尾部")
	}
	// 偏移已前移：开头不应等于文件开头
	if bytes.Equal(got[:24], buf.Bytes()[:24]) {
		t.Error("读到的开头与文件开头一致，说明偏移没前移")
	}
}

// TestLogUploadBuildPayloadGzip gzip 载荷可被服务端式解码（:492-495；服务端验魔数+解压，
// server-go/loguploader.go:311-326）。
func TestLogUploadBuildPayloadGzip(t *testing.T) {
	a, _ := newLogUploadHarness(t, nil)
	p := a.logUploadBuildPayload()
	if p == nil {
		t.Fatal("应有载荷")
	}
	if len(p) < 2 || p[0] != 0x1f || p[1] != 0x8b { // 服务端魔数判据
		t.Fatalf("不是 gzip 流: % x", p[:min(2, len(p))])
	}
	gr, err := gzip.NewReader(bytes.NewReader(p))
	if err != nil {
		t.Fatal(err)
	}
	out, err := io.ReadAll(gr)
	_ = gr.Close()
	if err != nil || !strings.Contains(string(out), "punch sample") {
		t.Errorf("解压内容不符: %q err=%v", out, err)
	}
}

// TestLogUploadSHA256Hex RFC 已知向量（:610-623）。
func TestLogUploadSHA256Hex(t *testing.T) {
	if got := logUploadSHA256Hex([]byte("abc")); got != "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" {
		t.Errorf("sha256(\"abc\") = %s", got)
	}
	if got := logUploadSHA256Hex(nil); got != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" {
		t.Errorf("空输入摘要不符: %s", got)
	}
}

// TestLogUploadArmJoiner 房客 arm：起 90s 上传定时器、不起轮询、并登记一次 status（:110-112/:104）。
func TestLogUploadArmJoiner(t *testing.T) {
	a, hc := newLogUploadHarness(t, nil)
	a.logUploadArm(" ABC123 ", false) // :82 trim
	logUploadMu.Lock()
	code := ""
	if logActiveCodePtr != nil {
		code = *logActiveCodePtr
	}
	uploadScheduled := logUploadFuture != 0
	pollScheduled := logPollFuture != 0
	role := logRole
	startSet := logPunchStartMs > 0
	logUploadMu.Unlock()
	if code != "ABC123" {
		t.Errorf("房码应被 trim 后登记，得 %q", code)
	}
	if !uploadScheduled || pollScheduled { // :110-115
		t.Errorf("房客应只有上传定时器: upload=%v poll=%v", uploadScheduled, pollScheduled)
	}
	if role != "joiner" || !startSet { // :97-98
		t.Errorf("role=%q punchStart 已置=%v", role, startSet)
	}
	// reportStatus 是异步的（模组 sendAsync），等一下再看请求体（:309-313，不带 query）
	if !waitForRoute(hc, logRouteStatus, 2*time.Second) {
		t.Fatal("arm 时应登记一次 status")
	}
	req, _ := hc.last(logRouteStatus)
	body := string(req.body)
	if !strings.Contains(body, `"role":"joiner"`) || !strings.Contains(body, `"enabled":true`) || strings.Contains(body, `"query"`) {
		t.Errorf("status 请求体不符: %s", body)
	}
}

// TestLogUploadArmHost 房主 arm：不起 90s 定时器、起 5s 轮询（:110-115）。
func TestLogUploadArmHost(t *testing.T) {
	a, _ := newLogUploadHarness(t, nil)
	a.logUploadArm("ABC123", true)
	logUploadMu.Lock()
	uploadScheduled := logUploadFuture != 0
	pollScheduled := logPollFuture != 0
	role := logRole
	logUploadMu.Unlock()
	if uploadScheduled {
		t.Error("房主不该在 arm 时就起 90s 定时器（要等 punch_info）")
	}
	if !pollScheduled || role != "host" { // :113-115
		t.Errorf("房主应起轮询且 role=host: poll=%v role=%q", pollScheduled, role)
	}
}

// TestLogUploadUploadHeaders 上传请求的头集合与体（:547-569；服务端按头判合法性）。
func TestLogUploadUploadHeaders(t *testing.T) {
	a, hc := newLogUploadHarness(t, nil)
	a.logUploadArm("ABC123", false)
	var cbRan int32
	logUploadSetOnUploaded(func() { atomic.AddInt32(&cbRan, 1) })

	a.logUploadWithRetry("ABC123", logMaxAttempts)
	if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
		t.Fatal("没有 /log/upload 请求")
	}
	deadline := time.Now().Add(2 * time.Second)
	for !logUploaded.Load() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}

	req, _ := hc.last(logRouteUpload)
	h := req.header
	wants := map[string]string{
		"Content-Type":      "application/octet-stream", // :553
		"Accept":            "application/json",         // :554
		"X-Log-Role":        "joiner",                   // :558
		"X-Log-Code":        "ABC123",                   // :559
		"X-Log-Version":     appVersion,                 // :561
		"X-VoxLink-Version": appVersion,                 // :556
	}
	for name, want := range wants {
		if got := h.Get(name); got != want {
			t.Errorf("头 %s = %q, want %q", name, got, want)
		}
	}
	if h.Get("X-Log-Sha256") != logUploadSHA256Hex(req.body) { // :557
		t.Error("X-Log-Sha256 与请求体不一致（服务端会判 LOG_HASH_MISMATCH）")
	}
	if h.Get("X-Log-Duration-Ms") == "" { // :562
		t.Error("缺 X-Log-Duration-Ms")
	}
	if h.Get("X-Log-Name") == "" { // :560 玩家名（本端为登录用户名，取不到则空）
		t.Log("X-Log-Name 为空：本机取不到登录名")
	}
	if len(req.body) < 2 || req.body[0] != 0x1f || req.body[1] != 0x8b { // 载荷是 gzip
		t.Error("请求体不是 gzip 流")
	}
	if !logUploaded.Load() { // :392
		t.Error("上传成功后应置 UPLOADED")
	}
	if atomic.LoadInt32(&cbRan) != 1 { // :393-395 onUploaded 回调
		t.Errorf("onUploaded 回调执行次数 = %d", cbRan)
	}
	if !logUploadIsUploadFinished() { // :277-280
		t.Error("isUploadFinished 应为 true")
	}
}

// TestLogUploadSingleFlight 并发双 runUpload 只允许一个真 post（:361-369 单飞锁）。
func TestLogUploadSingleFlight(t *testing.T) {
	slow := make(chan struct{})
	unblockOnce := sync.Once{}
	a, hc := newLogUploadHarness(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("route") == logRouteUpload {
			<-slow
			unblockOnce.Do(func() { close(slow) })
		}
		_, _ = w.Write([]byte(`{"success":true}`))
	})
	a.logUploadArm("ABC123", false)

	var start sync.WaitGroup
	for i := 0; i < 2; i++ {
		start.Add(1)
		go func() {
			defer start.Done()
			a.logUploadWithRetry("ABC123", logMaxAttempts)
		}()
	}
	start.Wait()
	time.Sleep(100 * time.Millisecond)
	// 第二条撞单飞锁 → 只登记 15s 后的重试（:363-367），此刻 upload 仍只有一次
	if n := hc.count(logRouteUpload); n != 1 {
		t.Errorf("并发上传应只 post 一次，实为 %d", n)
	}
	close(slow)
	time.Sleep(200 * time.Millisecond)
	if n := hc.count(logRouteUpload); n != 1 {
		t.Errorf("收口后仍应只有一次上传，实为 %d", n)
	}
}

// TestLogUploadRunUploadStableWindow 稳定窗口内顺延、过窗口跳过、数据面静默立即传（:333-350）。
func TestLogUploadRunUploadStableWindow(t *testing.T) {
	t.Run("窗口内顺延不上传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		logUploadMu.Lock()
		logConnectedMs = time.Now().UnixMilli()
		logLastActiveMs = logConnectedMs
		logUploadMu.Unlock()
		a.logUploadRunUpload()
		time.Sleep(150 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("稳定窗口内不应上传")
		}
		logUploadMu.Lock()
		again := logUploadFuture != 0
		logUploadMu.Unlock()
		if !again {
			t.Error("应已顺延一次复查（:344）")
		}
	})

	t.Run("已过窗口跳过", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		logUploadMu.Lock()
		logConnectedMs = time.Now().UnixMilli() - (logStableWindow + time.Second).Milliseconds()
		logLastActiveMs = time.Now().UnixMilli() // 仍在跳心跳 = 链路活着
		logUploadMu.Unlock()
		a.logUploadRunUpload()
		time.Sleep(150 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("连接稳定应 skip upload（:346-349）")
		}
	})

	t.Run("连上后数据面静默立即传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		logUploadMu.Lock()
		logConnectedMs = time.Now().UnixMilli() - 5000
		logLastActiveMs = time.Now().UnixMilli() - (logActivityTimeout + 5*time.Second).Milliseconds()
		logUploadMu.Unlock()
		a.logUploadRunUpload()
		if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
			t.Error("数据面静默超 15s 应判失败并上传（:335-337）")
		}
	})

	t.Run("玩家关掉开关则不传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		logUploadEnabled.Store(false) // LogUploadState.java:8/:12-16
		a.logUploadArm("ABC123", false)
		a.logUploadRunUpload()
		time.Sleep(150 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("开关关闭时不得上传（:326-329）")
		}
	})
}

// TestLogUploadTerminalFailureAndDisarmFlush 终局失败置位 + disarm 补传；纯取消不补传（:183-196/:252-271）。
func TestLogUploadTerminalFailureAndDisarmFlush(t *testing.T) {
	t.Run("终局失败后退出补传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnTerminalFailure() // :190 置 failureUploadArmed
		logUploadMu.Lock()
		armed := logFailureArmed
		logUploadMu.Unlock()
		if !armed {
			t.Fatal("onTerminalFailure 应置 failureUploadArmed")
		}
		// :187 幂等：第二次不再改排期
		logUploadMu.Lock()
		fut := logUploadFuture
		logUploadMu.Unlock()
		a.logUploadOnTerminalFailure()
		logUploadMu.Lock()
		same := logUploadFuture == fut
		logUploadMu.Unlock()
		if !same {
			t.Error("已 armed 时重复调用不得重排（:187-189）")
		}
		a.logUploadDisarm() // :256 flushFailure → 200ms 后补传
		if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
			t.Error("终局失败后退出应补传（:266-270）")
		}
	})

	t.Run("纯取消不上传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadDisarm() // failureUploadArmed=false → 只取消
		time.Sleep(250 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("主动取消（未失败）不得上传")
		}
		logUploadMu.Lock()
		cleared := logActiveCodePtr == nil && logUploadFuture == 0 && logPollFuture == 0
		logUploadMu.Unlock()
		if !cleared {
			t.Error("disarm 应清空登记与排期（:257-265）")
		}
	})

	t.Run("disarm 后在途重试不得再冒出来", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadDisarm()
		a.logUploadWithRetry("ABC123", logMaxAttempts) // :356-360
		time.Sleep(150 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("已 disarm 的重试链必须静默（模组注释原文）")
		}
	})
}

// TestLogUploadLinkDeadAndDisconnected 断链与判死都无条件快传（:157-181，2026-10-05 规则）。
func TestLogUploadLinkDeadAndDisconnected(t *testing.T) {
	t.Run("onDisconnected 已连上才算掉线", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnDisconnected() // connectedAtMs==0 → :160 不成立
		time.Sleep(150 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("没连上过就不算掉线，不该上传")
		}
		a.logUploadOnConnected() // :141-145
		a.logUploadOnDisconnected()
		if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
			t.Error("连上后掉线应无条件上传（稳定窗口拦截已移除）")
		}
	})

	t.Run("onLinkDead 与断链同权", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnLinkDead()
		if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
			t.Error("链路判死应与断链同权快传（:170-181）")
		}
		a.logUploadOnLinkDead() // :173 已上传则不再传
		time.Sleep(150 * time.Millisecond)
		if n := hc.count(logRouteUpload); n != 1 {
			t.Errorf("UPLOADED 后应停止重复上传，实为 %d 次", n)
		}
	})

	t.Run("无登记时判死不动作", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadOnLinkDead() // activeCode==null → :173-175
		time.Sleep(150 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("未在会话中不应上传")
		}
	})
}

// TestLogUploadHostPollOpponentUploaded 房主轮询到"对手已传"→ 立刻跟上（:225-240）。
func TestLogUploadHostPollOpponentUploaded(t *testing.T) {
	a, hc := newLogUploadHarness(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("route") == logRouteStatus { // :230-233
			_, _ = w.Write([]byte(`{"success":true,"data":{"opponent":{"uploaded":true}}}`))
			return
		}
		_, _ = w.Write([]byte(`{"success":true}`))
	})
	a.logUploadArm("ABC123", true)
	a.logUploadPollOpponentUploaded()
	if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
		t.Error("对手已传时房主应立刻跟上")
	}
	// 轮询请求必须带 query=true（:224）；status 可能有多次（arm 的登记 + 轮询），取带 query 的那次
	var sawQuery bool
	hc.mu.Lock()
	for _, r := range hc.requests {
		if r.route == logRouteStatus && strings.Contains(string(r.body), `"query":true`) {
			sawQuery = true
		}
	}
	hc.mu.Unlock()
	if !sawQuery {
		t.Error("轮询 status 请求体缺 query=true")
	}
}

// TestLogUploadHostPollStableWindowSelfStop 本端稳定后轮询自行摘掉（:207-214）。
func TestLogUploadHostPollStableWindowSelfStop(t *testing.T) {
	a, hc := newLogUploadHarness(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"success":true,"data":{"opponent":{"uploaded":true}}}`))
	})
	a.logUploadArm("ABC123", true)
	logUploadMu.Lock()
	logConnectedMs = time.Now().UnixMilli() - (logStableWindow + time.Second).Milliseconds()
	logUploadMu.Unlock()
	a.logUploadPollOpponentUploaded()
	time.Sleep(150 * time.Millisecond)
	logUploadMu.Lock()
	pollCleared := logPollFuture == 0
	logUploadMu.Unlock()
	if !pollCleared {
		t.Error("本端稳定时应取消轮询")
	}
	if hc.count(logRouteUpload) != 0 {
		t.Error("稳定时不因对手上传而跟传")
	}
}

// TestLogUploadHostWaitTimeout 房主等对手超 5 分钟则自行上传（:215-219）。
func TestLogUploadHostWaitTimeout(t *testing.T) {
	a, hc := newLogUploadHarness(t, nil)
	a.logUploadArm("ABC123", true)
	logUploadMu.Lock()
	logPunchStartMs = time.Now().UnixMilli() - (logHostWaitTimeout + time.Minute).Milliseconds()
	logUploadMu.Unlock()
	a.logUploadPollOpponentUploaded()
	if !waitForRoute(hc, logRouteUpload, 3*time.Second) {
		t.Error("等对手超时应照传（host wait timeout, upload anyway）")
	}
}

// TestLogUploadSchedulePunchUpload 房主 punch 起点重置计时（:118-138）。
func TestLogUploadSchedulePunchUpload(t *testing.T) {
	a, _ := newLogUploadHarness(t, nil)
	a.logUploadArm("ABC123", true)
	logUploadMu.Lock()
	if logUploadFuture != 0 {
		logUploadMu.Unlock()
		t.Fatal("arm 时房主不应有上传排期")
	}
	logUploadMu.Unlock()

	a.logUploadSchedulePunchUpload()
	logUploadMu.Lock()
	uploadAfter := logUploadFuture
	start := logPunchStartMs
	logUploadMu.Unlock()
	if uploadAfter == 0 || start == 0 {
		t.Errorf("punch 后应起 90s 排期并置计时基准: upload=%d start=%d", uploadAfter, start)
	}

	// 已连接且过了稳定窗口时不得被新 joiner 触发（:123-126）
	logUploadMu.Lock()
	logConnectedMs = time.Now().UnixMilli() - (logStableWindow + time.Second).Milliseconds()
	logUploadMu.Unlock()
	a.logUploadSchedulePunchUpload() // 应直接返回，不改排期
	logUploadMu.Lock()
	stillScheduled := logUploadFuture != 0
	logUploadMu.Unlock()
	if !stillScheduled {
		t.Error("稳定窗口的早退不应破坏已有排期")
	}

	// 房客侧调用直接返回（:120）
	b, _ := newLogUploadHarness(t, nil)
	b.logUploadArm("ABC123", false)
	b.logUploadSchedulePunchUpload()
}

// TestLogUploadTransportActivityThrottle onTransportActivity 的内部 1s 节流（:147-155）。
func TestLogUploadTransportActivityThrottle(t *testing.T) {
	a, _ := newLogUploadHarness(t, nil)
	a.logUploadArm("ABC123", false)
	logUploadMu.Lock()
	logLastActiveMs = 0
	logLastReportMs = 0
	logUploadMu.Unlock()

	a.logUploadOnTransportActivity()
	logUploadMu.Lock()
	first := logLastActiveMs
	logUploadMu.Unlock()
	if first == 0 {
		t.Fatal("首次调用应刷新活跃时间")
	}
	a.logUploadOnTransportActivity()
	logUploadMu.Lock()
	second := logLastActiveMs
	logUploadMu.Unlock()
	if second != first {
		t.Errorf("1s 内重复调用应被节流: %d → %d", first, second)
	}
	logUploadMu.Lock()
	logLastReportMs = first - logActivityThrottle.Milliseconds() - 1
	logLastActiveMs = 1 // 哨兵：窗一放开就必须被写回 now（比 third != second 稳，同毫秒会假失败）
	logUploadMu.Unlock()
	a.logUploadOnTransportActivity()
	logUploadMu.Lock()
	third := logLastActiveMs
	logUploadMu.Unlock()
	if third == 1 {
		t.Error("节流窗过后应再次刷新")
	}
}

// TestLogUploadOnConnectedDoesNotCancel 连接成功只记时间，不取消上传（:140-145 注释原文）。
func TestLogUploadOnConnectedDoesNotCancel(t *testing.T) {
	a, _ := newLogUploadHarness(t, nil)
	a.logUploadArm("ABC123", false)
	logUploadMu.Lock()
	before := logUploadFuture
	logUploadMu.Unlock()
	a.logUploadOnConnected()
	logUploadMu.Lock()
	after := logUploadFuture
	connected := logConnectedMs
	active := logLastActiveMs
	logUploadMu.Unlock()
	if before == 0 || after != before {
		t.Errorf("onConnected 不应改排期: before=%d after=%d", before, after)
	}
	if connected == 0 || active != connected { // :143-144
		t.Errorf("connected=%d lastActive=%d", connected, active)
	}
}

// TestLogUploadOnExitGates 关程序兜底的四道门（:411-427）。
func TestLogUploadOnExitGates(t *testing.T) {
	t.Run("终局失败补传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnTerminalFailure()
		a.logUploadOnExit()
		if n := hc.count(logRouteUpload); n != 1 {
			t.Errorf("终局失败的会话退出应补传一次，实为 %d", n)
		}
		if !logUploaded.Load() { // :472-473
			t.Error("兜底上传成功应置 UPLOADED")
		}
	})

	t.Run("满30秒未连上的放弃型样本补传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		logUploadMu.Lock()
		logPunchStartMs = time.Now().UnixMilli() - (logMinAgeForQuit + 5*time.Second).Milliseconds()
		logUploadMu.Unlock()
		a.logUploadOnExit()
		if n := hc.count(logRouteUpload); n != 1 {
			t.Errorf("放弃型样本应补传，实为 %d", n)
		}
	})

	t.Run("秒进秒出不传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnExit() // 既非终局失败、也不满 30s
		time.Sleep(50 * time.Millisecond)
		if hc.count(logRouteUpload) != 0 {
			t.Error("秒退不得上传（模组注释：秒进秒出不算）")
		}
	})

	t.Run("已连接会话不传", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnConnected()
		logUploadMu.Lock()
		logFailureArmed = true // 即便置了失败位，connectedAtMs>0 也要挡住（:418-421）
		logUploadMu.Unlock()
		a.logUploadOnExit()
		if hc.count(logRouteUpload) != 0 {
			t.Error("已连接的会话视为正常使用，关程序不传")
		}
	})

	t.Run("在途上传时不重复兜底", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnTerminalFailure()
		logInFlight.Store(true) // :412 UPLOAD_IN_FLIGHT
		a.logUploadOnExit()
		if hc.count(logRouteUpload) != 0 {
			t.Error("单飞锁占用时兜底应直接放弃")
		}
	})

	t.Run("已上传过不再兜底", func(t *testing.T) {
		a, hc := newLogUploadHarness(t, nil)
		a.logUploadArm("ABC123", false)
		a.logUploadOnTerminalFailure()
		logUploaded.Store(true) // :412 UPLOADED
		a.logUploadOnExit()
		if hc.count(logRouteUpload) != 0 {
			t.Error("已上传的会话不应重复兜底")
		}
	})
}

// TestLogUploadParseResponse 响应解析的四条分支（:571-582）。
func TestLogUploadParseResponse(t *testing.T) {
	cases := []struct {
		name      string
		status    int
		body      string
		wantOK    bool
		wantCode  string
		wantRetry int
	}{
		{"200 成功", 200, `{"success":true,"data":{"saved":true}}`, true, "", 0},
		{"200 业务失败", 200, `{"success":false,"error":"LOG_HASH_MISMATCH","message":"x"}`, false, "LOG_HASH_MISMATCH", 0},
		{"200 非 JSON", 200, `not json`, false, "PARSE_ERROR", 0},
		{"429", 429, `{"success":false,"error":"RATE_LIMITED"}`, false, "RATE_LIMITED", 15}, // :577-579 写死 15
		{"500 带信封", 500, `{"success":false,"error":"INTERNAL_ERROR"}`, false, "INTERNAL_ERROR", 0},
		{"500 无信封", 500, `<html>oops</html>`, false, "SERVER_500", 0}, // :581
	}
	for _, c := range cases {
		r := logUploadParseResponse(c.status, c.body)
		if r.success != c.wantOK || r.code != c.wantCode {
			t.Errorf("%s: got (%v,%q) want (%v,%q)", c.name, r.success, r.code, c.wantOK, c.wantCode)
		}
		if r.retryAfter != c.wantRetry {
			t.Errorf("%s: retryAfter=%d want %d", c.name, r.retryAfter, c.wantRetry)
		}
	}
	// data 里要能取到 opponent（轮询判据 :230-233）
	r := logUploadParseResponse(200, `{"success":true,"data":{"opponent":{"uploaded":true}}}`)
	opp, ok := r.data["opponent"].(map[string]any)
	if !ok || opp["uploaded"] != true {
		t.Errorf("opponent 解析失败: %+v", r.data)
	}
}

// TestLogUploadStatusRateLimited 429 时 status 上报不炸、只记日志（:314-316）。
func TestLogUploadStatusRateLimited(t *testing.T) {
	a, hc := newLogUploadHarness(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"success":false,"error":"RATE_LIMITED"}`))
	})
	a.logUploadArm("ABC123", false) // reportStatus 走这条
	if !waitForRoute(hc, logRouteStatus, 2*time.Second) {
		t.Fatal("status 应已发出")
	}
	time.Sleep(50 * time.Millisecond)
	if logUploaded.Load() {
		t.Error("status 限频不应影响上传标记")
	}
}

// TestLogUploadLogLiteralsSurviveSanitizer 本模块日志字面量的消敏自检。
// 接线后 logUploadLog 的出口就是 App.netLog（hunk 见交付报告），必须过 netlog_sanitize_test.go 的正则。
func TestLogUploadLogLiteralsSurviveSanitizer(t *testing.T) {
	literals := []string{
		"log_upload_armed role=joiner",
		"log_upload_host_punch_timer_rearmed",
		"log_upload_link_dropped_inside_stable_window_upload_now",
		"log_upload_link_dead_upload_now",
		"log_upload_terminal_failure_upload_in_5s",
		"log_upload_left_room_after_terminal_failure_flush",
		"log_upload_host_wait_timeout_upload_anyway",
		"log_upload_opponent_uploaded_host_upload_now",
		"log_upload_skipped_disabled_by_player",
		"log_upload_transport_silent_upload_now",
		"log_upload_connection_stable_skip",
		"log_upload_already_in_flight_retry_in_15s",
		"log_upload_error retry_left=2",
		"log_upload_gave_up err=timeout",
		"log_upload_failed retry_left=1",
		"log_upload_uploaded role=host bytes=20480",
		"log_upload_status_report_failed code=RATE_LIMITED",
		"log_upload_no_log_content_skip",
		"log_upload_latest_log_missing_skip",
		"log_upload_read_latest_log_failed err=read",
		"log_upload_gzip_failed err=write",
		"log_upload_shutdown_ok cause=failed bytes=20480",
		"log_upload_shutdown_rejected http=413",
		"log_upload_shutdown_failed err=network",
	}
	for _, m := range literals {
		clean := sanitizeNetLogMessage(m)
		assertNoSensitive(t, m, clean)
		if clean != m {
			t.Errorf("字面量不该被消敏改写: %q → %q", m, clean)
		}
	}
	dirty := "log_upload_shutdown_failed err=Post \"https://example.com/?route=/log/upload\": dial tcp 192.0.2.9:443: connectex: i/o timeout"
	assertNoSensitive(t, dirty, sanitizeNetLogMessage(dirty))
}

// TestLogUploadBoundMethods LogUploadStatus / SetEnabled 的信封形状（UI/CLI 共用）。
func TestLogUploadBoundMethods(t *testing.T) {
	a, _ := newLogUploadHarness(t, nil)
	var env struct {
		OK   bool `json:"ok"`
		Data struct {
			Enabled  bool `json:"enabled"`
			Uploaded bool `json:"uploaded"`
			InRoom   bool `json:"inRoom"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(a.LogUploadStatus()), &env); err != nil {
		t.Fatal(err)
	}
	if !env.OK || !env.Data.Enabled || env.Data.InRoom {
		t.Errorf("初始状态信封不符: %+v", env)
	}
	a.logUploadArm("ABC123", false)
	if err := json.Unmarshal([]byte(a.LogUploadStatus()), &env); err != nil {
		t.Fatal(err)
	}
	if !env.Data.InRoom {
		t.Error("arm 之后 inRoom 应为 true")
	}
	out := a.LogUploadSetEnabled(false)
	if !strings.Contains(out, `"enabled":false`) {
		t.Errorf("关闭开关的回执不符: %s", out)
	}
	if logUploadEnabled.Load() {
		t.Error("开关未生效")
	}
	if logRoleOf() != "joiner" {
		t.Errorf("关开关不应清会话，role 仍在: %q", logRoleOf())
	}
}
