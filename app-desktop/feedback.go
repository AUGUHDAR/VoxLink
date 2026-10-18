package main

// feedback.go — 站内反馈上传器，逐段移植模组 network/FeedbackUploader.java（1.2.0，255 行）：
// 描述 + 任意附件（含可选日志）以 multipart/form-data 提交到信令服务器的 /feedback/submit，
// 附件走流式管道不整块读进内存，500MB 上限由本端预检 + 服务端硬限共同把关。
//
// 契约的另一半在 ui/FeedbackFormScreen.java（473 行），本轮只做**规格**不写界面（前端另有负责人），
// 需要的字段/状态/时序都写在 FeedbackFormSpec 注释里，前端按这份接。
//
// 与模组的两处事实差异（报告列明，不隐藏）：
//  1. 模组里 FeedbackUploader.submit **没有任何调用点**（全仓 grep 只有 mergeFiles/MAX_TOTAL_BYTES/
//     defaultLogFiles 被引用）：FeedbackFormScreen.java:425 实际走的是 TicketClient.submit
//     → /ticket/submit。本 App 对应物已经是 app.go/ticketstore.go 的 App.TicketSubmit，
//     所以本文件是"把模组这一份客户端补全"，不是替换工单入口；两者要不要并存请拍板。
//  2. 表单字段 client 的值：模组写死 "mod"（:136）。服务端只认 "mod"，其余一律按 "web"
//     处理（server-go/feedback.go:207-210），因此 App 端无论发 "mod" 还是 "app" 都会标错来源。
//     这里按"源码是最终裁判"保留 "mod" 原值，要正确标注需服务端先支持第三种来源。

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// ---- 常量（FeedbackUploader.java / FeedbackFormScreen.java）----

const (
	// feedbackMaxTotalBytes 附件合计上限 500MB（FeedbackUploader.java:29 MAX_TOTAL_BYTES = 500L*1024*1024）。
	// 与工单侧同一个数（ticketstore.go:25 ticketMaxTotalByte）。
	feedbackMaxTotalBytes = 500 * 1024 * 1024
	// feedbackRoute 提交端点（:219 "?route=/feedback/submit"）。
	feedbackRoute = "/feedback/submit"
	// feedbackDefaultServer 空地址时的默认信令服务器（:207 "https://link.voxdev.top"）。
	feedbackDefaultServer = "https://link.voxdev.top"
	// feedbackBoundaryPrefix multipart boundary 前缀（:133 "----VoxLinkFB" + 32 位 hex UUID）。
	feedbackBoundaryPrefix = "----VoxLinkFB"
	// feedbackSubmitTimeout 整次提交超时 30 分钟（:153 Duration.ofMinutes(30)）——
	// 500MB 在慢上行下必然超过通用 10s 超时（同 api.go:48-58 的判断）。
	feedbackSubmitTimeout = 30 * time.Minute
	// feedbackResponseLogPreview 响应体日志截断长度（:176 body.length()>300 ? 300+"…"）。
	feedbackResponseLogPreview = 300
	// feedbackRateLimitDefaultSec 被限频时默认等待（FeedbackFormScreen.java:433
	// ra = retryAfterSec>0 ? retryAfterSec : 600）。
	feedbackRateLimitDefaultSec = 600
	// feedbackMaxFileLines / feedbackMaxTipExamples 等属界面预算，见 FeedbackFormSpec。
)

// ---- 结果（class Result，:36-52）----

type feedbackResult struct {
	success bool
	// errorCode 失败错误码：RATE_LIMITED / FEEDBACK_TOO_LARGE / FEEDBACK_EMPTY / NETWORK_ERROR /
	// 其他服务端错误码（:38 注释原文）
	errorCode string
	// retryAfterSec RATE_LIMITED 时的等待秒数（服务端 Retry-After），非限频为 0（:40-41）
	retryAfterSec int
}

func feedbackOK() feedbackResult { return feedbackResult{success: true} } // :49-51 Result.ok()

// ---- 文件收集 ----

