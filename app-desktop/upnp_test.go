package main

// upnp_test.go — UPnP 移植自检。
//
// 覆盖面（能在没有真路由的环境里验的部分）：
//   - 常量与模组逐条对齐
//   - 三个 SOAP 请求体的**字节级**一致性（这是路由器是否接受映射的全部根据）
//   - SSDP 报文头抽取、设备描述 XML 的索引式截取（含 WANPPPConnection 与命名空间兜底）
//   - AddPortMapping 的成功/ errorCode / HTTP>=400 三条判定分支（用 loopback httptest 冒充网关）
//   - 续租任务体：网关丢失跳过、续租失败摘掉映射、closePort 的 removeIf 不 cancel（模组既有语义）
//   - 本模块所有日志字面量的消敏自检（与 netlog_sanitize_test.go 同一套正则）
//
// 覆盖不到的部分（必须真路由/真网络，见交付报告）：
//   - SSDP M-SEARCH 能否被 IGD 应答、组播 TTL、多网卡择优
//   - 路由器对 NewLeaseDuration=0 与 3600 的实际处理、50 分钟续租的真实效果

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// resetUPnPState 把包级状态恢复原样（测试之间互不污染）。
func resetUPnPState(t *testing.T) {
	t.Helper()
	upnpMu.Lock()
	upnpCachedGW = nil
	upnpGWCacheMs = 0
	upnpCachedLocal = ""
	upnpLocalCacheMs = 0
	upnpMappedPorts = map[string]bool{}
	upnpRenewTasks = map[string]int64{}
	upnpStopped = false
	upnpMu.Unlock()
	upnpStartMu.Lock()
	upnpStartDone = false
	upnpStartMu.Unlock()
}

func seedUPnpGateway(base, control, svc string) {
	upnpMu.Lock()
	upnpCachedGW = &upnpGateway{baseURL: base, controlU: control, svcType: svc}
	upnpGWCacheMs = time.Now().UnixMilli()
	upnpCachedLocal = "192.0.2.10" // RFC5737 文档地址：只进 SOAP 请求体，不进任何日志
	upnpLocalCacheMs = time.Now().UnixMilli()
	upnpMu.Unlock()
}

// TestUPnPConstants 关键常量与 UPnPManager.java 对齐。
func TestUPnPConstants(t *testing.T) {
	cases := []struct {
		name string
		got  any
		want any
	}{
		{"SSDP_ADDR", upnpSSDPAddr, "239.255.255.250"},                 // :34
		{"SSDP_PORT", upnpSSDPPort, 1900},                              // :35
		{"SEARCH_TIMEOUT", upnpSearchTimeout, 1500 * time.Millisecond}, // :36
		{"HTTP_TIMEOUT_MS", upnpHTTPTimeout, 3000 * time.Millisecond},  // :37
		{"CACHE_DURATION", upnpCacheDuration, 60 * time.Second},        // :41
		{"RENEW_LEAD_MS", upnpRenewLead, 50 * time.Minute},             // :48
		{"租约秒数", upnpLeaseSeconds, 3600},                               // :160/:204
		{"SSDP 缓冲", upnpSSDPBufSize, 4096},                             // :337
		{"join 超时", upnpJoinTimeout, 2000 * time.Millisecond},          // :394
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s = %v, want %v", c.name, c.got, c.want)
		}
	}
	if len(upnpSearchMessages) != 2 { // :305-308
		t.Fatalf("M-SEARCH 报文条数应为 2，实为 %d", len(upnpSearchMessages))
	}
	if !strings.Contains(upnpSearchMessages[0], "urn:schemas-upnp-org:device:InternetGatewayDevice:1") ||
		!strings.Contains(upnpSearchMessages[1], "urn:schemas-upnp-org:service:WANIPConnection:1") {
		t.Error("M-SEARCH 的 ST 顺序或内容不符（模组先 device 后 service）")
	}
	for _, m := range upnpSearchMessages {
		if !strings.HasSuffix(m, "\r\n\r\n") || !strings.Contains(m, "MX: 3") || !strings.Contains(m, `MAN: "ssdp:discover"`) {
			t.Errorf("M-SEARCH 报文尾部/MX/MAN 不符: %q", m)
		}
	}
}

