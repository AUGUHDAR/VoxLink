package main

// modsync_modrinth.go — Modrinth API v2 最小客户端 + CDN 下载校验，逐句对应
// `modsync/ModrinthClient.java`（687 行）。除 UA 里的版本号外零 MC 依赖
// （Java 侧唯一耦合是 voxlinkVersion()，ModrinthClient.java:54-60）。
//
// 常量表（值与 Java 逐字节一致，位置见 file:line）：
//
//	API              https://api.modrinth.com/v2            ModrinthClient.java:33
//	MIRROR_API       https://mod.mcimirror.top/modrinth     :35
//	MIRROR_PREFIX    https://mod.mcimirror.top              :36
//	MIRROR_TIMEOUT   8s                                     :37
//	USER_AGENT       "VoxLink/<版本> (modsync)"             :38
//	CHUNK            64                                     :39
//	TIMEOUT          20s                                    :40
//	HEADER_TIMEOUT   15000ms                                :42
//	MAX_DOWNLOAD     512MiB                                 :43
//	MAX_ATTEMPTS     2（尝试0=镜像，尝试1=官方）            :45
//	BACKOFF          1s / 3s                                :47-48
//	connectTimeout   10s                                    :70
//
// 1.2.0 的双源纪律（"MCIM 镜像短超时优先、官方兜底"）：
//   - apiUrls() 返回 [镜像, 官方]，sendAny 依次尝试（:183-186, 523-573）
//   - 429 → sleep(min(3, Retry-After)) 秒后换源（:535-541, 575-586）
//   - 镜像层 4xx 不作数，仅"最后一源的 4xx"才是永久失败（:544-550）
//   - 批量分块失败只损失该块，绝不做废整张清单（:96-99, 137-141）—— 生产事故 MXZD5N：
//     一次 HTTP 超时曾让 121 个 jar 的清单整体报废。

import (
	"context"
	"crypto/sha1"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	modrinthAPI = "https://api.modrinth.com/v2"
	// 镜像必须带 /v2：MCIM 的映射是 /modrinth/v2/* → api.modrinth.com/v2/*，
	// 少了 /v2 全部 404（实测 /modrinth/project/... = 404，/modrinth/v2/project/... = 200），
	// 于是"镜像优先"形同虚设，国内每次 API 调用都白等一轮再掉到官方。
	modrinthMirrorAPI    = "https://mod.mcimirror.top/modrinth/v2"
	modrinthMirrorPrefix = "https://mod.mcimirror.top"

	modrinthMirrorTimeout  = 8 * time.Second  // ModrinthClient.java:37
	modrinthTimeout        = 20 * time.Second // :40
	modrinthConnectTimeout = 10 * time.Second // :70
	modrinthHeaderTimeout  = 15 * time.Second // :42（15000ms）

	modrinthChunk        = 64                       // :39
	modrinthMaxDownload  = int64(512) * 1024 * 1024 // :43
	modrinthMaxAttempts  = 2                        // :45
	modrinthBackoffFirst = 1 * time.Second          // :47
	modrinthBackoffLate  = 3 * time.Second          // :48
)

// modrinthUserAgent：ModrinthClient.java:38 的 "VoxLink/" + voxlinkVersion() + " (modsync)"。
// 版本号取本应用版本（appVersion，app.go 定义），产品标识格式保持不变。
func modrinthUserAgent() string { return "VoxLink/" + appVersion + " (modsync)" }

// modrinthPermanentError：ModrinthClient.PermanentHttpException（:441-446）——
// 4xx（除 429）等"不要重试"的永久失败。
type modrinthPermanentError struct{ msg string }

func (e *modrinthPermanentError) Error() string { return e.msg }

// modrinthAbortedError：ModrinthClient.AbortedException（:435-439）——
// 玩家主动取消，调用方按"静默回到选择界面"处理，不当失败刷红。
type modrinthAbortedError struct{ name string }

func (e *modrinthAbortedError) Error() string { return "aborted: " + e.name }

var (
	modrinthHTTPOnce sync.Once
	modrinthHTTPCli  *http.Client
)

