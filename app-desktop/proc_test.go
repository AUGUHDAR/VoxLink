package main

// 隐藏子进程控制台（不闪黑框）之后，最坏的回退是"标准输出也拿不到了"——
// 那在界面上表现为"没检测到 MC 端口"，和真的没开 MC 长得一模一样。
// 所以这里直接用真实命令验证：窗口藏起来了，输出还在。

import (
	"runtime"
	"testing"
)

func TestHideChildConsoleStillCapturesStdout(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("netstat/wmic 这条路径是 Windows 专属")
	}
	// 任何开着的 Windows 机器都至少有一个 LISTENING 端口；拿到 0 条就说明输出被吞了。
	entries, err := netstatListening()
	if err != nil {
		t.Fatalf("netstatListening 失败（隐藏控制台后拿不到输出？）：%v", err)
	}
	if len(entries) == 0 {
		t.Fatal("netstat 一条 LISTENING 都没解析出来：输出捕获被 HideWindow/CREATE_NO_WINDOW 破坏了")
	}

	// java 进程枚举同理：没在跑 MC 时应当是"空列表 + 无错误"，而不是报错或卡住。
	procs, err := listJavaProcesses()
	if err != nil {
		t.Fatalf("listJavaProcesses 失败：%v", err)
	}
	t.Logf("本机 LISTENING 端口 %d 个、java 进程 %d 个", len(entries), len(procs))
}