// TestUPnPBuildAddPortMappingByteExact 与模组字符串拼接结果逐字节比（:510-525）。
func TestUPnPBuildAddPortMappingByteExact(t *testing.T) {
	got := upnpBuildAddPortMapping(25565, "192.0.2.10", "VoxLink-Test", "TCP")
	want := "<?xml version=\"1.0\"?>\n" +
		"<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
		"<s:Body><m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>25565</NewExternalPort>" +
		"<NewProtocol>TCP</NewProtocol><NewInternalPort>25565</NewInternalPort><NewInternalClient>192.0.2.10</NewInternalClient>" +
		"<NewEnabled>1</NewEnabled><NewPortMappingDescription>VoxLink-Test</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>" +
		"</m:AddPortMapping></s:Body></s:Envelope>"
	if got != want {
		t.Errorf("AddPortMapping 报文不一致\ngot:  %s\nwant: %s", got, want)
	}
}

// TestUPnPBuildAddPortMappingLeaseByteExact 带租约版（:527-544）：外部/内部端口可不同。
func TestUPnPBuildAddPortMappingLeaseByteExact(t *testing.T) {
	got := upnpBuildAddPortMappingLease(51600, 51601, "192.0.2.10", "VoxLink", "UDP", upnpLeaseSeconds)
	want := "<?xml version=\"1.0\"?>\n" +
		"<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
		"<s:Body><m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>51601</NewExternalPort>" +
		"<NewProtocol>UDP</NewProtocol><NewInternalPort>51600</NewInternalPort><NewInternalClient>192.0.2.10</NewInternalClient>" +
		"<NewEnabled>1</NewEnabled><NewPortMappingDescription>VoxLink</NewPortMappingDescription><NewLeaseDuration>3600</NewLeaseDuration>" +
		"</m:AddPortMapping></s:Body></s:Envelope>"
	if got != want {
		t.Errorf("带租约报文不一致\ngot:  %s\nwant: %s", got, want)
	}
}

// TestUPnPBuildDeletePortMappingByteExact 删除报文（:546-552）。
func TestUPnPBuildDeletePortMappingByteExact(t *testing.T) {
	got := upnpBuildDeletePortMapping(25565, "UDP")
	want := "<?xml version=\"1.0\"?>\n" +
		"<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
		"<s:Body><m:DeletePortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>25565</NewExternalPort>" +
		"<NewProtocol>UDP</NewProtocol></m:DeletePortMapping></s:Body></s:Envelope>"
	if got != want {
		t.Errorf("DeletePortMapping 报文不一致\ngot:  %s\nwant: %s", got, want)
	}
}

// TestUPnPEscapeDesc 描述转义与 null 回落（:511-513）。
func TestUPnPEscapeDesc(t *testing.T) {
	if got := upnpEscapeDesc(nil); got != "VoxLink" {
		t.Errorf("description=null 应回落 VoxLink，实为 %q", got)
	}
	desc := `a&b<c>d"e'f`
	want := "a&amp;b&lt;c&gt;d&quot;e&apos;f"
	if got := upnpEscapeDesc(&desc); got != want {
		t.Errorf("转义结果 = %q, want %q", got, want)
	}
	// & 必须最先替换，否则会把后面生成的实体再包一层
	amp := "&lt;"
	if got := upnpEscapeDesc(&amp); got != "&amp;lt;" {
		t.Errorf("& 优先替换被破坏: %q", got)
	}
}

// TestUPnPExtractHeader 行首匹配与大小写不敏感（:554-577）。
func TestUPnPExtractHeader(t *testing.T) {
	resp := "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.0.2.1:80/igd/desc.xml\r\nST: xxx\r\n\r\n"
	if got := upnpExtractHeader(resp, "LOCATION"); got != "http://192.0.2.1:80/igd/desc.xml" {
		t.Errorf("LOCATION = %q", got)
	}
	if got := upnpExtractHeader(resp, "location"); got != "http://192.0.2.1:80/igd/desc.xml" {
		t.Errorf("小写查询头 = %q", got)
	}
	if got := upnpExtractHeader(resp, "SERVER"); got != "" {
		t.Errorf("无该头时应为空串（等价模组 null），得 %q", got)
	}
	// 不在行首的同名串不得命中（:560 的前判据）
	trap := "X-Foo: LOCATION: http://evil/\r\n\r\n"
	if got := upnpExtractHeader(trap, "LOCATION"); got != "" {
		t.Errorf("非行首命中应失败，实为 %q", got)
	}
	// 首个头（i==0）
	lead := "Location: http://a/\r\n\r\n"
	if got := upnpExtractHeader(lead, "LOCATION"); got != "http://a/" {
		t.Errorf("行首第一个头应命中，实为 %q", got)
	}
	// 只有 \n 分隔（模组 :572-574 的兜底）
	lf := "A: 1\nLOCATION: http://b/\n"
	if got := upnpExtractHeader(lf, "LOCATION"); got != "http://b/" {
		t.Errorf("\\n 分隔应命中，实为 %q", got)
	}
}