// feedbackDefaultLogFiles 收集游戏目录 logs/ 下的日志（defaultLogFiles，:54-66）。
// 只认 latest.log 与 debug.log，存在才收录，顺序照抄。
// 模组传的是 Minecraft gameDirectory；本端传 a.logUploadLogPath() 的父父目录（配置目录），
// 让"反馈带的日志"与"打洞样本上传读的日志"是同一份（见 logupload.go 的 logUploadLogPath 注释）。
func feedbackDefaultLogFiles(gameDir string) []string {
	var out []string
	logs := filepath.Join(gameDir, "logs")                     // :57
	for _, name := range []string{"latest.log", "debug.log"} { // :58
		p := filepath.Join(logs, name)
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() { // Files.isRegularFile
			out = append(out, p)
		}
	}
	return out
}

// feedbackMergeFiles 合并附件与日志（按路径去重、保持顺序）并给出合计字节数
// （mergeFiles，:68-112；sizeOut[0] 在模组是出参，这里改返回值）。
// 三道过滤与模组一致：
//   - 非普通文件跳过（:82）
//   - size<=0 跳过（:87）
//   - **真打开一次**才算数（:94-101 注释原文：系统锁定文件（如 C:\DumpStack.log）、权限不足的文件
//     在传输阶段才会失败（ofFile 懒打开），会把整次提交炸成 NETWORK_ERROR）
func feedbackMergeFiles(attachments, logFiles []string) ([]string, int64) {
	seen := map[string]bool{} // LinkedHashSet 语义：Go map + 顺序表
	var order []string
	add := func(list []string) { // :71-77
		for _, p := range list {
			if p == "" || seen[p] {
				continue
			}
			seen[p] = true
			order = append(order, p)
		}
	}
	add(attachments)
	add(logFiles)

	var total int64
	var out []string
	for _, p := range order {
		st, err := os.Stat(p) // :82 Files.isRegularFile(p)
		if err != nil || !st.Mode().IsRegular() {
			continue
		}
		if st.Size() <= 0 { // :87 Files.size(p) <= 0
			continue
		}
		// :96 try (var ch = Files.newByteChannel(p)) — 只验可读，立即关
		f, err := os.Open(p)
		if err != nil {
			feedbackLog("warn", "feedback_skip_unreadable name="+filepath.Base(p)) // :99（模组打全路径，本端只打文件名）
			continue
		}
		_ = f.Close()
		total += st.Size() // :104
		out = append(out, p)
	}
	return out, total
}

// ---- 提交 ----

// feedbackSubmit 提交反馈（submit，:120-171）。callback 在 HTTP 线程触发（模组同口径：
// "异步回调，HttpClient 线程触发；UI 侧自行切回主线程"，:35 注释原文）——本端即 goroutine。
func feedbackSubmit(serverURL, description, clientInfo string, attachments, logFiles []string, callback func(feedbackResult)) {
	urlStr := feedbackNormalizeURL(serverURL) // :123
	if urlStr == "" {
		feedbackLog("warn", "feedback_submit_aborted bad_server_url") // :125
		callback(feedbackResult{errorCode: "BAD_SERVER_URL"})
		return
	}
	feedbackLog("info", fmt.Sprintf("feedback_submit desc_chars=%d files=%d logs=%d", // :130-131
		len(description), len(attachments), len(logFiles)))

	// boundary 形态与模组一致："----VoxLinkFB" + 32 位 hex（:133）。
	// Go 的 multipart.Writer 自己也会生成随机 boundary，这里显式 SetBoundary，
	// 好让请求头里的 boundary 与报文里写的完全相同（模组是手拼，天然一致）。
	boundary := feedbackBoundaryPrefix + feedbackRandomHex32()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	if err := mw.SetBoundary(boundary); err != nil { // 空 boundary 才会失败，这里纯属防御
		callback(feedbackResult{errorCode: "NETWORK_ERROR"})
		return
	}
	// :135-137 三个文本字段，顺序照抄：description / client / clientInfo
	// client 的值保留模组原文 "mod"（差异原因见文件头第 2 条）
	for _, f := range []struct{ name, value string }{
		{"description", description},
		{"client", "mod"},
		{"clientInfo", clientInfo},
	} {
		if err := feedbackWriteTextField(mw, f.name, f.value); err != nil { // :226-235
			callback(feedbackResult{errorCode: "NETWORK_ERROR"}) // :167-170 setup error
			return
		}
	}

	merged, total := feedbackMergeFiles(attachments, logFiles) // :139-140
	if total > feedbackMaxTotalBytes {                         // :141-144
		callback(feedbackResult{errorCode: "FEEDBACK_TOO_LARGE"})
		return
	}
	for _, p := range merged { // :146-148
		if err := feedbackWriteFilePart(mw, p); err != nil {
			callback(feedbackResult{errorCode: "NETWORK_ERROR"})
			return
		}
	}
	if err := mw.Close(); err != nil { // :150 结束边界
		callback(feedbackResult{errorCode: "NETWORK_ERROR"})
		return
	}

	req, err := http.NewRequest(http.MethodPost, urlStr, bytes.NewReader(body.Bytes())) // :152-156
	if err != nil {
		feedbackLog("warn", "feedback_submit_setup_error") // :168
		callback(feedbackResult{errorCode: "NETWORK_ERROR"})
		return
	}
	req.ContentLength = int64(body.Len())
	req.Header.Set("Content-Type", "multipart/form-data; boundary="+mw.Boundary()) // :154
	req.Header.Set("User-Agent", httpUserAgent)
	req.Header.Set("X-VoxLink-Version", appVersion)

	ctx, cancel := context.WithTimeout(context.Background(), feedbackSubmitTimeout) // :153 timeout(30min)
	defer cancel()
	req = req.WithContext(ctx)

	client := &http.Client{} // :158 HttpClient.newHttpClient()（无连接超时，整次由 30min 上限控制）
	go func() {
		resp, err := client.Do(req) // :159-160 sendAsync + BodyHandlers.ofString
		if err != nil {             // :161-166 exceptionally
			feedbackLog("warn", "feedback_submit_network_error")
			callback(feedbackResult{errorCode: "NETWORK_ERROR"})
			return
		}
		defer resp.Body.Close()
		data, _ := io.ReadAll(io.LimitReader(resp.Body, maxResponseLen)) // :159 ofString
		callback(feedbackParseResponse(resp.StatusCode, string(data)))
	}()
}

