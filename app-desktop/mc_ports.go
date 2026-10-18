package main

// mc_ports.go — 探测本机 Minecraft Java 进程的监听端口。
//
// 设计要点（对齐 Terracotta 的做法）：
//   1) 先列出所有 java.exe / javaw.exe 进程（wmic 优先，powershell 兜底），按 CommandLine
//      是否命中 MC 特征判断"是不是 MC 进程"。命中即视为 MC 进程（短 cmd 行往往是启动器子进程）。
//   2) 对每个 MC PID 解析 netstat -ano -p TCP 的 LISTENING 行，过滤出该 PID 的端口。
//   3) 返回 {ports:[{port,pid}]}，0 个=没检测到；多端口全返回（≤3 个，按端口升序）。
//   4) 不再返回非 java 进程的端口，不再有 name 字段。
//   5) Windows 实现；wmic 在 Win11 24H2+ 已默认移除，因此 powershell 兜底是必选路径。

import (
	"context"
	"encoding/csv"
	"fmt"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"time"
)

// mcCmdTimeout 外部枚举命令（wmic / powershell / netstat）的硬超时。
// 这三条都是无超时的同步子进程：wmic 在 Win11 上可能根本不存在、powershell 冷启动可挂数十秒，
// 一旦卡住 DetectMcPorts 就永久阻塞，且子进程句柄永不归还。
const mcCmdTimeout = 8 * time.Second

const (
	mcMaxResult = 3 // 最多返回端口数
)

// mcProcEntry 一次 MC 进程检测的进程条目（中间结构）。
type mcProcEntry struct {
	Pid         int
	CommandLine string
}

// mcPortEntry DetectMcPorts 返回的端口项（无 name 字段）。
type mcPortEntry struct {
	Port int `json:"port"`
	Pid  int `json:"pid"`
}

// ---- MC 进程特征匹配 ----

// mcProcessKeywords 进程命令行视为"MC 进程"的不区分大小写特征集。
// 短 cmd 行的 java 可能是启动器子进程，只要命中其一即视为 MC 进程。
var mcProcessKeywords = []string{
	"minecraft",
	".minecraft",
	"fabric",
	"forge",
	"neoforge",
	"net.fabricmc",
	"launchwrapper",
}

// isMcProcessLine 命令行命中任一 MC 特征即视为 MC 进程。
func isMcProcessLine(cmdLine string) bool {
	low := strings.ToLower(cmdLine)
	for _, kw := range mcProcessKeywords {
		if strings.Contains(low, kw) {
			return true
		}
	}
	return false
}

// ---- java 进程枚举（wmic → powershell 兜底）----

// listJavaProcesses 列出所有 java.exe / javaw.exe 进程及其 CommandLine。
// 实现：先尝试 wmic（Win10 默认有），失败/空退 powershell Get-CimInstance。
func listJavaProcesses() ([]mcProcEntry, error) {
	if entries, err := listJavaByWmic(); err == nil && len(entries) > 0 {
		return entries, nil
	}
	return listJavaByPowerShell()
}

// listJavaByWmic 用 wmic 查 java* 进程（Output CSV: ProcessId,CommandLine）。
// 没有 java 进程时 wmic 仅输出表头（不算错误，直接返回空切片）。
func listJavaByWmic() ([]mcProcEntry, error) {
	ctx, cancel := context.WithTimeout(context.Background(), mcCmdTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "wmic", "process", "where",
		"name='java.exe' or name='javaw.exe'",
		"get", "ProcessId,CommandLine", "/FORMAT:CSV")
	hideChildConsole(cmd) // 启动/检测端口时不该闪黑框
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	trimmed := strings.TrimSpace(string(out))
	if trimmed == "" {
		return []mcProcEntry{}, nil
	}
	entries, perr := parseWmicJavaCSV(trimmed)
	if perr != nil {
		return []mcProcEntry{}, nil
	}
	return entries, nil
}