// TestUPnPParseGateway 设备描述 XML 的索引式截取（:415-482）。
func TestUPnPParseGateway(t *testing.T) {
	const svc = "urn:schemas-upnp-org:service:WANIPConnection:1"

	t.Run("WANIPConnection 标准形态", func(t *testing.T) {
		xmlBody := `<?xml version="1.0"?><root><device><serviceList>` +
			`<service><serviceType>` + svc + `</serviceType><controlURL>/wanipc</controlURL></service>` +
			`</serviceList></device></root>`
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(xmlBody))
		}))
		defer srv.Close()
		gw := upnpParseGateway(srv.URL + "/desc.xml")
		if gw == nil {
			t.Fatal("应解析出网关")
		}
		if gw.controlU != "/wanipc" || gw.svcType != svc {
			t.Errorf("controlU=%q svcType=%q", gw.controlU, gw.svcType)
		}
		host := strings.TrimPrefix(srv.URL, "http://")
		if gw.baseURL != "http://"+host { // :473-474 显式带端口
			t.Errorf("baseURL=%q want http://%s", gw.baseURL, host)
		}
	})

	t.Run("WANPPPConnection 兜底", func(t *testing.T) {
		body := `<root><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>` +
			`<controlURL>/ppp</controlURL></root>`
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(body))
		}))
		defer srv.Close()
		gw := upnpParseGateway(srv.URL + "/desc.xml")
		if gw == nil || gw.controlU != "/ppp" {
			t.Fatalf("WANPPPConnection 应被兜底命中，得 %+v", gw)
		}
		if !strings.Contains(gw.svcType, "WANPPPConnection") {
			t.Errorf("svcType=%q", gw.svcType)
		}
	})

	t.Run("缺 controlURL 不产出网关", func(t *testing.T) {
		body := `<root><serviceType>` + svc + `</serviceType></root>`
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(body))
		}))
		defer srv.Close()
		if gw := upnpParseGateway(srv.URL + "/x.xml"); gw != nil {
			t.Errorf("缺 controlURL 应返回 nil，得 %+v", gw)
		}
	})

	t.Run("非 http scheme 等价 ClassCastException", func(t *testing.T) {
		if gw := upnpParseGateway("ftp://192.0.2.1/desc.xml"); gw != nil {
			t.Error("ftp 协议应返回 nil")
		}
	})
}