// feedbackParseResponse parseResponse(HttpResponse)（:173-201）。
// 只认统一信封：success 真即成功；否则取 error，retryAfter 从 details.retryAfter 取。
func feedbackParseResponse(status int, body string) feedbackResult {
	preview := body
	if len(preview) > feedbackResponseLogPreview { // :175-176
		preview = preview[:feedbackResponseLogPreview] + "…"
	}
	feedbackLog("info", fmt.Sprintf("feedback_response http=%d body=%s", status, preview))
	var root map[string]any
	if json.Unmarshal([]byte(body), &root) != nil || root == nil { // :178-181
		return feedbackResult{errorCode: "BAD_RESPONSE"}
	}
	if ok, isBool := root["success"].(bool); isBool && ok { // :184-186
		return feedbackOK()
	}
	code := "BAD_RESPONSE" // :188
	if v, ok := root["error"].(string); ok {
		code = v
	}
	retry := 0 // :189-195 details.retryAfter
	if d, ok := root["details"].(map[string]any); ok {
		if v, ok := d["retryAfter"].(float64); ok {
			retry = int(v)
		}
	}
	return feedbackResult{errorCode: code, retryAfterSec: retry}
}

// feedbackNormalizeURL 归一化服务器地址并拼出提交端点 URL；无效返回 ""（normalizeUrl，:203-224）。
// 逐步照抄：trim → 空则默认站 → 无 scheme 补 https:// → 去掉所有结尾 '/' → 接 "/?route=/feedback/submit"。
func feedbackNormalizeURL(serverURL string) string {
	urlStr := strings.TrimSpace(serverURL) // :205
	if urlStr == "" {                      // :206-208
		urlStr = feedbackDefaultServer
	}
	if !strings.HasPrefix(urlStr, "http://") && !strings.HasPrefix(urlStr, "https://") { // :210-212
		urlStr = "https://" + urlStr
	}
	for strings.HasSuffix(urlStr, "/") { // :214-216
		urlStr = urlStr[:len(urlStr)-1]
	}
	full := urlStr + "/?route=" + feedbackRoute // :219
	if !validFeedbackURL(full) {                // :220-223 URI.create 抛异常 → null
		return ""
	}
	return full
}

// ---- 客户端信息 ----

// feedbackBuildClientInfo 对应 FeedbackFormScreen.buildClientInfo（:445-460）：
// 模组给 gameVersion / loader / modVersion / nickname 四键。本 App 无 MC 账号与游戏版本，
// 沿用本端既有口径（ticketstore.go:230-234）：os / arch / version / modVersion，
// 服务端的 ticketClientTag（server-go/ticket.go:654-661）正是按 modVersion 等键拼来源标签。
func (a *App) feedbackBuildClientInfo() string {
	return fmt.Sprintf(`{"os":%q,"arch":%q,"version":%q,"modVersion":%q}`,
		runtime.GOOS, runtime.GOARCH, appVersion, appVersion)
}

