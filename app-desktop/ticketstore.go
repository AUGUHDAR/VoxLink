package main

// ticketstore.go — 桌面端工单：本机保存"单号 + 归属凭证"，服务端只保存凭证哈希。
//
// 与服务端 ticket.go 的契约：/ticket/submit 返回一次性 ticketSecret（明文只出现这一次），
// 之后 detail/reply/viewed/delete 都必须带上；签发前建的老工单服务端哈希为空 → 继续放行，
// 所以本地凭证为空时照样发请求，不会被误判为越权。
//
// 凭证不下发到前端：所有需要它的请求都在 Go 侧补齐，UI 只拿单号与内容。

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	ticketStoreMax     = 50
	ticketMaxTotalByte = 500 << 20
)

// localTicket 是 voxlink_tickets.json 的一行（存 %APPDATA%/VoxLinkApp/，不出机器）。
type localTicket struct {
	ID         string `json:"id"`
	Secret     string `json:"secret"`
	TimeMs     int64  `json:"timeMs"`
	LastTimeMs int64  `json:"lastTimeMs"`
	ReplyCount int    `json:"replyCount"`
	HasUnread  bool   `json:"hasUnread"`
	Deleted    bool   `json:"deleted"`
}

var ticketMu sync.Mutex

func (a *App) ticketStorePath() string {
	return filepath.Join(filepath.Dir(a.settingsPath), "tickets.json")
}

func readTickets(path string) []localTicket {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var list []localTicket
	if json.Unmarshal(b, &list) != nil {
		return nil
	}
	out := list[:0]
	for _, t := range list {
		if strings.TrimSpace(t.ID) != "" {
			out = append(out, t)
		}
	}
	return out
}