// TestUPnPOpenPortBranches AddPortMapping 的三条判定分支（:85 / :90 / :498）。
func TestUPnPOpenPortBranches(t *testing.T) {
	resetUPnPState(t)
	t.Cleanup(resetUPnPStateQuiet)

	var mu sync.Mutex
	var lastBody, lastAction, lastSOAPAction string
	respBody := "<s:Envelope><s:Body><u:AddPortMappingResponse/></s:Body></s:Envelope>"
	status := 200
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		buf := make([]byte, 8192)
		n, _ := r.Body.Read(buf)
		mu.Lock()
		lastBody = string(buf[:n])
		lastAction = r.URL.Path
		lastSOAPAction = r.Header.Get("SOAPAction")
		mu.Unlock()
		w.WriteHeader(status)
		_, _ = w.Write([]byte(respBody))
	}))
	defer srv.Close()

	host := strings.TrimPrefix(srv.URL, "http://")
	seedUPnPGateway("http://"+host, "/ctl", "urn:schemas-upnp-org:service:WANIPConnection:1")

	// 成功
	res := upnpOpenPort(25565, "VoxLink")
	if !res.available || !res.success || res.externalPort != 25565 {
		t.Errorf("成功分支 res=%+v", res)
	}
	mu.Lock()
	body, action, soap := lastBody, lastAction, lastSOAPAction
	mu.Unlock()
	if !strings.Contains(body, "<NewExternalPort>25565</NewExternalPort>") || !strings.Contains(body, "<NewProtocol>TCP</NewProtocol>") {
		t.Errorf("请求体端口/协议不符: %s", body)
	}
	if action != "/ctl" { // :485 baseUrl+controlUrl 拼接
		t.Errorf("controlURL 拼接路径 = %q", action)
	}
	if want := `"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping"`; soap != want { // :492
		t.Errorf("SOAPAction = %q want %q", soap, want)
	}
	upnpMu.Lock()
	mapped := upnpMappedPorts["25565_TCP"]
	upnpMu.Unlock()
	if !mapped { // :86
		t.Error("成功后应登记 mappedPorts[25565_TCP]")
	}

	// errorCode → 失败但 available=true
	mu.Lock()
	respBody = "<s:Body><u:AddPortMapping><errorCode>714</errorCode></u:AddPortMapping></s:Body>"
	mu.Unlock()
	res = upnpOpenUdpPort(25565, "VoxLink-UDP")
	if !res.available || res.success {
		t.Errorf("errorCode 分支应 available=true/success=false，得 %+v", res)
	}

	// HTTP >=400 → sendSoapRequest 返回 null（:498-501）→ 同样判失败
	mu.Lock()
	respBody = "<s:Body><u:AddPortMappingResponse/></s:Body>"
	status = 500
	mu.Unlock()
	res = upnpOpenPortProto(25566, "x", "UDP")
	if !res.available || res.success {
		t.Errorf("HTTP 500 分支应判失败，得 %+v", res)
	}
}

// TestUPnPOpenPortNoGateway 无网关时的返回值（:74-76 与 :81-82 两条不同分支）。
func TestUPnPOpenPortNoGateway(t *testing.T) {
	resetUPnPState(t)
	t.Cleanup(resetUPnPStateQuiet)

	// 网关缓存指向一个已关服的地址：discoverGateway 不会被调用（缓存命中），
	// 但 SOAP 会失败 → 走 catch 分支（:95-98）→ available=false
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := srv.URL
	srv.Close()
	host := strings.TrimPrefix(url, "http://")
	seedUPnPGateway("http://"+host, "/ctl", "urn:schemas-upnp-org:service:WANIPConnection:1")
	res := upnpOpenPort(25565, "VoxLink")
	if res.available || res.success {
		t.Errorf("SOAP 异常应按 :95-98 返回 (false,false)，得 %+v", res)
	}

	// 有网关但没有本机 IP → (true,false)（:79-82）
	seedUPnPGateway("http://"+host, "/ctl", "urn:schemas-upnp-org:service:WANIPConnection:1")
	upnpMu.Lock()
	upnpCachedLocal = ""
	upnpLocalCacheMs = 0
	upnpMu.Unlock()
	// 让 getLocalIp 返回空：把缓存时间设为"未过期"但值为空 → 走 :79 分支需要真实本机 IP，
	// 因此这里直接验证"协议非法必须抛"（:68-70）而非依赖环境
	defer func() {
		if r := recover(); r == nil {
			t.Error("非法协议应抛 IllegalArgumentException 等价 panic")
		} else if !strings.Contains(fmt.Sprint(r), "Invalid protocol: SCTP") {
			t.Errorf("panic 消息不符: %v", r)
		}
	}()
	upnpOpenPortProto(1, "x", "SCTP")
}

// seedUPnPGateway 把 httptest 假网关写进 60s 网关缓存，让 upnpCachedGateway 直接命中：
// 测试绝不能走真实 SSDP —— 那会向 239.255.255.250:1900 组播 M-SEARCH，扰民且结果不确定。
// 第二个参数按 upnpParseGateway 的口径存成 controlU（upnpSendSOAP 里 baseURL+controlU 拼接）。
func seedUPnPGateway(baseURL, controlPath, svcType string) {
	upnpMu.Lock()
	upnpCachedGW = &upnpGateway{baseURL: baseURL, controlU: controlPath, svcType: svcType}
	upnpGWCacheMs = time.Now().UnixMilli()
	upnpMu.Unlock()
}

func resetUPnPStateQuiet() {
	upnpMu.Lock()
	upnpCachedGW = nil
	upnpGWCacheMs = 0
	upnpCachedLocal = ""
	upnpLocalCacheMs = 0
	upnpMappedPorts = map[string]bool{}
	upnpRenewTasks = map[string]int64{}
	upnpStopped = false
	upnpMu.Unlock()
}