// ---- 绑定方法（UI / CLI 同一个入口）----

// FeedbackSubmit 提交站内反馈：
// {description, files:[本地路径], includeLogs:bool} → {ok:true} 或错误信封。
// 界面侧的预检（空内容、超 500MB、限频倒计时）按 FeedbackFormSpec 由前端做，
// 这里兜第二次（模组同样是"界面预检 + 服务端硬限"，:25 注释原文）。
func (a *App) FeedbackSubmit(reqJSON string) string {
	var req struct {
		Description string   `json:"description"`
		Files       []string `json:"files"`
		IncludeLogs *bool    `json:"includeLogs"` // FeedbackFormScreen.java:61 includeLogs 默认 true
	}
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	desc := strings.TrimSpace(req.Description) // :407 descBox.getValue().trim()
	includeLogs := true
	if req.IncludeLogs != nil {
		includeLogs = *req.IncludeLogs
	}
	var logs []string
	if includeLogs { // :408 includeLogs ? defaultLogFiles(gameDir()) : null
		// "游戏目录"在本端的等价物 = 配置目录（logs/latest.log 就挂在它下面，
		// 与 logupload.go 的 logUploadLogPath 同一份文件）
		logs = feedbackDefaultLogFiles(filepath.Dir(a.settingsPath))
	}
	files, total := feedbackMergeFiles(req.Files, logs) // :409-410
	if desc == "" && len(files) == 0 {                  // :411-414 FEEDBACK_EMPTY 口径（界面键 voxlink.fb.err_empty）
		return wrapErr("FEEDBACK_EMPTY", "写点什么，或者附上日志吧")
	}
	if total > feedbackMaxTotalBytes { // :416-419（界面键 voxlink.fb.too_large）
		return wrapErr("FEEDBACK_TOO_LARGE", "附件合计 "+strconv.FormatFloat(float64(total)/(1<<20), 'f', 1, 64)+" MB，超过 500MB 上限")
	}

	done := make(chan feedbackResult, 1)
	feedbackSubmit(a.baseURL(), desc, a.feedbackBuildClientInfo(), files, nil, func(r feedbackResult) { done <- r })
	res := <-done // 绑定方法同步返回信封（模组是回调；本端 UI 走 wails 调用-返回，CLI 走一行 JSON）
	if res.success {
		return wrapOK(map[string]any{"ok": true})
	}
	if res.errorCode == "RATE_LIMITED" { // FeedbackFormScreen.java:432-435
		wait := res.retryAfterSec
		if wait <= 0 { // :433 retryAfterSec>0 ? retryAfterSec : 600
			wait = feedbackRateLimitDefaultSec
		}
		return wrapErr("RATE_LIMITED", "提交太频繁，请 "+strconv.Itoa(wait)+" 秒后再试")
	}
	return wrapErr(res.errorCode, "反馈提交失败（"+res.errorCode+"）") // :439 err_failed + "(" + errorCode + ")"
}

// ---- 界面规格（不实现，只给前端/后续接线的依据）----