// modrinthClient 对应 client()（:62-79）：connectTimeout 10s + FOLLOW 重定向
// （CDN 部分文件会 307 跳转，不跟随会被当成下载失败）。
// Go 的默认 Client 已跟随重定向（上限 10 次）且禁止 https→http 降级，
// 与 Java Redirect.NORMAL 同义；这里只把 connect 超时对齐到 10s。
// 请求级超时由 context 控制（Java 用 HttpRequest.timeout）。
func modrinthClient() *http.Client {
	modrinthHTTPOnce.Do(func() {
		modrinthHTTPCli = &http.Client{
			Timeout: 0, // 不设整体超时：API 用 ctx，下载体需要长流
			Transport: &http.Transport{
				DialContext:         (&net.Dialer{Timeout: modrinthConnectTimeout, KeepAlive: 30 * time.Second}).DialContext,
				TLSHandshakeTimeout: modrinthConnectTimeout,
			},
		}
	})
	return modrinthHTTPCli
}

// modrinthAPIURLs：apiUrls(pathAndQuery)（:183-186）——镜像在前、官方在后。
func modrinthAPIURLs(pathAndQuery string) []string {
	return []string{modrinthMirrorAPI + pathAndQuery, modrinthAPI + pathAndQuery}
}

// versionsFromSha1：sha1 → version JSON；MR 不认识的哈希在返回 map 中缺席（:82-113）。
// 关键：单块失败只损失该块（调用方让它们落 unknownMods），不作废整张清单。
func modrinthVersionsFromSha1(sha1List []string) map[string]map[string]any {
	out := map[string]map[string]any{}
	for from := 0; from < len(sha1List); from += modrinthChunk {
		end := from + modrinthChunk
		if end > len(sha1List) {
			end = len(sha1List)
		}
		chunk := sha1List[from:end]

		body := map[string]any{
			"hashes":    toAnySlice(chunk),
			"algorithm": "sha1",
		}
		payload, err := json.Marshal(body)
		if err != nil {
			modSyncLogWarn("version_files chunk failed ({} hashes): {}", len(chunk), err.Error()) // ModrinthClient.java:98
			continue
		}
		resp, err := modrinthPostJSON(modrinthAPIURLs("/version_files"), string(payload))
		if err != nil {
			// :96-99：分块网络失败只损失该块
			modSyncLogWarn("version_files chunk failed ({} hashes): {}", len(chunk), err.Error()) // ModrinthClient.java:98
			continue
		}
		if resp == nil {
			continue
		}
		for k, v := range resp {
			if o, ok := v.(map[string]any); ok {
				out[k] = o
			}
		}
	}
	return out
}

// projectsByIds：projectId → project JSON（:116-154）。
// 分块失败降级（:137-141）：元数据缺失由调用方 fail-open 处理，不作废整批。
//
// 修复记录（必须保留）：1.1.2 二轮曾只查依赖 ids、漏掉自有 mod 的 project_id，
// 导致根集恒空、必装清单永远是 0（生产日志 "0 required entries"）——
// 调用方 ModSyncManifestService.java:268-273 传的是 "自有 + 依赖" 全集。
func modrinthProjectsByIds(ids []string) map[string]map[string]any {
	out := map[string]map[string]any{}
	for from := 0; from < len(ids); from += modrinthChunk {
		end := from + modrinthChunk
		if end > len(ids) {
			end = len(ids)
		}
		chunk := ids[from:end]

		// Gson 在 Java 侧负责转义；这里交给 encoding/json，产出同样的 ["id","id"] 形态。
		idsJSON, err := json.Marshal(chunk)
		if err != nil {
			modSyncLogWarn("projects chunk encode failed ({} ids): {}", len(chunk), err.Error())
			continue
		}

		arr, err := modrinthGetJSONArray(modrinthAPIURLs("/projects?ids=" + url.QueryEscape(string(idsJSON))))
		if err != nil {
			modSyncLogWarn("projects chunk failed ({} ids): {}", len(chunk), err.Error()) // ModrinthClient.java:139
			continue
		}
		if arr == nil {
			continue
		}
		for _, el := range arr {
			if o, ok := el.(map[string]any); ok {
				if id := jstr(o, "id"); id != "" {
					out[id] = o
				}
			}
		}
	}
	return out
}