// TestUPnPRenewTickBranches 续租任务体的三条分支（:200-215）。
func TestUPnPRenewTickBranches(t *testing.T) {
	resetUPnPState(t)
	t.Cleanup(resetUPnPStateQuiet)

	var fail bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if fail {
			_, _ = w.Write([]byte("<s:Body><errorCode>501</errorCode></s:Body>"))
			return
		}
		_, _ = w.Write([]byte("<s:Body><u:AddPortMappingResponse/></s:Body>"))
	}))
	defer srv.Close()
	host := strings.TrimPrefix(srv.URL, "http://")

	upnpMu.Lock()
	upnpMappedPorts["51601_UDP"] = true
	upnpRenewTasks["51600_51601_UDP"] = 4242 // 假的 id：分支里只做 remove，不会去 cancel 真任务
	upnpMu.Unlock()
	seedUPnPGateway("http://"+host, "/ctl", "urn:schemas-upnp-org:service:WANIPConnection:1")

	// 续租成功：映射与续租登记都保留
	upnpRenewTick("51600_51601_UDP", 51600, 51601)
	upnpMu.Lock()
	_, okMap := upnpMappedPorts["51601_UDP"]
	_, okTask := upnpRenewTasks["51600_51601_UDP"]
	upnpMu.Unlock()
	if !okMap || !okTask {
		t.Errorf("续租成功后映射/续租都应保留: mapped=%v task=%v", okMap, okTask)
	}

	// 续租失败：摘掉映射并取消续租登记（:206-213）
	fail = true
	upnpRenewTick("51600_51601_UDP", 51600, 51601)
	upnpMu.Lock()
	_, stillMap := upnpMappedPorts["51601_UDP"]
	_, stillTask := upnpRenewTasks["51600_51601_UDP"]
	upnpMu.Unlock()
	if stillMap || stillTask {
		t.Errorf("续租失败后应双双摘掉: mapped=%v task=%v", stillMap, stillTask)
	}

	// 映射已被外部关掉：任务体走"自杀"分支，不再发 SOAP（:190-196）
	called := false
	srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true })
	upnpRenewTick("51600_51601_UDP", 51600, 51601)
	if called {
		t.Error("映射已不在时不应再发 SOAP")
	}
}

// TestUPnPClosePortLeavesRenewTaskScheduled 复刻并钉住模组 :126-131 的既有语义：
// removeIf 只摘键、不 cancel 调度器里的任务（要改属行为变更，需拍板）。
func TestUPnPClosePortLeavesRenewTaskScheduled(t *testing.T) {
	resetUPnPState(t)
	t.Cleanup(resetUPnPStateQuiet)

	s := newSeqScheduler()
	upnpMu.Lock()
	upnpSched = s
	upnpMappedPorts["51601_UDP"] = true
	upnpMu.Unlock()
	// 一条几乎不会到期的排期（模拟 scheduleRenewal 已登记）
	id := s.scheduleRepeat(time.Hour, time.Hour, func() {})
	upnpMu.Lock()
	upnpRenewTasks["51600_51601_UDP"] = id
	upnpMu.Unlock()

	upnpCloseUdpPort(51601)

	upnpMu.Lock()
	_, stillThere := upnpRenewTasks["51600_51601_UDP"]
	upnpMu.Unlock()
	if stillThere {
		t.Error("closePort 应摘掉 renewTasks 的键")
	}
	s.mu.Lock()
	_, taskAlive := s.tasks[id]
	s.mu.Unlock()
	if !taskAlive {
		t.Error("模组的 removeIf 不 cancel 任务；此处若被 cancel 说明改动越界")
	}
	s.shutdownNow()
	upnpMu.Lock()
	upnpSched = nil
	upnpSchedOnce = sync.Once{}
	upnpMu.Unlock()
}