// parseWmicJavaCSV 解析 wmic CSV 输出。表头形如：CommandLine,ProcessId
// （字段顺序在 wmic 上是按字母排序，CommandLine 在前）。
func parseWmicJavaCSV(out string) ([]mcProcEntry, error) {
	r := csv.NewReader(strings.NewReader(out))
	r.FieldsPerRecord = -1 // 允许空白字段（部分行为空）
	records, err := r.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("wmic CSV 解析失败: %w", err)
	}
	if len(records) == 0 {
		return nil, fmt.Errorf("wmic 输出为空")
	}
	header := records[0]
	cmdIdx, pidIdx := -1, -1
	for i, h := range header {
		switch strings.ToLower(strings.TrimSpace(h)) {
		case "commandline":
			cmdIdx = i
		case "processid":
			pidIdx = i
		}
	}
	if cmdIdx < 0 || pidIdx < 0 {
		return nil, fmt.Errorf("wmic 表头缺少 CommandLine/ProcessId")
	}
	var out2 []mcProcEntry
	for _, row := range records[1:] {
		if len(row) <= pidIdx {
			continue
		}
		pid, err := strconv.Atoi(strings.TrimSpace(row[pidIdx]))
		if err != nil || pid <= 0 {
			continue
		}
		var cmdLine string
		if cmdIdx < len(row) {
			cmdLine = row[cmdIdx]
		}
		out2 = append(out2, mcProcEntry{Pid: pid, CommandLine: cmdLine})
	}
	return out2, nil
}

// listJavaByPowerShell powershell 兜底：Get-CimInstance Win32_Process。
// 没有 java 进程时 PowerShell 输出空（不算错误，直接返回空切片）。
func listJavaByPowerShell() ([]mcProcEntry, error) {
	ctx, cancel := context.WithTimeout(context.Background(), mcCmdTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "powershell", "-NoProfile", "-Command",
		"Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Select ProcessId,CommandLine | ConvertTo-Csv -NoTypeInformation")
	hideChildConsole(cmd)
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("powershell 执行失败: %w", err)
	}
	trimmed := strings.TrimSpace(string(out))
	if trimmed == "" {
		return []mcProcEntry{}, nil
	}
	entries, perr := parsePowerShellJavaCSV(trimmed)
	if perr != nil {
		// 解析失败 = 表头缺失等情形，视为空
		return []mcProcEntry{}, nil
	}
	return entries, nil
}

// parsePowerShellJavaCSV 解析 PowerShell ConvertTo-Csv 输出。
// 表头形如："ProcessId","CommandLine"。
func parsePowerShellJavaCSV(out string) ([]mcProcEntry, error) {
	r := csv.NewReader(strings.NewReader(out))
	records, err := r.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("powershell CSV 解析失败: %w", err)
	}
	if len(records) == 0 {
		return nil, fmt.Errorf("powershell 输出为空")
	}
	header := records[0]
	cmdIdx, pidIdx := -1, -1
	for i, h := range header {
		switch strings.ToLower(strings.Trim(strings.TrimSpace(h), `"`)) {
		case "commandline":
			cmdIdx = i
		case "processid":
			pidIdx = i
		}
	}
	if cmdIdx < 0 || pidIdx < 0 {
		return nil, fmt.Errorf("powershell 表头缺少 CommandLine/ProcessId")
	}
	var out2 []mcProcEntry
	for _, row := range records[1:] {
		if len(row) <= pidIdx {
			continue
		}
		pid, err := strconv.Atoi(strings.TrimSpace(row[pidIdx]))
		if err != nil || pid <= 0 {
			continue
		}
		var cmdLine string
		if cmdIdx < len(row) {
			cmdLine = row[cmdIdx]
		}
		out2 = append(out2, mcProcEntry{Pid: pid, CommandLine: cmdLine})
	}
	return out2, nil
}

// ---- netstat LISTENING 解析（与原 parser 形态一致，复用）----

// netstatEntry netstat LISTENING 行解析结果（中间结构）。
type netstatEntry struct {
	Port int
	Pid  int
}

