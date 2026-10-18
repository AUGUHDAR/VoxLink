package main

// 客户端标签（服务端后台"标签"行显示的那段）：模组侧显示的是 forge/neoforge 这类加载器名，
// 应用侧过去固定显示"APP"。现在 CLI 建房可以带 --client-tag 自定义，供启动器开发者对齐标识；
// UI 不暴露这个入参（界面面向玩家）。长度上限 10 个字，中文一字算一个。

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestSanitizeClientTag(t *testing.T) {
	cases := []struct {
		in      string
		want    string
		wantErr bool
	}{
		{"", "", false},
		{"   ", "", false},
		{"PCL2", "pcl2", false},                       // 大小写归一，与服务端 roommanager 同口径
		{"My Launch", "my launch", false},             // 空格保留（"My Launcher" 是 11 个字，超上限要拒）
		{"a   b", "a b", false},                       // 连续空格压成一个
		{"星光启动器", "星光启动器", false},                     // 中文放行（旧规则会整串丢空）
		{"pcl2_beta", "pcl2_beta", false},             // 下划线/连字符（11 字的 "pcl2_beta-x" 会被拒）
		{"1234567890", "1234567890", false},           // 恰好 10 个
		{"12345678901", "", true},                     // 11 个 → 报错
		{"一二三四五六七八九十点", "", true},                     // 中文也按字计数
		{`bad<script>"'`, "badscript", false},         // 尖括号/引号被剔，剩下的仍可用
		{"\u001b[31mred\u001b[0m", "31mred0m", false}, // ANSI 控制序列：转义符与方括号被剔，字面字母留着
		{"❤️love", "love", false},                     // 心形不是汉字，剔掉
		{"<>\"'&", "", false},                         // 全被剔 → 清洗后空
		{"  KamuCL  ", "kamucl", false},               // 只 trim 首尾 + 转小写，中间不动
	}
	for _, c := range cases {
		got, err := sanitizeClientTag(c.in)
		if c.wantErr {
			if err == nil {
				t.Errorf("sanitizeClientTag(%q) 应报错，得到 %q", c.in, got)
			}
			continue
		}
		if err != nil {
			t.Errorf("sanitizeClientTag(%q) 不该报错：%v", c.in, err)
			continue
		}
		if got != c.want {
			t.Errorf("sanitizeClientTag(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// normalizeClientTag 是"静默丢弃"版：磁盘上被人手改坏的设置不能报错，只能当没配。
// normalizeClientTag 是"静默"版：磁盘上被人手改过的设置值不能报错，
// 与服务端同口径地清洗 + 截到 10 个字（截短了仍是有效标识，比整个不上报好）。
func TestNormalizeClientTagTruncatesInsteadOfDropping(t *testing.T) {
	if got := normalizeClientTag("一二三四五六七八九十点"); got != "一二三四五六七八九十" {
		t.Errorf("超长应截断到 10 个字，得 %q", got)
	}
	if got := normalizeClientTag("pcl2"); got != "pcl2" {
		t.Errorf("合法值不该变：%q", got)
	}
	if got := normalizeClientTag(`脏<标签>"`); got != "脏标签" {
		t.Errorf("非法字符应逐个剔掉而不是连坐丢弃，得 %q", got)
	}
}

// CreateRoom 的标签校验发生在网络动作之前：超长必须当场拒绝，
// 而不是把房建起来却悄悄少一个字段。
func TestCreateRoomRejectsOverlongClientTag(t *testing.T) {
	a := NewApp()
	a.settingsPath = t.TempDir() + "/settings.json"
	out := a.CreateRoom(`{"name":"t","hostPort":25565,"clientTag":"一二三四五六七八九十点"}`)
	if !strings.Contains(out, "INVALID_PARAMS") {
		t.Fatalf("超长标签应当场报错，实际：%s", out)
	}
	if !strings.Contains(out, "10") {
		t.Fatalf("报错文案要说清上限是几个字：%s", out)
	}
}

// 设置写回同样报错（面向开发者的入口不能静默丢），且报错时不能留下半套设置。
func TestSaveSettingsRejectsOverlongClientTag(t *testing.T) {
	dir := t.TempDir()
	a := NewApp()
	a.settingsPath = dir + "/settings.json"
	a.settings.Theme = "dark"

	out := a.SaveSettings(`{"theme":"light","clientTag":"一二三四五六七八九十点"}`)
	if !strings.Contains(out, "INVALID_PARAMS") {
		t.Fatalf("应报错：%s", out)
	}
	if a.settings.Theme != "dark" {
		t.Errorf("报错的那次保存不该改到 theme（半套设置）：%q", a.settings.Theme)
	}

	// 中文标签入库并读回
	out = a.SaveSettings(`{"clientTag":"星光启动器"}`)
	if !strings.Contains(out, "星光启动器") {
		t.Fatalf("中文标签应被接受：%s", out)
	}
	if a.clientTagOf() != "星光启动器" {
		t.Errorf("持久值 = %q", a.clientTagOf())
	}
}

// theme 与 clientTag/language 同口径：开发者入口写回野值要当场报错，
// 不能把 "彩虹色" 这类值落盘（界面拿到野值只能走兜底分支）。
func TestSaveSettingsRejectsUnknownTheme(t *testing.T) {
	dir := t.TempDir()
	a := NewApp()
	a.settingsPath = dir + "/settings.json"
	a.settings.Theme = "light"

	out := a.SaveSettings(`{"theme":"彩虹色"}`)
	if !strings.Contains(out, "INVALID_PARAMS") {
		t.Fatalf("未知 theme 应报错：%s", out)
	}
	if a.settings.Theme != "light" {
		t.Errorf("报错那次不该改到 theme：%q", a.settings.Theme)
	}
	for _, ok := range []string{"light", "dark"} {
		if strings.Contains(a.SaveSettings(`{"theme":"`+ok+`"}`), "INVALID_PARAMS") {
			t.Errorf("%s 是合法主题却被拒", ok)
		}
	}
	// 未提供 theme（空串）= 不动现有值，也不能报错
	a.settings.Theme = "dark"
	if strings.Contains(a.SaveSettings(`{"clientTag":"pcl2"}`), "INVALID_PARAMS") {
		t.Error("不带 theme 的保存被误拒")
	}
	if a.settings.Theme != "dark" {
		t.Errorf("不带 theme 的保存改掉了主题：%q", a.settings.Theme)
	}
}

// --client-tag 必须真的落到 /room/create 的请求体里：这里用 httptest 假基址
// （绝不打生产服务器），并让建房响应直接失败——只验"发出去的那一段"，不进会话流程。
func TestCreateRoomPutsClientTagOnTheWire(t *testing.T) {
	var mu sync.Mutex
	var bodies []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		bodies = append(bodies, body)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		// 信封失败：CreateRoom 会在这里返回，后续会话/WS 都不起（测试只看到请求体）
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "TEST_STOP"})
	}))
	defer srv.Close()

	// 端口探测要求本地真有人在听：开一个只用于占口的回环监听
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("回环监听不可用：", err)
	}
	defer ln.Close()
	_, portStr, _ := net.SplitHostPort(ln.Addr().String())

	newApp := func() *App {
		a := NewApp()
		a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
		a.settings.ServerURL = srv.URL
		return a
	}

	// 1) 当次入参优先
	a := newApp()
	a.settings.ClientTag = "pcl2"
	a.CreateRoom(`{"name":"t","hostPort":` + portStr + `,"clientTag":"星光启动器"}`)
	mu.Lock()
	got := bodies[len(bodies)-1]
	mu.Unlock()
	if got["clientTag"] != "星光启动器" {
		t.Fatalf("当次 --client-tag 应覆盖持久值，请求体=%v", got["clientTag"])
	}
	if got["clientType"] != "app" {
		t.Errorf("clientType 不该被标签改动：%v", got["clientType"])
	}

	// 2) 不传时回落持久值
	a2 := newApp()
	a2.settings.ClientTag = "pcl2"
	a2.CreateRoom(`{"name":"t","hostPort":` + portStr + `}`)
	mu.Lock()
	got2 := bodies[len(bodies)-1]
	mu.Unlock()
	if got2["clientTag"] != "pcl2" {
		t.Fatalf("应回落到设置里的持久值，请求体=%v", got2["clientTag"])
	}

	// 3) 两者都空则整个字段不发（不给服务端塞空串）
	a3 := newApp()
	a3.CreateRoom(`{"name":"t","hostPort":` + portStr + `}`)
	mu.Lock()
	got3 := bodies[len(bodies)-1]
	mu.Unlock()
	if _, ok := got3["clientTag"]; ok {
		t.Fatalf("没有标签时不该带 clientTag 字段：%v", got3["clientTag"])
	}
}