// latestVersionFor：指定项目在本端 MC 版本 + 加载器下的最新版本；无兼容版本返回 nil（:157-162）。
func modrinthLatestVersionFor(projectID, gameVersion, loader string) (map[string]any, error) {
	gv := url.QueryEscape(`["` + modrinthEscape(gameVersion) + `"]`)
	ld := url.QueryEscape(`["` + modrinthEscape(loader) + `"]`)
	arr, err := modrinthGetJSONArray(modrinthAPIURLs("/project/" + modrinthEscape(projectID) +
		"/version?game_versions=" + gv + "&loaders=" + ld))
	if err != nil {
		return nil, err
	}
	if len(arr) > 0 {
		if o, ok := arr[0].(map[string]any); ok {
			return o, nil
		}
	}
	return nil, nil
}

// search：GET /v2/search，名称 + 加载器搜项目；gameVersion 非空时加版本 facet（:165-176）。
func modrinthSearch(query, loader, gameVersion string) ([]any, error) {
	// Java 逐字： "[[\"project_type:mod\"],[\"categories:" + loader + "\"]"
	//            + （版本非空） ",[\"versions:" + gv + "\"]"
	//            + "]"
	facets := `[["project_type:mod"],["categories:` + modrinthEscape(loader) + `"]`
	if strings.TrimSpace(gameVersion) != "" {
		facets += `,["versions:` + modrinthEscape(gameVersion) + `"]`
	}
	facets += "]"
	o, err := modrinthGetJSONObject(modrinthAPIURLs("/search?query="+url.QueryEscape(query)+
		"&facets="+url.QueryEscape(facets)+"&limit=5"), false)
	if err != nil {
		return nil, err
	}
	if hits := jarr(o, "hits"); hits != nil {
		return hits, nil
	}
	return []any{}, nil
}

// project：GET /v2/project/{id|slug}；项目不存在(404)返回 nil（:179-181）。
func modrinthProject(idOrSlug string) (map[string]any, error) {
	return modrinthGetJSONObject(modrinthAPIURLs("/project/"+url.QueryEscape(idOrSlug)), true)
}

// primaryFile：从 version JSON 取 primary 文件对象；无 primary 时取第一个（:589-612）。
func modrinthPrimaryFile(version map[string]any) map[string]any {
	files := jarr(version, "files")
	if files == nil {
		return nil
	}
	var first map[string]any
	for _, el := range files {
		f, ok := el.(map[string]any)
		if !ok {
			continue
		}
		if jbool(f, "primary") {
			return f
		}
		if first == nil {
			first = f
		}
	}
	return first
}

// isServerOnly：项目是否"纯服务端"模组（房客无需安装）（:454-474）。
func modrinthIsServerOnly(project map[string]any) bool {
	if project == nil {
		return false
	}
	if v, ok := project["client_side"]; ok && v != nil {
		// required/optional 都可能被房主世界用到；仅明确不支持客户端的才跳过
		return strings.EqualFold(jstr(project, "client_side"), "unsupported")
	}
	for _, el := range jarr(project, "environment") {
		v := ""
		if s, ok := el.(string); ok {
			v = s
		}
		if strings.EqualFold(v, "server_only") {
			return true
		}
	}
	return false
}

