package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 日志落盘是"日志上传"和"反馈附日志"两个功能的数据源：模组靠 MC 的 log4j AppenderRefs
// 产出 logs/{latest.log,debug.log}，本端没有 log4j，这两个文件由 logfile.go 自己维护。
// 这里验的是：级别切分（latest.log 不收 DEBUG）、UI 每场截断、CLI 不截断、跨天滚动与历史裁剪。
//
// 注意滚动判据是"文件 mtime 的日期 vs 当前日期"，所以除了专门测滚动的用例，
// 其余用例都钉在真实 now 上（伪造日期会让 mtime 看起来"跨天"，把文件滚走）。

func readLog(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(data)
}

// pinNow 把时钟钉在"现在"：只固定时分秒的稳定性，不改变日期，因此不触发滚动。
func pinNow() func() time.Time {
	now := time.Now()
	return func() time.Time { return now }
}

func TestLogFileLevelSplit(t *testing.T) {
	dir := t.TempDir()
	f := newLogFiles(dir, true)
	f.nowFn = fixedClock(time.Date(2026, 10, 10, 12, 30, 45, 0, time.Local))

	f.Write("info", "连接引擎就绪（role=guest）")
	f.Write("debug", "打洞候选 candidates=3")
	f.Write("warn", "中继验证窗超时")
	f.Write("error", "标准 TURN 分配失败")

	latest := readLog(t, filepath.Join(dir, "latest.log"))
	debug := readLog(t, filepath.Join(dir, "debug.log"))

	if strings.Contains(latest, "打洞候选") {
		t.Errorf("latest.log 不该收 DEBUG（vanilla StripFilter）：\n%s", latest)
	}
	for _, want := range []string{"连接引擎就绪", "中继验证窗超时", "标准 TURN 分配失败"} {
		if !strings.Contains(latest, want) {
			t.Errorf("latest.log 缺 %q：\n%s", want, latest)
		}
	}
	if !strings.Contains(debug, "打洞候选") {
		t.Errorf("debug.log 该收 DEBUG：\n%s", debug)
	}
	// vanilla 行格式：[%d{dd:MM:yyyy HH:mm:ss}] [%t/%level]: %msg
	first := strings.SplitN(latest, "\n", 2)[0]
	if want := "[10:10:2026 12:30:45] [VoxLink/INFO]: 连接引擎就绪"; !strings.HasPrefix(first, want) {
		t.Errorf("行格式不对\n got: %q\nwant: %q 前缀", first, want)
	}
}

func fixedClock(t time.Time) func() time.Time { return func() time.Time { return t } }

func TestLogFileUITruncatesLatestPerRun(t *testing.T) {
	dir := t.TempDir()
	stamp := pinNow()

	first := newLogFiles(dir, true)
	first.nowFn = stamp
	first.Write("info", "上一场的一句话")

	second := newLogFiles(dir, true) // 同一天再起一场
	second.nowFn = stamp
	second.Write("info", "这一场的一句话")

	latest := readLog(t, filepath.Join(dir, "latest.log"))
	if strings.Contains(latest, "上一场") {
		t.Errorf("latest.log 应每场截断（vanilla File appender 无 append）：\n%s", latest)
	}
	debug := readLog(t, filepath.Join(dir, "debug.log"))
	for _, want := range []string{"上一场", "这一场"} {
		if !strings.Contains(debug, want) {
			t.Errorf("debug.log 应跨场追加，缺 %q：\n%s", want, debug)
		}
	}
}

// CLI 版每次调用都是独立进程，绝不能把 UI 那一场的 latest.log 截掉
// （日志上传读的正是它，截断等于毁掉待传样本）。
func TestLogFileCLIAppendsInsteadOfTruncating(t *testing.T) {
	dir := t.TempDir()
	stamp := pinNow()

	ui := newLogFiles(dir, true)
	ui.nowFn = stamp
	ui.Write("info", "UI 的那场日志")

	cli := newLogFiles(dir, false)
	cli.nowFn = stamp
	cli.Write("info", "CLI 的一行")

	latest := readLog(t, filepath.Join(dir, "latest.log"))
	for _, want := range []string{"UI 的那场日志", "CLI 的一行"} {
		if !strings.Contains(latest, want) {
			t.Errorf("CLI 模式应追加，缺 %q：\n%s", want, latest)
		}
	}
}

