package main

// 房主名审核的用例。参照物：RoomManager.java:1142-1186（处理本体）、
// :1966-1987（信令分发）、:1594-1607（心跳分发）。
// 断的是：批准备案要改名并置标志、驳回要把原因带出来、去重条件与模组逐条一致。

import (
	"strings"
	"sync"
	"testing"
)

type noticeSink struct {
	mu      sync.Mutex
	notices []map[string]any
	states  []map[string]any
}

func (s *noticeSink) Emit(event string, data any) {
	m, ok := data.(map[string]any)
	if !ok {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	switch event {
	case "room:notice":
		s.notices = append(s.notices, m)
	case "session:state":
		s.states = append(s.states, m)
	}
}

func (s *noticeSink) keys() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.notices))
	for _, n := range s.notices {
		out = append(out, n["key"].(string))
	}
	return out
}

func moderationApp(t *testing.T) (*App, *noticeSink) {
	t.Helper()
	a := NewApp()
	sink := &noticeSink{}
	a.AttachSink(sink, nil)
	a.mu.Lock()
	a.state = "hosting"
	a.code = "AB12CD"
	a.isHost = true
	a.room = map[string]any{"name": "原始房名", "nameApproved": true}
	a.mu.Unlock()
	return a, sink
}

func ptr(s string) *string { return &s }

func TestNameApprovedRenamesAndMarks(t *testing.T) {
	a, sink := moderationApp(t)
	if !a.handleRoomNameSignal("room_name_approved", map[string]any{"name": "通过后的名字"}) {
		t.Fatal("approved 型应被消费")
	}
	a.mu.Lock()
	name := roomStr(a.room, "name")
	approved := a.room["nameApproved"]
	a.mu.Unlock()
	if name != "通过后的名字" {
		t.Fatalf("批准备案要把新名字落到本地房间信息（:1154-1155），得到 %q", name)
	}
	if approved != true {
		t.Fatal("要置 nameApproved=true（:1153）")
	}
	ks := strings.Join(sink.keys(), ",")
	if !strings.Contains(ks, chatNameApproved) {
		t.Fatalf("要播一条已备案，得到 %v", sink.keys())
	}
	// :1164-1165 模组把名字作为第二行聊天文本，本端是这条通知的唯一参数
	var args []string
	sink.mu.Lock()
	for _, n := range sink.notices {
		if n["key"] == chatNameApproved {
			args, _ = n["args"].([]string)
		}
	}
	sink.mu.Unlock()
	if len(args) != 1 || args[0] != "通过后的名字" {
		t.Fatalf("备案通知要带上生效名字（:1165），得到 %v", args)
	}
	if len(sink.states) == 0 {
		t.Fatal("改名后要把房间信息再广播一次，否则界面停在旧名")
	}
}

func TestNameRejectedCarriesReason(t *testing.T) {
	a, sink := moderationApp(t)
	a.handleRoomNameSignal("room_name_rejected", map[string]any{"name": "违规名字", "reason": "含敏感词"})
	a.mu.Lock()
	approved := a.room["nameApproved"]
	name := roomStr(a.room, "name")
	a.mu.Unlock()
	if approved != false {
		t.Fatal("驳回必须把 nameApproved 置回 false（:1153）")
	}
	if name != "原始房名" {
		t.Fatalf("驳回不得采用被拒的名字（:1154 只在 approved 分支改名），得到 %q", name)
	}
	ks := strings.Join(sink.keys(), ",")
	if !strings.Contains(ks, chatNameRejectedHint) || !strings.Contains(ks, chatReasonLabel) {
		t.Fatalf("驳回要给出提示与原因两行（:1172-1173），得到 %v", sink.keys())
	}
	var reasonArgs []string
	sink.mu.Lock()
	for _, n := range sink.notices {
		if n["key"] == chatReasonLabel {
			reasonArgs, _ = n["args"].([]string)
		}
	}
	sink.mu.Unlock()
	if len(reasonArgs) == 0 || reasonArgs[0] != "含敏感词" {
		t.Fatalf("原因原文要能取到（:1173 reason_label 的参数），得到 %v", reasonArgs)
	}
}

