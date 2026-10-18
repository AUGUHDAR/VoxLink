//go:build windows

package main

import (
	"os/exec"
	"syscall"
)

// hideChildConsole 让子进程不弹出控制台窗口。
//
// UI 版是 GUI 子系统程序（-H windowsgui），一旦 spawn 控制台子系统的程序
// （netstat / wmic / powershell）Windows 会给它新建一个控制台——玩家看到的就是
// 每次启动和点"重新检测端口"时闪一下的黑框。CLI 版同样适用：输出本来就走管道，不需要窗口。
//
// 两道保险：STARTF_USESHOWWINDOW/SW_HIDE（HideWindow）对多数程序有效，
// 但有些宿主（cmd 包装、部分 PowerShell 宿主）会自己再 ShowWindow，
// CREATE_NO_WINDOW 则是根本不分配控制台。
func hideChildConsole(cmd *exec.Cmd) {
	const createNoWindow = 0x08000000
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.HideWindow = true
	cmd.SysProcAttr.CreationFlags |= createNoWindow
}