// FeedbackFormSpec 是 ui/FeedbackFormScreen.java（473 行）的契约要点，按模组原值列出：
//
// 字段与默认值
//   - 描述多行输入（:85-87，占位键 voxlink.fb.desc_hint），高度预算 56、最小 40（:41-42）
//   - 附件列表 attachments（:60）；includeLogs 默认 **true**（:61）
//   - submitting / submitted / createdTicketId / rateLimitedUntilMs（:62-67）
//   - 布局预算：FIELD_W=220、MAX_TIP_EXAMPLES=3、FIXED_TIP_LINES=4、TIP_LINE_H=10、
//     STATUS_RESERVE=22、TITLE_Y=15、MAX_FILE_LINES=2（:40-49），
//     computeLayout 的"先削描述框、再削示例行、最后保住按钮"循环照 :125-148
//
// 交互
//   - 添加文件走系统原生多选框（:287-348）；取消返回空、不改状态
//   - 拖放加入附件（:362-390）：跳过目录，并**提前过滤不可读文件**（:374 打开一次验证）
//   - 清空按钮只清附件与状态串（:95-100）
//   - 提交前预检（:397-419）：限频窗内直接返回；trim 后空内容且无文件 → err_empty；
//     合计 > 500MB → too_large
//   - 提交中禁用按钮与 ESC/关闭（:115、:462-472）
//   - 结果处理（:425-442）：成功 → submitted=true、记下工单号、清空附件、清零限频窗；
//     RATE_LIMITED → rateLimitedUntilMs = now + max(retryAfterSec,600)*1000 并禁用按钮；
//     TICKET_TOO_LARGE → too_large；其余 → err_failed + "(" + errorCode + ")"
//   - 限频倒计时按 mm/ss 显示（:191-205，剩余向上取整：(leftMs+999)/1000）
//   - 附件展示最多 2 行文件名，超出显示 "+N 个文件"，下面一行是合计大小（:214-226），
//     大小格式 formatBytes：GB 两位小数 / MB 一位 / KB 一位 / B（:258-272）
//   - clientInfo 四键 gameVersion/loader/modVersion/nickname（:445-456）
//   - 服务端限频窗口 10 分钟（server-go/feedback.go:29 feedbackRateWindow=600），
//     与模组的默认 600 秒一致

// ---- 工具 ----

// feedbackWriteTextField 写一个 text/plain 表单字段（textPart，:226-235）。
// 用 CreatePart 而不是 WriteField：WriteField 不写 Content-Type 行，模组手拼的报文里有
// `Content-Type: text/plain; charset=utf-8`，这里显式建头才能逐字节对齐。
// 模组每个分节尾自带 CRLF（:234），Go 的 Writer 在下一个分节/结束边界前补同一个 CRLF，
// 所以这里写完 value 就返回，不能再补尾。
func feedbackWriteTextField(mw *multipart.Writer, name, value string) error {
	w, err := mw.CreatePart(feedbackPartHeader(
		"form-data; name=\""+name+"\"", "text/plain; charset=utf-8")) // :227-229
	if err != nil {
		return err
	}
	_, err = io.WriteString(w, value) // :231-233
	return err
}

// feedbackPartHeader 单个分节的头（Content-Disposition + Content-Type）。
// Go 的 CreatePart 按 key 字典序输出，恰好与模组手拼顺序一致（Disposition 先于 Type）。
func feedbackPartHeader(disposition, contentType string) textproto.MIMEHeader {
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", disposition)
	h.Set("Content-Type", contentType)
	return h
}

// feedbackWriteFilePart 写一个附件字段（filePart，:237-254）：字段名固定 attachments，
// 文件名把 '"' 换成 "'"（:238，防破坏 Content-Disposition 头），类型 application/octet-stream。
func feedbackWriteFilePart(mw *multipart.Writer, path string) error {
	name := strings.ReplaceAll(filepath.Base(path), "\"", "'") // :238
	w, err := mw.CreatePart(feedbackPartHeader(
		"form-data; name=\"attachments\"; filename=\""+name+"\"", "application/octet-stream")) // :240-242
	if err != nil {
		return err
	}
	f, err := os.Open(path) // :244-247：ofFile 失败时模组塞空字节（分节仍然写，正文 0 字节）
	if err != nil {
		return nil
	}
	defer f.Close()
	_, err = io.Copy(w, f) // 流式：不整块进内存（:24 注释原文）
	return err
}

// feedbackRandomHex32 boundary 后缀：模组用 UUID 去横线（:133，32 位 hex）。
func feedbackRandomHex32() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(b)
}

func validFeedbackURL(s string) bool {
	// 模组 :219-223 用 URI.create 的异常表达"无效"；Go 的 url.Parse 宽松得多，
	// 这里补一道最小可用性判定（有 scheme + host），避免把明显坏地址发出去。
	i := strings.Index(s, "://")
	if i <= 0 {
		return false
	}
	rest := s[i+3:]
	if j := strings.IndexAny(rest, "/?#"); j >= 0 {
		rest = rest[:j]
	}
	return rest != "" && !strings.ContainsAny(rest, " \t\n")
}

// feedbackLogSink 由 App 启动时注入（hunk 见交付报告）。
var feedbackLogSink func(level, msg string)

func feedbackLog(level, msg string) {
	if feedbackLogSink != nil {
		feedbackLogSink(level, msg)
		return
	}
	log.Printf("[%s] %s", level, sanitizeNetLogMessage(msg))
}