func TestNameRejectedWithoutReasonStillReports(t *testing.T) {
	a, sink := moderationApp(t)
	a.handleRoomNameSignal("room_name_rejected", map[string]any{"name": "n2"})
	ks := strings.Join(sink.keys(), ",")
	if !strings.Contains(ks, chatReasonLabel) {
		t.Fatalf("原因缺失也要给一行『未知原因』（:1169-1171），得到 %v", sink.keys())
	}
	var reasonArgs []string
	sink.mu.Lock()
	for _, n := range sink.notices {
		if n["key"] == chatReasonLabel {
			reasonArgs, _ = n["args"].([]string)
		}
	}
	sink.mu.Unlock()
	if len(reasonArgs) != 1 || reasonArgs[0] != chatUnknownReason {
		t.Fatalf("缺原因时要落到 unknown_reason 键（:1171），得到 %v", reasonArgs)
	}
}

// :1144-1145 的两层门：状态没变时只有 approved 才可能再播。
func TestModerationDedupeMatchesModGates(t *testing.T) {
	t.Run("同状态同名字不重复播", func(t *testing.T) {
		a, sink := moderationApp(t)
		p := map[string]any{"name": "同一个名字", "reason": "同一个原因"}
		a.handleRoomNameSignal("room_name_rejected", p)
		first := len(sink.keys())
		a.handleRoomNameSignal("room_name_rejected", p)
		if got := len(sink.keys()); got != first {
			t.Fatalf("重复驳回不得再播（:1144），第一次 %d 条、现在 %d 条", first, got)
		}
	})
	t.Run("驳回换名字仍不重复播", func(t *testing.T) {
		a, sink := moderationApp(t)
		a.handleRoomNameSignal("room_name_rejected", map[string]any{"name": "违规一", "reason": "r1"})
		first := len(sink.keys())
		a.handleRoomNameSignal("room_name_rejected", map[string]any{"name": "违规二", "reason": "r2"})
		if got := len(sink.keys()); got != first {
			t.Fatalf("模组内门是『状态变了 或 状态就是 approved』（:1145），连续驳回只播一次；第一次 %d 条、现在 %d 条", first, got)
		}
	})
	t.Run("无名字的驳回重复推也只播一次", func(t *testing.T) {
		// 服务端 adminNameBlocked 分支就发 {"reason": ...} 不带 name（moderation.go:472）
		a, sink := moderationApp(t)
		p := map[string]any{"reason": "管理员已屏蔽房间名"}
		a.handleRoomNameSignal("room_name_rejected", p)
		first := len(sink.keys())
		a.handleRoomNameSignal("room_name_rejected", p)
		if got := len(sink.keys()); got != first {
			t.Fatalf("无 name 的重复驳回同样受 :1145 内门约束；第一次 %d 条、现在 %d 条", first, got)
		}
	})
	t.Run("approved 改名每次都播", func(t *testing.T) {
		a, sink := moderationApp(t)
		a.handleRoomNameSignal("room_name_approved", map[string]any{"name": "甲名"})
		first := len(sink.keys())
		a.handleRoomNameSignal("room_name_approved", map[string]any{"name": "乙名"})
		if got := len(sink.keys()); got <= first {
			t.Fatalf("同为 approved 但名字变了要重新播报（:1144 外门），第一次 %d 条、现在 %d 条", first, got)
		}
		a.mu.Lock()
		name := roomStr(a.room, "name")
		a.mu.Unlock()
		if name != "乙名" {
			t.Fatalf("改名要落到本地房间信息，得到 %q", name)
		}
	})
	t.Run("状态来回变化每次都播", func(t *testing.T) {
		a, sink := moderationApp(t)
		a.handleRoomNameSignal("room_name_rejected", map[string]any{"name": "x", "reason": "r"})
		first := len(sink.keys())
		a.handleRoomNameSignal("room_name_approved", map[string]any{"name": "y"})
		if got := len(sink.keys()); got <= first {
			t.Fatalf("驳回→通过是状态变化，必须播（:1144），得到 %v", sink.keys())
		}
	})
}

