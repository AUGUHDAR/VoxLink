//go:build windows

package main

import (
	"os/exec"
	"strings"
)

// OpenInBrowser 用系统默认浏览器打开 http/https 链接（Windows：rundll32）。
// 校验只允许 http/https：链接来自前端，不能把任意字符串交给 shell。
func (a *App) OpenInBrowser(rawURL string) {
	u := strings.TrimSpace(rawURL)
	if !validateServerURL(u) {
		a.netLog("warn", "拒绝打开非 http/https 链接: "+rawURL)
		return
	}
	// cmd /c start 会吃掉 & 等字符，因此走 url.dll,FileProtocolHandler。
	if err := openWith(u, "rundll32", "url.dll,FileProtocolHandler"); err != nil {
		a.netLog("error", "打开浏览器失败: "+err.Error())
	}
}

// openWith 启动外部程序打开链接；Start 后必须 Wait 回收，否则每次调用留一个僵尸子进程句柄。
// rundll32 把链接交给 shell 后立即退出，异步回收不阻塞界面。
func openWith(url string, name string, prefix ...string) error {
	args := append(append([]string{}, prefix...), url)
	cmd := exec.Command(name, args...)
	hideChildConsole(cmd)
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() { _ = cmd.Wait() }()
	return nil
}