// parseNetstatListening 解析 netstat -ano -p TCP 输出中的 LISTENING 行。
// 行示例： `  TCP    0.0.0.0:25565    0.0.0.0:0    LISTENING    22456`
//
//	`  TCP    [::]:25565       [::]:0       LISTENING    22456`
func parseNetstatListening(out string) []netstatEntry {
	seen := map[netstatEntry]bool{}
	var out2 []netstatEntry
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 || !strings.EqualFold(fields[0], "TCP") {
			continue
		}
		if !strings.EqualFold(fields[3], "LISTENING") {
			continue
		}
		local := fields[1]
		idx := strings.LastIndex(local, ":")
		if idx < 0 {
			continue
		}
		port, err := strconv.Atoi(local[idx+1:])
		if err != nil || port <= 0 || port > 65535 {
			continue
		}
		pid, err := strconv.Atoi(fields[len(fields)-1])
		if err != nil || pid <= 0 {
			continue
		}
		ent := netstatEntry{Port: port, Pid: pid}
		if !seen[ent] {
			seen[ent] = true
			out2 = append(out2, ent)
		}
	}
	return out2
}

// ---- 顶层入口 ----

// detectMcPorts 主流程：列 java 进程 → 筛 MC → 取这些 PID 的 LISTENING 端口 → 排序/截断。
// netstatListening 跑一次 `netstat -ano -p TCP` 并解析出 LISTENING 行。
// 单独拆出来是为了能被测试直接调用：隐藏控制台那面改动一旦把标准输出弄丢，
// "没检测到 MC 端口"和"探测根本没工作"在界面上长得一样，只有这条路径能区分。
func netstatListening() ([]netstatEntry, error) {
	nctx, ncancel := context.WithTimeout(context.Background(), mcCmdTimeout)
	defer ncancel()
	cmd := exec.CommandContext(nctx, "netstat", "-ano", "-p", "TCP")
	hideChildConsole(cmd)
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("执行 netstat 失败: %w", err)
	}
	return parseNetstatListening(string(out)), nil
}

func detectMcPorts() ([]mcPortEntry, error) {
	procs, err := listJavaProcesses()
	if err != nil {
		return nil, fmt.Errorf("枚举 java 进程失败: %w", err)
	}
	if len(procs) == 0 {
		return []mcPortEntry{}, nil
	}

	// 筛 MC 进程（命中 CommandLine 特征）
	mcPids := map[int]bool{}
	for _, p := range procs {
		if isMcProcessLine(p.CommandLine) {
			mcPids[p.Pid] = true
		}
	}
	if len(mcPids) == 0 {
		return []mcPortEntry{}, nil
	}

	// 取这些 PID 的 LISTENING 端口
	entries, err := netstatListening()
	if err != nil {
		return nil, err
	}
	if len(entries) == 0 {
		return []mcPortEntry{}, nil
	}

	// 过滤 + 排序 + 截断
	var result []mcPortEntry
	for _, e := range entries {
		if !mcPids[e.Pid] {
			continue
		}
		result = append(result, mcPortEntry{Port: e.Port, Pid: e.Pid})
	}
	sort.SliceStable(result, func(i, j int) bool {
		if result[i].Port != result[j].Port {
			return result[i].Port < result[j].Port
		}
		return result[i].Pid < result[j].Pid
	})
	if len(result) > mcMaxResult {
		result = result[:mcMaxResult]
	}
	if result == nil {
		result = []mcPortEntry{}
	}
	return result, nil
}

// ---- 调试辅助：仅用于命令行打印真实结果，逻辑无副作用 ----

// runDetectMcPortsForLog 给日志/CLI 用的便捷封装（同 detectMcPorts，附带回执）。
func runDetectMcPortsForLog() (string, error) {
	ports, err := detectMcPorts()
	if err != nil {
		return "", err
	}
	if len(ports) == 0 {
		return "no MC java processes detected", nil
	}
	parts := make([]string, 0, len(ports))
	for _, p := range ports {
		parts = append(parts, fmt.Sprintf("%d(pid=%d)", p.Port, p.Pid))
	}
	return fmt.Sprintf("found %d MC port(s): %s (at %s)", len(ports), strings.Join(parts, ", "), time.Now().Format(time.RFC3339)), nil
}