func writeTickets(path string, list []localTicket) error {
	if len(list) > ticketStoreMax {
		list = list[:ticketStoreMax]
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// upsertTicketLocked 新增或补登一条工单记录（调用方须持有 ticketMu）。
func upsertTicketLocked(path string, id, secret string, nowMs int64) error {
	list := readTickets(path)
	for i := range list {
		if list[i].ID == id {
			if secret != "" && list[i].Secret == "" {
				list[i].Secret = secret
				return writeTickets(path, list)
			}
			return nil
		}
	}
	list = append([]localTicket{{ID: id, Secret: secret, TimeMs: nowMs, LastTimeMs: nowMs}}, list...)
	return writeTickets(path, list)
}

func patchTicketLocked(path string, id string, fn func(*localTicket)) error {
	list := readTickets(path)
	for i := range list {
		if list[i].ID == id {
			fn(&list[i])
			return writeTickets(path, list)
		}
	}
	return nil
}

func (a *App) secretOf(id string) string {
	ticketMu.Lock()
	defer ticketMu.Unlock()
	for _, t := range readTickets(a.ticketStorePath()) {
		if t.ID == id {
			return t.Secret
		}
	}
	return ""
}

// ticketView 是下发给前端的记录：不带凭证。
func ticketView(t localTicket) map[string]any {
	return map[string]any{
		"id": t.ID, "timeMs": t.TimeMs, "lastTimeMs": t.LastTimeMs,
		"replyCount": t.ReplyCount, "hasUnread": t.HasUnread, "deleted": t.Deleted,
	}
}

func (a *App) ticketListView() string {
	ticketMu.Lock()
	list := readTickets(a.ticketStorePath())
	ticketMu.Unlock()
	out := make([]map[string]any, 0, len(list))
	unread := 0
	for _, t := range list {
		if t.Deleted {
			continue
		}
		if t.HasUnread {
			unread++
		}
		out = append(out, ticketView(t))
	}
	return wrapOK(map[string]any{"tickets": out, "unread": unread})
}

// TicketList 返回本机保存的工单（不含凭证）。
func (a *App) TicketList() string { return a.ticketListView() }

// TicketPoll 向服务端拉一次回复状态，刷新未读与回复数（启动时与进入工单页时调用）。
func (a *App) TicketPoll() string {
	ticketMu.Lock()
	path := a.ticketStorePath()
	list := readTickets(path)
	ids := make([]string, 0, len(list))
	for _, t := range list {
		if t.ID != "" {
			ids = append(ids, t.ID)
		}
	}
	ticketMu.Unlock()
	if len(ids) == 0 {
		return a.ticketListView()
	}

	var resp struct {
		Tickets []struct {
			ID         string `json:"id"`
			Deleted    bool   `json:"deleted"`
			HasUnread  bool   `json:"hasUnread"`
			ReplyCount int    `json:"replyCount"`
			LastTime   int64  `json:"lastTime"`
		} `json:"tickets"`
		Removed []string `json:"removed"`
	}
	if err := a.api.post(a.baseURL(), "/ticket/poll", map[string]any{"ids": ids}, &resp); err != nil {
		return wrapCallErr(err)
	}
	ticketMu.Lock()
	for _, r := range resp.Tickets {
		id := r.ID
		_ = patchTicketLocked(path, id, func(t *localTicket) {
			t.HasUnread = r.HasUnread
			t.Deleted = r.Deleted
			t.ReplyCount = r.ReplyCount
			if r.LastTime > 0 {
				t.LastTimeMs = r.LastTime * 1000
			}
		})
	}
	// 服务端已硬删/过了保留期的单：本地整条抹掉（含一次性 secret），
	// 否则工单列表里永远躺着一条点开就失败的僵尸单
	if len(resp.Removed) > 0 {
		gone := make(map[string]bool, len(resp.Removed))
		for _, id := range resp.Removed {
			gone[id] = true
		}
		list := readTickets(path)
		kept := make([]localTicket, 0, len(list))
		for _, t := range list {
			if !gone[t.ID] {
				kept = append(kept, t)
			}
		}
		if len(kept) != len(list) {
			_ = writeTickets(path, kept)
		}
	}
	ticketMu.Unlock()
	return a.ticketListView()
}

// TicketSubmit 提交工单：{description, files:[本地路径]} → {id}。
func (a *App) TicketSubmit(reqJSON string) string {
	var req struct {
		Description string   `json:"description"`
		Files       []string `json:"files"`
	}
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	desc := strings.TrimSpace(req.Description)
	if desc == "" && len(req.Files) == 0 {
		return wrapErr("TICKET_EMPTY", "写点什么，或者附上日志吧")
	}
	if code, msg := checkTicketFiles(req.Files); code != "" {
		return wrapErr(code, msg)
	}
	fields := map[string]string{
		"description": desc,
		"client":      "app",
		// clientInfo 的键按服务端 ticketClientTag（server-go/ticket.go:654-661：
		// 取 gameVersion/loader/modVersion/nickname 拼接）与模组侧口径
		// （ui/FeedbackFormScreen.java:445-456 含 modVersion=MOD_VERSION）对齐：
		// 本端版本键为 modVersion=appVersion，后台工单据此显示来源版本。
		"clientInfo": fmt.Sprintf(`{"os":%q,"arch":%q,"version":%q,"modVersion":%q}`, runtime.GOOS, runtime.GOARCH, appVersion, appVersion),
	}
	var out struct {
		ID           string `json:"id"`
		TicketSecret string `json:"ticketSecret"`
	}
	if err := a.api.postMultipart(a.baseURL(), "/ticket/submit", fields, req.Files, &out); err != nil {
		return wrapCallErr(err)
	}
	if out.ID == "" {
		return wrapErr("BAD_RESPONSE", "服务器没有返回工单号")
	}
	ticketMu.Lock()
	err := upsertTicketLocked(a.ticketStorePath(), out.ID, out.TicketSecret, nowMs())
	ticketMu.Unlock()
	if err != nil {
		return wrapErr("IO", "工单已提交，但本地保存失败: "+err.Error())
	}
	return wrapOK(map[string]any{"id": out.ID})
}

// TicketDetail 拉取详情（含正文与往来消息）。
func (a *App) TicketDetail(id string) string {
	id = strings.TrimSpace(id)
	if id == "" {
		return wrapErr("INVALID_PARAMS", "缺少工单号")
	}
	q := url.Values{"id": []string{id}}
	if sec := a.secretOf(id); sec != "" {
		q.Set("secret", sec)
	}
	var data map[string]any
	if err := a.api.get(a.baseURL(), "/ticket/detail", q, &data); err != nil {
		return wrapCallErr(err)
	}
	ticketMu.Lock()
	_ = patchTicketLocked(a.ticketStorePath(), id, func(t *localTicket) {
		t.HasUnread = false
	})
	ticketMu.Unlock()
	if data == nil {
		data = map[string]any{}
	}
	data["id"] = id
	return wrapOK(data)
}

// TicketReply 追问：{id, text, files:[本地路径]}。
func (a *App) TicketReply(reqJSON string) string {
	var req struct {
		ID    string   `json:"id"`
		Text  string   `json:"text"`
		Files []string `json:"files"`
	}
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	req.ID = strings.TrimSpace(req.ID)
	if req.ID == "" {
		return wrapErr("INVALID_PARAMS", "缺少工单号")
	}
	text := strings.TrimSpace(req.Text)
	if text == "" && len(req.Files) == 0 {
		return wrapErr("TICKET_EMPTY", "写点什么，或者附上日志吧")
	}
	if code, msg := checkTicketFiles(req.Files); code != "" {
		return wrapErr(code, msg)
	}
	fields := map[string]string{
		"id":     req.ID,
		"text":   text,
		"secret": a.secretOf(req.ID),
	}
	if err := a.api.postMultipart(a.baseURL(), "/ticket/reply", fields, req.Files, nil); err != nil {
		return wrapCallErr(err)
	}
	ticketMu.Lock()
	_ = patchTicketLocked(a.ticketStorePath(), req.ID, func(t *localTicket) {
		t.LastTimeMs = nowMs()
	})
	ticketMu.Unlock()
	return wrapOK(map[string]any{"id": req.ID})
}

// TicketRetract 撤回自己发出的一条追问：{id, msg}。
// 只能撤 From=player 的消息，且要带上本地存的归属 secret；服务端两处都会再判一次。
func (a *App) TicketRetract(reqJSON string) string {
	var req struct {
		ID  string `json:"id"`
		Msg string `json:"msg"`
	}
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	req.ID = strings.TrimSpace(req.ID)
	req.Msg = strings.TrimSpace(req.Msg)
	if req.ID == "" || req.Msg == "" {
		return wrapErr("INVALID_PARAMS", "缺少工单号或消息号")
	}
	if err := a.api.post(a.baseURL(), "/ticket/retract", map[string]any{
		"id": req.ID, "msg": req.Msg, "secret": a.secretOf(req.ID),
	}, nil); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(map[string]any{"id": req.ID, "msg": req.Msg})
}

// TicketViewed 已读回执（本地即时清零）。
func (a *App) TicketViewed(id string) string {
	id = strings.TrimSpace(id)
	if id == "" {
		return wrapErr("INVALID_PARAMS", "缺少工单号")
	}
	err := a.api.post(a.baseURL(), "/ticket/viewed", map[string]any{
		"id": id, "secret": a.secretOf(id),
	}, nil)
	ticketMu.Lock()
	_ = patchTicketLocked(a.ticketStorePath(), id, func(t *localTicket) {
		t.HasUnread = false
	})
	ticketMu.Unlock()
	if err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(map[string]any{"ok": true})
}

// TicketDelete 玩家端软删：服务端打标记，本地从列表移除。
func (a *App) TicketDelete(id string) string {
	id = strings.TrimSpace(id)
	if id == "" {
		return wrapErr("INVALID_PARAMS", "缺少工单号")
	}
	if err := a.api.post(a.baseURL(), "/ticket/delete", map[string]any{
		"id": id, "secret": a.secretOf(id),
	}, nil); err != nil {
		return wrapCallErr(err)
	}
	ticketMu.Lock()
	_ = patchTicketLocked(a.ticketStorePath(), id, func(t *localTicket) {
		t.Deleted = true
		t.HasUnread = false
	})
	ticketMu.Unlock()
	return wrapOK(map[string]any{"id": id})
}

// checkTicketFiles 附件预检：存在可读、总量不超服务端硬上限（先在本机拦住，
// 免得传了一半被服务器打回）。
func checkTicketFiles(files []string) (string, string) {
	var total int64
	for _, p := range files {
		st, err := os.Stat(p)
		if err != nil || st.IsDir() {
			return "INVALID_FILE", "附件读不到：" + filepath.Base(p)
		}
		total += st.Size()
	}
	if total > ticketMaxTotalByte {
		return "TICKET_TOO_LARGE", fmt.Sprintf("附件合计 %.1f MB，超过 500MB 上限", float64(total)/(1<<20))
	}
	return "", ""
}

// TicketPickFiles 弹系统多选框挑附件；取消返回空列表。
// 无图形层（CLI 版）时明确报 NO_DIALOG——附件路径由调用方直接给出，不假装成功。
func (a *App) TicketPickFiles() string {
	a.mu.Lock()
	picker := a.picker
	a.mu.Unlock()
	if picker == nil {
		return wrapErr("NO_DIALOG", "当前版本没有图形对话框，请直接给出附件路径")
	}
	paths, err := picker.PickFiles("选择要附带的日志 / 截图", []pickFilter{
		{Name: "日志与文本", Patterns: []string{"*.log", "*.txt", "*.json", "*.cfg", "*.ini"}},
		{Name: "图片", Patterns: []string{"*.png", "*.jpg", "*.jpeg", "*.webp"}},
		{Name: "所有文件", Patterns: []string{"*.*"}},
	})
	if err != nil {
		return wrapErr("DIALOG", err.Error())
	}
	out := make([]string, 0, len(paths))
	for _, p := range paths {
		if strings.TrimSpace(p) != "" {
			out = append(out, p)
		}
	}
	return wrapOK(map[string]any{"files": out})
}

func nowMs() int64 { return time.Now().UnixMilli() }