func TestLogFileRollsOnDayChange(t *testing.T) {
	yesterday := time.Now().AddDate(0, 0, -1)
	today := time.Now()

	for _, tc := range []struct {
		name     string
		truncate bool
		roll     bool // 该模式下 latest.log 是否参与按天滚动
	}{{"cli", false, true}, {"ui", true, false}} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			for _, fname := range []string{"latest.log", "debug.log"} {
				path := filepath.Join(dir, fname)
				if err := os.WriteFile(path, []byte("昨天的内容\n"), 0o644); err != nil {
					t.Fatal(err)
				}
				if err := os.Chtimes(path, yesterday, yesterday); err != nil {
					t.Fatal(err)
				}
			}

			f := newLogFiles(dir, tc.truncate)
			f.nowFn = fixedClock(today)
			f.Write("info", "今天")

			debug := readLog(t, filepath.Join(dir, "debug.log"))
			if !strings.Contains(debug, "今天") || strings.Contains(debug, "昨天") {
				t.Errorf("debug.log 应是新的一天：\n%s", debug)
			}
			if rolled, err := filepath.Glob(filepath.Join(dir, "debug-*.log")); err != nil || len(rolled) != 1 {
				t.Errorf("debug.log 应留下 1 个昨天的滚动文件：%v (err=%v)", rolled, err)
			}

			latest := readLog(t, filepath.Join(dir, "latest.log"))
			if !strings.Contains(latest, "今天") {
				t.Errorf("latest.log 应有今天的内容：\n%s", latest)
			}
			rolled, err := filepath.Glob(filepath.Join(dir, "latest-*.log"))
			if err != nil {
				t.Fatal(err)
			}
			if tc.roll {
				if strings.Contains(latest, "昨天") {
					t.Errorf("CLI 模式 latest.log 跨天应滚走：\n%s", latest)
				}
				if len(rolled) != 1 {
					t.Errorf("CLI 模式应留下 1 个 latest 滚动文件：%v", rolled)
				}
			} else {
				// vanilla：latest.log 是"当前这一场"，不滚动，直接截断重来
				if len(rolled) != 0 {
					t.Errorf("UI 模式不该滚 latest.log（vanilla 无此 appender）：%v", rolled)
				}
				if strings.Contains(latest, "昨天") {
					t.Errorf("UI 模式 latest.log 应被截断：\n%s", latest)
				}
			}
		})
	}
}

func TestLogFileRollPrunesHistory(t *testing.T) {
	dir := t.TempDir()
	// 预置 9 个历史文件（超过 logRollMax=7），再触发一次滚动
	for i := 0; i < 9; i++ {
		name := filepath.Join(dir, "debug-day"+strconv.Itoa(i)+".log")
		if err := os.WriteFile(name, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
		stamp := time.Now().AddDate(0, 0, -(20 + i))
		if err := os.Chtimes(name, stamp, stamp); err != nil {
			t.Fatal(err)
		}
	}
	cur := filepath.Join(dir, "debug.log")
	old := time.Now().AddDate(0, 0, -1)
	if err := os.WriteFile(cur, []byte("昨天\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(cur, old, old); err != nil {
		t.Fatal(err)
	}

	f := newLogFiles(dir, false)
	f.Write("info", "今天")

	matches, err := filepath.Glob(filepath.Join(dir, "debug-*.log"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != logRollMax {
		t.Errorf("历史文件应裁到 %d 个，实际 %d：%v", logRollMax, len(matches), matches)
	}
}

func TestLogFileNilSafeAndDisabledOnOpenFailure(t *testing.T) {
	// dir 落在一个普通文件之下 → MkdirAll 必失败；Write 不能 panic，也不能反复重试磁盘
	blocker := filepath.Join(t.TempDir(), "blocker")
	if err := os.WriteFile(blocker, []byte("not a dir"), 0o644); err != nil {
		t.Fatal(err)
	}
	f := newLogFiles(filepath.Join(blocker, "logs"), true)
	f.Write("info", "这条不该崩")
	if !f.failed {
		t.Errorf("建不出目录时应置 failed，后续不再碰磁盘")
	}
	f.Write("info", "这条也不该崩")

	var nilSink *logFiles
	nilSink.Write("info", "nil 接收者") // 不能 panic
}

func TestAppNetLogWritesFiles(t *testing.T) {
	dir := t.TempDir()
	a := NewApp()
	a.settingsPath = filepath.Join(dir, "VoxLinkApp", "settings.json")
	a.openLogFiles(true)

	a.netLog("info", "mapped=203.0.113.9:46012")
	body := readLog(t, filepath.Join(dir, "VoxLinkApp", "logs", "latest.log"))
	// 落盘的必须是消敏后的文本（netlog_sanitize_test.go 定的同一口径）：
	// 这个文件会被原文上传给服务器，IP 不能进去。
	if strings.Contains(body, "203.0.113.9") {
		t.Errorf("latest.log 里出现了裸 IP：\n%s", body)
	}
	if !strings.Contains(body, "[VoxLink/INFO]") {
		t.Errorf("netLog 没按同一格式落盘：\n%s", body)
	}
}