// distinctProjectIds：versions 去重后的 project_id 集合，保持首次出现顺序（:614-623）。
func modrinthDistinctProjectIDs(versions []map[string]any) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, v := range versions {
		pid, ok := v["project_id"]
		if !ok || pid == nil {
			continue
		}
		s := jstr(v, "project_id")
		if seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

// distinctProjectIds：versions 去重后的 project_id 集合，保持首次出现顺序（:614-623）。

// sendAny 对应 ModrinthClient.sendAny（:523-573）：
// 镜像短超时在前，官方兜底在后；429 小退避换源；4xx/5xx/网络失败换下一源，
// 仅最后一源的 4xx 判永久失败。返回响应体字节。
// method/body 对应 Java 的 UnaryOperator<HttpRequest.Builder>（GET 或 POST ofString）。
func modrinthSendAny(urls []string, label, method, body string) (int, http.Header, []byte, error) {
	var lastErr error
	for i, u := range urls {
		isLast := i == len(urls)-1
		mirror := strings.HasPrefix(u, modrinthMirrorPrefix)
		timeout := modrinthTimeout
		if mirror {
			timeout = modrinthMirrorTimeout
		}

		var rd io.Reader
		if body != "" {
			rd = strings.NewReader(body)
		}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		req, err := http.NewRequestWithContext(ctx, method, u, rd)
		if err != nil {
			cancel()
			lastErr = err
			if isLast {
				return 0, nil, nil, lastErr
			}
			continue
		}
		req.Header.Set("User-Agent", modrinthUserAgent())
		if method == "POST" {
			req.Header.Set("Content-Type", "application/json")
		}
		resp, err := modrinthClient().Do(req)
		if err != nil {
			cancel()
			// 网络层失败：换下一源
			lastErr = err
			if isLast {
				return 0, nil, nil, err
			}
			continue
		}
		data, rerr := io.ReadAll(resp.Body)
		resp.Body.Close()
		cancel()
		if rerr != nil {
			lastErr = rerr
			if isLast {
				return 0, nil, nil, rerr
			}
			continue
		}

		code := resp.StatusCode
		if code == http.StatusTooManyRequests {
			if !isLast {
				// :537：sleep(min(3, Retry-After)) 秒后换源
				time.Sleep(time.Duration(min64(3, parseRetryAfter(resp))) * time.Second)
				continue
			}
			return 0, nil, nil, fmt.Errorf("%s HTTP 429 (exhausted)", label)
		}
		if code >= 400 && code < 500 {
			// :544-550：镜像层 4xx 不作数，交给官方端点判定
			if !isLast {
				continue
			}
			return code, resp.Header, data, &modrinthPermanentError{msg: fmt.Sprintf("%s HTTP %d", label, code)}
		}
		if code >= 500 && code < 600 {
			if !isLast {
				continue
			}
			return code, resp.Header, data, fmt.Errorf("%s HTTP %d", label, code)
		}
		return code, resp.Header, data, nil
	}
	if lastErr != nil {
		return 0, nil, nil, lastErr
	}
	return 0, nil, nil, fmt.Errorf("%s: no endpoint answered", label)
}

// parseRetryAfter：Retry-After 头 → 秒；解析失败/缺失按 3（:575-586）。
func parseRetryAfter(resp *http.Response) int64 {
	v := strings.TrimSpace(resp.Header.Get("Retry-After"))
	if v == "" {
		return 3
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return 3
	}
	return n
}

func modrinthPostJSON(urls []string, jsonBody string) (map[string]any, error) {
	code, _, data, err := modrinthSendAny(urls, "Modrinth POST", "POST", jsonBody)
	if err != nil {
		return nil, err
	}
	if code < 200 || code > 299 {
		return nil, fmt.Errorf("Modrinth POST failed: HTTP %d", code)
	}
	var el any
	if err := json.Unmarshal(data, &el); err != nil {
		return nil, nil // Gson parseString 的非对象结果 → postJson 返回 null（:484-485）
	}
	if o, ok := el.(map[string]any); ok {
		return o, nil
	}
	return nil, nil
}

func modrinthGetJSONArray(urls []string) ([]any, error) {
	code, _, data, err := modrinthSendAny(urls, "Modrinth GET", "GET", "")
	if err != nil {
		return nil, err
	}
	if code < 200 || code > 299 {
		return nil, fmt.Errorf("Modrinth GET failed: HTTP %d", code)
	}
	var el any
	if err := json.Unmarshal(data, &el); err != nil {
		return nil, nil
	}
	if a, ok := el.([]any); ok {
		return a, nil
	}
	return nil, nil
}

// getJsonObject 对应 ModrinthClient.getJsonObject(urls, nullOn404)（:498-517）：
// 404 且 nullOn404 → nil（永久失败消息固定为 "Modrinth GET HTTP <code>"）。
func modrinthGetJSONObject(urls []string, nullOn404 bool) (map[string]any, error) {
	code, _, data, err := modrinthSendAny(urls, "Modrinth GET", "GET", "")
	if err != nil {
		var pe *modrinthPermanentError
		if nullOn404 && errors.As(err, &pe) && strings.HasSuffix(pe.Error(), "404") {
			return nil, nil
		}
		return nil, err
	}
	if code < 200 || code > 299 {
		return nil, fmt.Errorf("Modrinth GET failed: HTTP %d", code)
	}
	var el any
	if err := json.Unmarshal(data, &el); err != nil {
		return nil, nil
	}
	if o, ok := el.(map[string]any); ok {
		return o, nil
	}
	return nil, nil
}

// ---- 下载 ----

// modrinthDownloadVerified 对应 downloadVerified(...)（:199-252）：
// 最多 MAX_ATTEMPTS 次尝试，尝试 0 走 MCIM 镜像 CDN、尝试 1 回官方直链；
// 尝试间退避 1s / 3s；全部失败时错误串前缀 "MODRINTH:"。
// expectedSha512 非空必须匹配；缺失时回退 sha1；都缺失则放行并 warn（:400-417）。
// shouldAbort 返回 true 时抛 *modrinthAbortedError（取消逻辑不被破坏）。
func modrinthDownloadVerified(rawURL, expectedSha512, expectedSha1, fileName, modsDir string,
	shouldAbort func() bool) (int64, error) {
	if err := modrinthEnsureSafeFileName(fileName); err != nil {
		return 0, err
	}

	var errorsJoined []string
	cli := modrinthClient()

	for i := 0; i < modrinthMaxAttempts; i++ {
		if shouldAbort != nil && shouldAbort() {
			return 0, &modrinthAbortedError{name: fileName}
		}

		// 尝试 0 走 MCIM 镜像（国内快），尝试 1 回官方 CDN（:211-212）
		attemptURL := rawURL
		if i == 0 {
			attemptURL = modrinthMirrorDownloadURL(rawURL)
		}

		bytes, err := modrinthDoDownload(cli, attemptURL, expectedSha512, expectedSha1, fileName, modsDir, shouldAbort)
		if err == nil {
			return bytes, nil
		}
		var aborted *modrinthAbortedError
		if errors.As(err, &aborted) {
			return 0, err // 用户取消，立即上抛，不重试（:216-217）
		}
		var perm *modrinthPermanentError
		if errors.As(err, &perm) {
			// :218-224：镜像层 4xx 不作数；还有官方尝试就换源，官方 4xx 才是永久失败
			if i < modrinthMaxAttempts-1 && attemptURL != rawURL {
				continue
			}
			return 0, err
		}
		if shouldAbort != nil && shouldAbort() {
			return 0, &modrinthAbortedError{name: fileName}
		}
		errorsJoined = append(errorsJoined, err.Error())

		// 尝试间退避：第 1 次后 1s，第 2 次后 3s（:235-246）
		if i < modrinthMaxAttempts-1 {
			backoff := modrinthBackoffFirst
			if i != 0 {
				backoff = modrinthBackoffLate
			}
			time.Sleep(backoff)
		}
	}

	msg := "all attempts failed"
	if len(errorsJoined) > 0 {
		msg = strings.Join(errorsJoined, " | ")
	}
	return 0, errors.New("MODRINTH: " + msg)
}

// modrinthDoDownload 对应 doDownload(...)（:268-432）：单次尝试。
// 先写 .part，双哈希流式计算，校验通过后原子改名。
// 取消：响应头阶段 200ms 轮询 shouldAbort（:280-300），
// 阻塞读体阶段 150ms 取消监视（:341-359，Java 靠 future.cancel(true) 关连接，
// Go 靠 cancel context 达到同一效果：阻塞中的 Read 立刻返回错误）。
func modrinthDoDownload(cli *http.Client, rawURL, expectedSha512, expectedSha1, fileName, modsDir string,
	shouldAbort func() bool) (int64, error) {
	if err := modrinthEnsureSafeFileName(fileName); err != nil {
		return 0, err
	}
	if _, err := url.Parse(rawURL); err != nil {
		return 0, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 阶段 1：响应头等待 —— Java 有两道闸：HttpRequest.timeout(20s)（:274）与
	// HEADER_TIMEOUT_MS=15000 的轮询截止（:281-297），先命中者生效。
	req, err := http.NewRequestWithContext(ctx, "GET", rawURL, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", modrinthUserAgent())

	// HttpRequest.timeout(TIMEOUT) 的等价看门狗（:274）
	watchdog := time.AfterFunc(modrinthTimeout, func() { cancel() })
	defer watchdog.Stop()

	var headersArrived atomic.Bool
	var abortByUser atomic.Bool
	var abortByDeadline atomic.Bool

	// shouldAbort 轮询 + 15s 头截止（200ms 一查，同 Java 的 future.get(200ms) 循环，
	// :281-297）；Java 每轮先查 shouldAbort、再 get(200ms)、最后比对 deadline。
	done := make(chan struct{})
	defer close(done)
	go func() {
		tick := time.NewTicker(200 * time.Millisecond)
		defer tick.Stop()
		deadline := time.Now().Add(modrinthHeaderTimeout)
		for {
			select {
			case <-done:
				return
			case <-ctx.Done():
				return
			case <-tick.C:
			}
			if headersArrived.Load() {
				return
			}
			if shouldAbort != nil && shouldAbort() {
				abortByUser.Store(true)
				cancel()
				return
			}
			if headersArrived.Load() {
				return
			}
			if time.Now().After(deadline) {
				abortByDeadline.Store(true)
				cancel()
				return
			}
		}
	}()

	resp, err := cli.Do(req)
	headersArrived.Store(true)
	// 看门狗只管到"响应头到达"：Java 的 HttpRequest.timeout(20s) 约束的是响应，
	// 不约束响应体流。此前不在此处停表，任何 20s 没下完的文件都会被自己取消
	// （实测 fabric-api 走 CDN 42s 报 "context canceled"，ModSync 永远装不完大模组）。
	watchdog.Stop()
	if err != nil {
		if abortByUser.Load() {
			return 0, &modrinthAbortedError{name: fileName}
		}
		if abortByDeadline.Load() {
			return 0, fmt.Errorf("HTTP headers timeout for %s", fileName)
		}
		return 0, err
	}

	if resp.StatusCode == http.StatusTooManyRequests {
		// :302-305：429 留给外层重试（按 Retry-After 退避），不上抛 permanent
		resp.Body.Close()
		return 0, fmt.Errorf("HTTP 429 for %s", fileName)
	}
	if (resp.StatusCode >= 400 && resp.StatusCode < 500) || resp.Body == nil {
		// :307-310：4xx（除 429）永久失败，调用方不应重试
		resp.Body.Close()
		return 0, &modrinthPermanentError{msg: fmt.Sprintf("HTTP %d for %s", resp.StatusCode, fileName)}
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		resp.Body.Close()
		return 0, fmt.Errorf("HTTP %d for %s", resp.StatusCode, fileName)
	}

	// Content-Length 预检：声明超限就拒绝写盘，连 .part 都不创建（:316-327）
	if cl := resp.Header.Get("Content-Length"); cl != "" {
		if declared, cerr := strconv.ParseInt(strings.TrimSpace(cl), 10, 64); cerr == nil {
			if declared > modrinthMaxDownload {
				resp.Body.Close()
				return 0, fmt.Errorf("declared Content-Length %d exceeds limit for %s", declared, fileName)
			}
		}
	}

	target := filepath.Join(modsDir, fileName)
	part := filepath.Join(modsDir, fileName+".part")
	sha512h := sha512.New()
	sha1h := sha1.New()

	// 体阶段取消监视：150ms 一查（:341-359）
	bodyDone := make(chan struct{})
	go func() {
		tick := time.NewTicker(150 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-bodyDone:
				return
			case <-tick.C:
			}
			if shouldAbort != nil && shouldAbort() {
				cancel()
				return
			}
		}
	}()

	var total int64
	var ioFailure error
	func() {
		defer resp.Body.Close()
		out, oerr := os.Create(part)
		if oerr != nil {
			ioFailure = oerr
			return
		}
		defer out.Close()
		buf := make([]byte, modSyncHashBuf)
		for {
			n, rerr := resp.Body.Read(buf)
			if n > 0 {
				total += int64(n)
				if total > modrinthMaxDownload {
					cancel()
					ioFailure = fmt.Errorf("file too large: %s", fileName)
					break
				}
				sha512h.Write(buf[:n])
				sha1h.Write(buf[:n])
				if _, werr := out.Write(buf[:n]); werr != nil {
					ioFailure = werr
					break
				}
			}
			if rerr == io.EOF {
				break
			}
			if rerr != nil {
				ioFailure = rerr
				break
			}
		}
	}()
	close(bodyDone)

	abortedFlag := false
	if ioFailure != nil {
		if shouldAbort != nil && shouldAbort() {
			abortedFlag = true
		}
	}
	if abortedFlag || (shouldAbort != nil && shouldAbort()) {
		modrinthQuietDelete(part)
		return 0, &modrinthAbortedError{name: fileName}
	}
	if ioFailure != nil {
		modrinthQuietDelete(part)
		return 0, ioFailure
	}

	actualSha512 := hex.EncodeToString(sha512h.Sum(nil))
	actualSha1 := hex.EncodeToString(sha1h.Sum(nil))
	sha512Wanted := strings.TrimSpace(expectedSha512) != ""
	sha1Wanted := strings.TrimSpace(expectedSha1) != ""
	switch {
	case sha512Wanted:
		if !strings.EqualFold(actualSha512, expectedSha512) {
			modrinthQuietDelete(part)
			return 0, fmt.Errorf("sha512 mismatch: %s", fileName)
		}
	case sha1Wanted:
		if !strings.EqualFold(actualSha1, expectedSha1) {
			modrinthQuietDelete(part)
			return 0, fmt.Errorf("sha1 mismatch: %s", fileName)
		}
	default:
		modSyncLogWarn("no expected hash for {}; skipping verification", fileName) // ModrinthClient.java:416
	}

	// 原子改名（:419-429）：Go 的 os.Rename 在 POSIX 上是原子替换；Windows 上
	// MoveFileEx(REPLACE_EXISTING) 同卷原子。跨卷失败 → 清 .part 后报错，
	// 与 Java 的 AtomicMoveNotSupportedException→普通 move→仍失败即清理等价。
	if rerr := os.Rename(part, target); rerr != nil {
		modrinthQuietDelete(part)
		return 0, rerr
	}
	return total, nil
}

// modrinthMirrorDownloadURL：MR CDN / forgecdn → MCIM 镜像；其余原样返回（:626-648）。
func modrinthMirrorDownloadURL(rawURL string) string {
	if rawURL == "" {
		return rawURL
	}
	if strings.HasPrefix(rawURL, "https://cdn.modrinth.com/") {
		return modrinthMirrorPrefix + "/" + strings.TrimPrefix(rawURL, "https://cdn.modrinth.com/")
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return rawURL
	}
	host := u.Hostname()
	cfCdn := host == "edge.forgecdn.net" || host == "mediafilez.forgecdn.net" ||
		host == "media.forgecdn.net" || strings.HasSuffix(host, "-service.overwolf.wtf")
	if cfCdn {
		out := modrinthMirrorPrefix + u.Path
		if u.RawQuery != "" {
			out += "?" + u.RawQuery
		}
		return out
	}
	return rawURL
}

// modrinthEscape：ModrinthClient.escape(s)（:650-652）—— 反斜杠与双引号加转义，
// 供拼 facet/查询用的 JSON 字面量使用。
func modrinthEscape(s string) string {
	if s == "" {
		return ""
	}
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	return s
}

// modrinthQuietDelete：Files.deleteIfExists 忽略异常（:663-668）。
func modrinthQuietDelete(p string) {
	if p == "" {
		return
	}
	_ = os.Remove(p)
}

// modrinthEnsureSafeFileName：防御性 fileName 消毒（:671-686）——
// 拒空、拒 `..` `/` `\` `:`、拒控制字符（<0x20 或 0x7F）。
func modrinthEnsureSafeFileName(fileName string) error {
	if strings.TrimSpace(fileName) == "" {
		return errors.New("unsafe fileName: empty")
	}
	if strings.Contains(fileName, "..") || strings.Contains(fileName, "/") ||
		strings.Contains(fileName, "\\") || strings.Contains(fileName, ":") {
		return fmt.Errorf("unsafe fileName: %s", fileName)
	}
	for _, r := range fileName {
		if r < 0x20 || r == 0x7F {
			return fmt.Errorf("unsafe fileName (control char): %s", fileName)
		}
	}
	return nil
}

// modSyncSafeFileName 对应 ModSyncSelectScreen.isSafeFileName(fileName, modsDirNorm)
// （:718-749）：下载环在取用条目前的额外一道防目录逃逸（含解析后必须落在 modsDir 内）。
func modSyncSafeFileName(fileName, modsDir string) bool {
	if fileName == "" {
		return false
	}
	if strings.Contains(fileName, "..") {
		return false
	}
	if strings.Contains(fileName, "/") || strings.Contains(fileName, "\\") || strings.Contains(fileName, ":") {
		return false
	}
	for _, r := range fileName {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	norm, err := filepath.Abs(modsDir)
	if err != nil {
		return false
	}
	resolved := filepath.Clean(filepath.Join(norm, fileName))
	return strings.EqualFold(resolved, norm) || strings.HasPrefix(strings.ToLower(resolved), strings.ToLower(norm)+string(os.PathSeparator))
}

func modSyncToAnySlice(in []string) []any {
	out := make([]any, 0, len(in))
	for _, s := range in {
		out = append(out, s)
	}
	return out
}

func min64(a, b int64) int64 {
	if b < a {
		return b
	}
	return a
}
