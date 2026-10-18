//go:build !windows

package main

import "os/exec"

// hideChildConsole 在非 Windows 上没有控制台窗口这回事，空实现保持调用点一致。
func hideChildConsole(cmd *exec.Cmd) {}
