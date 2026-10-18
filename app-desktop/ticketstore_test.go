package main

// ticketstore_test.go — 桌面端工单契约：凭证只在 Go 侧与磁盘，前端拿不到；
// detail/reply/viewed/delete 必须带上服务端签发的那一个。

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type ticketStub struct {
	t          *testing.T
	id         string
	secret     string
	lastDetail string
	lastSecret string
	gotClient  string
	gotFiles   int
	forbidAll  bool
	removed    []string
}

func (s *ticketStub) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		send := func(v any) { _ = json.NewEncoder(w).Encode(v) }
		switch r.URL.Query().Get("route") {
		case "/ticket/submit":
			if err := r.ParseMultipartForm(32 << 20); err != nil {
				s.t.Fatalf("submit 不是合法 multipart: %v", err)
			}
			s.gotClient = r.FormValue("client")
			s.gotFiles = len(r.MultipartForm.File["attachments"])
			send(map[string]any{"success": true, "data": map[string]any{
				"id": s.id, "ticketSecret": s.secret, "attachments": s.gotFiles,
			}})
		case "/ticket/reply":
			_ = r.ParseMultipartForm(32 << 20)
			s.lastSecret = r.FormValue("secret")
			if s.lastSecret == "" {
				send(map[string]any{"success": false, "error": "TICKET_FORBIDDEN"})
				return
			}
			send(map[string]any{"success": true, "data": map[string]any{"id": r.FormValue("id")}})
		case "/ticket/detail":
			s.lastDetail = r.URL.Query().Get("id")
			s.lastSecret = r.URL.Query().Get("secret")
			if s.forbidAll || s.lastSecret == "" {
				w.WriteHeader(http.StatusForbidden)
				send(map[string]any{"success": false, "error": "TICKET_FORBIDDEN"})
				return
			}
			send(map[string]any{"success": true, "data": map[string]any{
				"id": s.lastDetail, "description": "打洞失败", "time": 1790000000,
				"messages": []any{map[string]any{"from": "admin", "time": 1790000500, "text": "已收到"}},
			}})
		case "/ticket/poll":
			var body struct {
				IDs []string `json:"ids"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			rows := []map[string]any{}
			for _, id := range body.IDs {
				rows = append(rows, map[string]any{"id": id, "hasUnread": true, "replyCount": 2, "lastTime": 1790000500})
			}
			send(map[string]any{"success": true, "data": map[string]any{"tickets": rows, "removed": s.removed}})
		case "/ticket/viewed", "/ticket/delete":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			s.lastSecret, _ = body["secret"].(string)
			send(map[string]any{"success": true, "data": map[string]any{"ok": true}})
		default:
			send(map[string]any{"success": false, "error": "UNKNOWN_ENDPOINT"})
		}
	}
}

func newTicketApp(t *testing.T, stub *ticketStub) *App {
	t.Helper()
	srv := httptest.NewServer(stub.handler())
	t.Cleanup(srv.Close)
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	a.mu.Lock()
	a.settings.ServerURL = srv.URL
	a.mu.Unlock()
	return a
}

func decode(t *testing.T, s string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		t.Fatalf("返回不是合法 JSON：%s", s)
	}
	return m
}

// 提交：附件流式上传、client=app、ticketSecret 落盘但绝不下发给前端。
func TestTicketSubmitKeepsSecretLocal(t *testing.T) {
	dir := t.TempDir()
	att := filepath.Join(dir, "latest.log")
	if err := os.WriteFile(att, []byte(strings.Repeat("x", 4096)), 0o644); err != nil {
		t.Fatal(err)
	}
	stub := &ticketStub{t: t, id: "abcd1234ef567890", secret: "a3630f5682cfdab541ec78c5aabc5a99"}
	a := newTicketApp(t, stub)

	resp := decode(t, a.TicketSubmit(`{"description":"打洞 100% 失败","files":[`+jsonQuote(att)+`]}`))
	if resp["ok"] != true {
		t.Fatalf("提交应成功：%v", resp)
	}
	if stub.gotClient != "app" {
		t.Fatalf("client 应为 app，实际 %q", stub.gotClient)
	}
	if stub.gotFiles != 1 {
		t.Fatalf("附件应上传 1 个，实际 %d", stub.gotFiles)
	}

	list := a.TicketList()
	if !strings.Contains(list, "abcd1234ef567890") {
		t.Fatalf("列表应含单号：%s", list)
	}
	if strings.Contains(list, stub.secret) {
		t.Fatal("凭证绝不应出现在下发给前端的数据里")
	}
	raw, err := os.ReadFile(a.ticketStorePath())
	if err != nil || !strings.Contains(string(raw), stub.secret) {
		t.Fatalf("凭证应保存在本地文件：%s err=%v", string(raw), err)
	}
}

// 详情/追问/已读/删除都要回传凭证；服务端缺凭证时返回的错误码要透传。
func TestTicketRequestsCarrySecret(t *testing.T) {
	stub := &ticketStub{t: t, id: "1111222233334444", secret: "deadbeefdeadbeefdeadbeefdeadbeef"}
	a := newTicketApp(t, stub)
	if resp := decode(t, a.TicketSubmit(`{"description":"x"}`)); resp["ok"] != true {
		t.Fatalf("提交失败：%v", resp)
	}

	d := decode(t, a.TicketDetail("1111222233334444"))
	if d["ok"] != true {
		t.Fatalf("详情应成功：%v", d)
	}
	if stub.lastSecret != stub.secret {
		t.Fatalf("详情未带本地凭证：%q", stub.lastSecret)
	}
	data, _ := d["data"].(map[string]any)
	if msgs, _ := data["messages"].([]any); len(msgs) != 1 {
		t.Fatalf("详情消息丢失：%v", data)
	}

	if r := decode(t, a.TicketReply(`{"id":"1111222233334444","text":"补充：CGNAT"}`)); r["ok"] != true {
		t.Fatalf("追问应成功：%v", r)
	}
	if stub.lastSecret != stub.secret {
		t.Fatalf("追问未带凭证：%q", stub.lastSecret)
	}
	if v := decode(t, a.TicketViewed("1111222233334444")); v["ok"] != true {
		t.Fatalf("已读应成功：%v", v)
	}
	if dl := decode(t, a.TicketDelete("1111222233334444")); dl["ok"] != true {
		t.Fatalf("删除应成功：%v", dl)
	}
	// 软删后不再出现在列表里
	if strings.Contains(a.TicketList(), "1111222233334444") {
		t.Fatal("已删除的工单不应再出现在列表")
	}
}

func TestTicketDetailForbiddenPropagates(t *testing.T) {
	stub := &ticketStub{t: t, id: "9999888877776666", secret: "cafebabecafebabecafebabecafebabe", forbidAll: true}
	a := newTicketApp(t, stub)
	_ = a.TicketSubmit(`{"description":"x"}`)
	got := decode(t, a.TicketDetail("9999888877776666"))
	if got["ok"] != false || got["error"] != "TICKET_FORBIDDEN" {
		t.Fatalf("服务端 403 应透传 TICKET_FORBIDDEN：%v", got)
	}
}

func TestTicketPollMarksUnread(t *testing.T) {
	stub := &ticketStub{t: t, id: "5555666677778888", secret: "0123456789abcdef0123456789abcdef"}
	a := newTicketApp(t, stub)
	_ = a.TicketSubmit(`{"description":"x"}`)
	got := decode(t, a.TicketPoll())
	data, _ := got["data"].(map[string]any)
	if data["unread"] != float64(1) {
		t.Fatalf("轮询后应记 1 条未读：%v", data)
	}
	list, _ := data["tickets"].([]any)
	row := list[0].(map[string]any)
	if row["replyCount"] != float64(2) {
		t.Fatalf("回复数未刷新：%v", row)
	}
	if strings.Contains(a.TicketPoll(), stub.secret) {
		t.Fatal("轮询结果同样不得下发凭证")
	}
}

func TestTicketPollDropsServerRemoved(t *testing.T) {
	stub := &ticketStub{t: t, id: "1111222233334444", secret: strings.Repeat("a", 32),
		removed: []string{"9999888877776666"}}
	a := newTicketApp(t, stub)
	if err := writeTickets(a.ticketStorePath(), []localTicket{
		{ID: "1111222233334444", Secret: strings.Repeat("a", 32), TimeMs: 1790000000000},
		{ID: "9999888877776666", Secret: strings.Repeat("b", 32), TimeMs: 1790000000000},
	}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(a.TicketList(), "9999888877776666") {
		t.Fatal("前置条件没成立：本地应先有两条")
	}
	a.TicketPoll()
	list := a.TicketList()
	if strings.Contains(list, "9999888877776666") {
		t.Fatalf("服务端已删的单必须从本地消失：%s", list)
	}
	if !strings.Contains(list, "1111222233334444") {
		t.Fatalf("没被删的单不该丢：%s", list)
	}
}

func TestCheckTicketFiles(t *testing.T) {
	dir := t.TempDir()
	ok := filepath.Join(dir, "a.log")
	if err := os.WriteFile(ok, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if code, _ := checkTicketFiles([]string{ok}); code != "" {
		t.Fatalf("正常附件不应被拦：%s", code)
	}
	if code, _ := checkTicketFiles([]string{filepath.Join(dir, "missing.log")}); code != "INVALID_FILE" {
		t.Fatalf("缺失附件应报 INVALID_FILE，实际 %q", code)
	}
	if code, _ := checkTicketFiles([]string{dir}); code != "INVALID_FILE" {
		t.Fatalf("目录不应作为附件，实际 %q", code)
	}
}

func jsonQuote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}