func TestUnavailableGivesRetryHint(t *testing.T) {
	a, sink := moderationApp(t)
	if !a.handleRoomNameSignal("room_name_unavailable", map[string]any{"name": "占位"}) {
		t.Fatal("unavailable 型应被消费")
	}
	ks := strings.Join(sink.keys(), ",")
	if !strings.Contains(ks, chatNameUnavailable) || !strings.Contains(ks, chatPleaseRetry) {
		t.Fatalf("要同时给『不可用』与『请重试』两行（:1176-1177），得到 %v", sink.keys())
	}
}

func TestOtherSignalsNotConsumed(t *testing.T) {
	a, _ := moderationApp(t)
	if a.handleRoomNameSignal("peer_port", map[string]any{}) {
		t.Fatal("与名字审核无关的信令不得被这条目吃掉")
	}
}

// 审核中的占位名不得写进本地房间名（模组 :1154 明确排除 name_pending_review）。
func TestApprovedWithPlaceholderKeepsOldName(t *testing.T) {
	a, _ := moderationApp(t)
	a.handleNameModerationUpdate("approved", "", ptr(namePendingReviewValue), true)
	a.mu.Lock()
	name := roomStr(a.room, "name")
	approved := a.room["nameApproved"]
	a.mu.Unlock()
	if name != "原始房名" {
		t.Fatalf("占位名不能覆盖已有房名，得到 %q", name)
	}
	if approved != true {
		t.Fatal("approved 标志仍要按参数写入（:1153）")
	}
}

// 心跳响应入口（:1594-1607）：状态=pending 没有聊天行，但 nameApproved 要落回 false。
func TestHeartbeatStatusPendingUpdatesFlagWithoutNotice(t *testing.T) {
	a, sink := moderationApp(t)
	approved := false
	a.handleNameModerationUpdate("pending", "", ptr("审核中名字"), approved)
	a.mu.Lock()
	flag := a.room["nameApproved"]
	name := roomStr(a.room, "name")
	a.mu.Unlock()
	if flag != false {
		t.Fatalf("pending 要把 nameApproved 落成 false（:1153 用参数而非状态串），得到 %v", flag)
	}
	if name != "原始房名" {
		t.Fatalf("pending 不得改名，得到 %q", name)
	}
	if len(sink.keys()) != 0 {
		t.Fatalf("pending 在模组 switch 里没有分支，不该有播报，得到 %v", sink.keys())
	}
	if len(sink.states) == 0 {
		t.Fatal("标志变了仍要广播房间信息，界面才看得到『审核中』")
	}
}

// 不在房里（模组 :1968 currentRoom==null/PENDING 守卫）什么都不写。
func TestNameSignalOutsideRoomIsInert(t *testing.T) {
	a := NewApp()
	sink := &noticeSink{}
	a.AttachSink(sink, nil)
	if !a.handleRoomNameSignal("room_name_approved", map[string]any{"name": "某房名"}) {
		t.Fatal("无房时也应消费该类型，避免落到连接引擎")
	}
	if len(sink.keys()) != 0 || len(sink.states) != 0 {
		t.Fatalf("无房时不得有任何播报，得到 %v / %v", sink.keys(), sink.states)
	}
}

// 心跳响应里的房名：审核中的占位名不许覆盖本地好名（模组 :1154 排除同名）。
func TestHeartbeatNameFilterDropsPlaceholder(t *testing.T) {
	if got := heartbeatDisplayName(namePendingReviewValue); got != "" {
		t.Fatalf("占位名要被丢弃，得到 %q", got)
	}
	if got := heartbeatDisplayName("正常房名"); got != "正常房名" {
		t.Fatalf("别人的房名要照常同步，得到 %q", got)
	}
}
