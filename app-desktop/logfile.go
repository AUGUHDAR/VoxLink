// 日志落盘：模组不写日志文件，它把 VoxLinkMod.LOGGER 交给 Minecraft 的 log4j2 配置，
// 由 vanilla 的 AppenderRefs 落成 <游戏目录>/logs/latest.log（INFO 起、每次启动截断）
// 与 logs/debug.log（DEBUG 起、按天滚动、maxHistory 个历史文件）。
//
// 两个下游功能只认这两个文件名：
//   - LogUploader 读 latest.log 尾部上传（本端 logupload.go 的 logUploadLogPath）
//   - FeedbackUploader 收录 latest.log + debug.log 作附件（本端 feedback.go 的日志目录枚举）
//
// App 侧没有 log4j：不补这一层，这两个功能永远走"文件缺失"分支（读到 0 字节、静默 skip）。
// a.netLog 是本端唯一的日志出口（177 个调用点，modSyncLog / logUploadLog 也都汇到它），
// 在这一个点写文件即等于全量覆盖。
//
// 每次写都 open-append-close 而不是常开句柄：常开句柄在 Windows 上会挡住同目录的
// 外部改写与测试的 TempDir 清理，而本端的日志量（一场连接几千行）用不起也犯不着缓冲。
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	// logRollMax 与 vanilla 的 maxHistory 同数：滚动下来的历史文件最多留 7 个。
	logRollMax = 7
	// logFileThread vanilla 的 pattern 是 "[%d{dd:MM:yyyy HH:mm:ss}] [%t/%level]: %msg%n"；
	// 本端没有 MC 的线程名，%t 位置固定写 "VoxLink"。两个消费者都只原样存/展示文本，不解析它。
	logFileThread = "VoxLink"

	logFileLatest = "latest.log"
	logFileDebug  = "debug.log"
)

// logFiles 一对文件（latest.log / debug.log）的写入器。
//
// truncateLatest 决定 latest.log 的开法，两个入口不同：
//   - UI 版传 true：vanilla 的 File appender 无 append，首次写时截断——"latest = 当前这一场"。
//   - CLI 版传 false：启动器可能把 `voxlink status` 调上千次，截断会把 UI 那一场的
//     latest.log 抹掉（日志上传读的就是它），所以 CLI 只追加，跨天按天滚动防无限增长。
//     模组没有 CLI，这一条是本端自己的裁决，不是移植偏差。
type logFiles struct {
	dir            string
	truncateLatest bool

	mu      sync.Mutex
	started bool
	failed  bool
	day     string // 当前文件归属的那天，跨天即滚动
	nowFn   func() time.Time
}

func newLogFiles(dir string, truncateLatest bool) *logFiles {
	return &logFiles{dir: dir, truncateLatest: truncateLatest}
}

// logFileLevelName 把本端的 level 字符串归一到 vanilla 的全大写 %level。
func logFileLevelName(level string) string {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug", "trace":
		return "DEBUG"
	case "warn", "warning":
		return "WARN"
	case "error", "err", "fatal":
		return "ERROR"
	default:
		return "INFO"
	}
}

// logFileInLatest 对应 vanilla 的 StripFilter：latest.log 不收 DEBUG/TRACE。
func logFileInLatest(level string) bool {
	return logFileLevelName(level) != "DEBUG"
}

func logFileLine(t time.Time, level, msg string) string {
	return fmt.Sprintf("[%02d:%02d:%04d %02d:%02d:%02d] [%s/%s]: %s\n",
		t.Day(), int(t.Month()), t.Year(), t.Hour(), t.Minute(), t.Second(),
		logFileThread, logFileLevelName(level), strings.TrimRight(msg, "\r\n"))
}

func (f *logFiles) now() time.Time {
	if f.nowFn != nil {
		return f.nowFn()
	}
	return time.Now()
}

// Write 追加一行。写失败一律不报也不重试：日志落盘是辅助通道，不能反过来影响调用点。
func (f *logFiles) Write(level, msg string) {
	if f == nil || f.dir == "" {
		return
	}
	now := f.now()
	line := logFileLine(now, level, msg)

	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failed {
		return
	}
	if !f.started {
		if err := f.prepareLocked(now); err != nil {
			f.failed = true // 建目录失败就不再重试，免得每条日志都撞一次磁盘
			return
		}
	} else if day := now.Format("2006-01-02"); day != f.day {
		f.day = day
		f.rollLocked(now) // 跨天滚动；失败只影响历史文件命名，继续往当前文件写
	}

	// debug.log：DEBUG 起全收（vanilla File appender level=DEBUG）
	f.appendLocked(logFileDebug, line)
	// latest.log：INFO 起（StripFilter 掉 DEBUG/TRACE）
	if logFileInLatest(level) {
		f.appendLocked(logFileLatest, line)
	}
}

// prepareLocked 建目录 + 首次归属日期；UI 模式下把 latest.log 截断（vanilla 语义）。
func (f *logFiles) prepareLocked(now time.Time) error {
	if err := os.MkdirAll(f.dir, 0o755); err != nil {
		return err
	}
	f.started = true
	f.day = now.Format("2006-01-02")
	_ = f.rollFileLocked(logFileDebug, "debug", now)
	if f.truncateLatest { // vanilla：latest.log 每次启动截断，不滚动（上一场的正文由 debug.log 留着）
		return os.WriteFile(filepath.Join(f.dir, logFileLatest), nil, 0o644)
	}
	// 滚动失败只影响历史文件的命名，不阻断本次写，所以错误一律忽略
	_ = f.rollFileLocked(logFileLatest, "latest", now)
	return nil
}

// rollLocked 跨天滚动两个追加型文件（latest.log 在 UI 模式下是"当前这一场"，不滚它）。
func (f *logFiles) rollLocked(now time.Time) {
	if !f.truncateLatest {
		_ = f.rollFileLocked(logFileLatest, "latest", now)
	}
	_ = f.rollFileLocked(logFileDebug, "debug", now)
}

// rollFileLocked 把不属于今天的 <name> 改名为 <prefix>-<该文件当天时刻>.log，
// 再把该前缀的历史裁到 logRollMax 个（vanilla TimeBasedRollingPolicy + maxHistory）。
func (f *logFiles) rollFileLocked(name, prefix string, now time.Time) error {
	cur := filepath.Join(f.dir, name)
	st, err := os.Stat(cur)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if st.ModTime().Format("2006-01-02") == now.Format("2006-01-02") {
		return nil // 还是同一天：继续追加
	}
	rolled := filepath.Join(f.dir, prefix+"-"+st.ModTime().Format("2006-01-02_15-04-05")+".log")
	if err := os.Rename(cur, rolled); err != nil {
		return err
	}
	f.pruneLocked(prefix)
	return nil
}

func (f *logFiles) appendLocked(name, line string) {
	file, err := os.OpenFile(filepath.Join(f.dir, name),
		os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}
	_, _ = file.WriteString(line)
	_ = file.Close()
}

func (f *logFiles) pruneLocked(prefix string) {
	matches, err := filepath.Glob(filepath.Join(f.dir, prefix+"-*.log"))
	if err != nil || len(matches) <= logRollMax {
		return
	}
	sort.Slice(matches, func(i, j int) bool {
		si, e1 := os.Stat(matches[i])
		sj, e2 := os.Stat(matches[j])
		if e1 != nil || e2 != nil {
			return matches[i] < matches[j]
		}
		return si.ModTime().Before(sj.ModTime())
	})
	for _, p := range matches[:len(matches)-logRollMax] {
		_ = os.Remove(p)
	}
}
