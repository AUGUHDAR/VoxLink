package main

// 会话级总超时的行为用例（CM:6590-6627 + CM:6701-6727）。
// 断的是三件事：首轮挂表、到点只报"全局超时"这一件事实、已胜出/非首轮不得报。

import (
	"sync"
	"testing"
	"time"
)

type lockedSink struct {
	mu     sync.Mutex
	events []map[string]any
}

func (s *lockedSink) Emit(event string, data any) {
	if m, ok := data.(map[string]any); ok {
		m["_event"] = event
		s.mu.Lock()
		s.events = append(s.events, m)
		s.mu.Unlock()
	}
}

func (s *lockedSink) failures() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []string
	for _, e := range s.events {
		if e["_event"] == "conn:state" && e["status"] == "failed" {
			out = append(out, e["detail"].(string))
		}
	}
	return out
}

func timeoutEngine(t *testing.T) (*App, *connEngine, *lockedSink) {
	t.Helper()
	unit := sessionTimeoutUnit
	sessionTimeoutUnit = time.Millisecond
	t.Cleanup(func() { sessionTimeoutUnit = unit })

	a := NewApp()
	sink := &lockedSink{}
	a.AttachSink(sink, nil)
	e := a.engine
	e.mu.Lock()
	e.sess = newSession(a, "AB12CD", "tok", false)
	e.gActive = true
	e.code = "AB12CD"
	e.token = "tok"
	e.mu.Unlock()
	return a, e, sink
}

func TestGlobalTimeoutArmedOnFirstRoundAndFires(t *testing.T) {
	_, e, sink := timeoutEngine(t)
	e.armConnectionTimeout(punchDefault, false, false)

	e.mu.Lock()
	sec := e.connTimeoutSec
	armed := e.connTimeoutTimer != nil
	e.mu.Unlock()
	if !armed {
		t.Fatal("首轮必须挂会话总超时（CM:6626）")
	}
	if sec != punchDefault.connectionTimeoutSec {
		t.Fatalf("非对称档应取 connectionTimeoutSec=%d，得到 %d", punchDefault.connectionTimeoutSec, sec)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && len(sink.failures()) == 0 {
		time.Sleep(5 * time.Millisecond)
	}
	got := sink.failures()
	if len(got) == 0 {
		t.Fatal("到点必须上报一次全局超时（模组 showConnectFailed(global_timeout)）")
	}
	if got[0] != "voxlink.connection.global_timeout" {
		t.Fatalf("上报内容应是稳定键而非渲染文案，得到 %q", got[0])
	}
}

func TestGlobalTimeoutNotArmedOnRetryRound(t *testing.T) {
	_, e, sink := timeoutEngine(t)
	e.mu.Lock()
	e.retryRound = 1 // CM:6626：持续重试轮次不重挂
	e.mu.Unlock()
	e.armConnectionTimeout(punchDefault, false, false)

	e.mu.Lock()
	armed := e.connTimeoutTimer != nil
	e.mu.Unlock()
	if armed {
		t.Fatal("非首轮不得重挂总超时（否则每轮续命，等于没有总超时）")
	}
	if got := sink.failures(); len(got) != 0 {
		t.Fatalf("非首轮不该有任何失败上报，得到 %v", got)
	}
}

func TestGlobalTimeoutSilentAfterWin(t *testing.T) {
	_, e, sink := timeoutEngine(t)
	e.armConnectionTimeout(punchDefault, false, false)
	e.mu.Lock()
	e.gP2PDone = true // CM:6715 connectionWon 之后到点也不许再报失败
	e.mu.Unlock()
	time.Sleep(300 * time.Millisecond)
	if got := sink.failures(); len(got) != 0 {
		t.Fatalf("已胜出后不得报全局超时，得到 %v", got)
	}
}

// 任一侧对称时窗口拉长（CM:6605-6607），且拉长这一条要留痕
func TestGlobalTimeoutExtendedForSymmetric(t *testing.T) {
	_, e, _ := timeoutEngine(t)
	e.armConnectionTimeout(punchDefault, true, false)
	e.mu.Lock()
	sec := e.connTimeoutSec
	e.mu.Unlock()
	if sec != punchDefault.symmetricConnectionTimeoutSec {
		t.Fatalf("对称侧应取 symmetricConnectionTimeoutSec=%d，得到 %d",
			punchDefault.symmetricConnectionTimeoutSec, sec)
	}
}