// TestSeqSchedulerSerialAndCancel 单线程串行 + cancel(false) 语义（续租正确性的地基）。
func TestSeqSchedulerSerialAndCancel(t *testing.T) {
	s := newSeqScheduler()
	defer s.shutdownNow()

	var mu sync.Mutex
	order := []string{}
	done := make(chan struct{})
	var wg = make(chan struct{}, 4)
	for i := 0; i < 3; i++ {
		idx := i
		s.scheduleRepeat(time.Duration(5*(i+1))*time.Millisecond, 0, func() {
			mu.Lock()
			order = append(order, fmt.Sprint(idx))
			mu.Unlock()
			wg <- struct{}{}
		})
	}
	go func() {
		for range []int{0, 1, 2} {
			<-wg
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("三次一次性任务未按预期跑完")
	}
	mu.Lock()
	got := strings.Join(order, ",")
	mu.Unlock()
	if got != "0,1,2" { // 同刻到期按提交顺序；错峰到期按到期顺序
		t.Errorf("任务执行顺序 = %q, want 0,1,2", got)
	}

	// 周期任务：cancel 后不再重排
	periodic := make(chan int, 8)
	id := s.scheduleRepeat(5*time.Millisecond, 5*time.Millisecond, func() {
		select {
		case periodic <- 1:
		default:
		}
	})
	<-periodic
	s.cancel(id)
	time.Sleep(40 * time.Millisecond)
	n := 0
	for {
		select {
		case <-periodic:
			n++
		default:
			goto done2
		}
	}
done2:
	// cancel(false) 的语义是"不再排下一次"，但**不打断已经在跑的那一次**
	// （ScheduledFuture.cancel(false) 同语义）。所以容忍 1 次在途执行，只不许继续排。
	if n > 1 {
		t.Errorf("cancel(false) 之后不应继续排期，实收 %d 次", n)
	}

	// stopped 后 schedule 返回 0（等价被拒绝）
	s.shutdownNow()
	if got := s.scheduleRepeat(time.Millisecond, 0, func() {}); got != 0 {
		t.Errorf("停机后应拒绝新任务，得 id=%d", got)
	}
}

// TestUPnPLogLiteralsSurviveSanitizer 本模块日志字面量必须过消敏自检。
// 接线后 upnpLog 的出口就是 App.netLog（hunk 见交付报告），所以这些串必须满足
// netlog_sanitize_test.go 的同一套正则。
func TestUPnPLogLiteralsSurviveSanitizer(t *testing.T) {
	literals := []string{
		"upnp_no_gateway",
		"upnp_no_local_ip",
		"upnp_port_opened protocol=TCP port=25565",
		"upnp_port_open_failed protocol=UDP port=25565",
		"upnp_port_closed protocol=TCP port=25565",
		"upnp_udp_mapping_failed port=51601",
		"upnp_mapping_error err=dial tcp: lookup failed",
		"upnp_close_error err=dial tcp: lookup failed",
		"upnp_renew_skipped internal=51600 external=51601",
		"upnp_renew_failed internal=51600 external=51601",
		"upnp_renew_ok internal=51600 external=51601",
		"upnp_renew_exception context deadline exceeded",
		"upnp_gateway_parse_failed",
		"upnp_startup_prediscovery_begin",
		"upnp_startup_prediscovery_ok elapsed_ms=1500",
		"upnp_startup_prediscovery_no_gateway elapsed_ms=1500",
		"upnp_startup_prediscovery_no_local_ip elapsed_ms=1500",
		"upnp_startup_prediscovery_exception err=dial tcp: lookup failed",
		"upnp_error err=unsupported protocol scheme \"\"",
		"upnp_scheduler_task_panic: runtime error",
	}
	for _, m := range literals {
		clean := sanitizeNetLogMessage(m)
		assertNoSensitive(t, m, clean)
		if clean != m {
			t.Errorf("字面量本就不该被改写（说明写法越界了）: %q → %q", m, clean)
		}
	}
	// 运行期带错误消息的那几条：错误串里若混进地址，必须被消敏掉
	dirty := "upnp_error err=Get \"http://192.0.2.1:80/ctl\": dial tcp 192.0.2.9:1900: i/o timeout"
	assertNoSensitive(t, dirty, sanitizeNetLogMessage(dirty))
}

// TestUPnPLogSinkCapture 确认注入的 sink 收到的是原始串（消敏在出口层做，本模块不预先改写）。
func TestUPnPLogSinkCapture(t *testing.T) {
	var got []string
	upnpLogSink = func(level, msg string) { got = append(got, level+"|"+msg) }
	t.Cleanup(func() { upnpLogSink = nil })
	upnpLog("info", "upnp_no_gateway")
	if len(got) != 1 || got[0] != "info|upnp_no_gateway" {
		t.Errorf("sink 收到 %v", got)
	}
}
