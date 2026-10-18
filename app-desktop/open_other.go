//go:build !windows

package main

import (
	"fmt"
	"os/exec"
	"strings"
)

// openers 各桌面环境的默认浏览器拉起命令：Linux 用 xdg-open，macOS 用 open。
var openers = []string{"xdg-open", "open"}

// OpenInBrowser 用系统默认浏览器打开 http/https 链接（非 Windows）。
func (a *App) OpenInBrowser(rawURL string) {
	u := strings.TrimSpace(rawURL)
	if !validateServerURL(u) {
		a.netLog("warn", "拒绝打开非 http/https 链接: "+rawURL)
		return
	}
	if err := openWith(u); err != nil {
		a.netLog("error", "打开浏览器失败: "+err.Error())
	}
}

// openWith 依次尝试可用的打开命令，一个都没装时返回错误（不静默成功）。
func openWith(url string) error {
	var lastErr error
	for _, name := range openers {
		if _, err := exec.LookPath(name); err != nil {
			lastErr = err
			continue
		}
		cmd := exec.Command(name, url)
		if err := cmd.Start(); err != nil {
			lastErr = err
			continue
		}
		go func() { _ = cmd.Wait() }()
		return nil
	}
	if lastErr != nil {
		return fmt.Errorf("没有可用的浏览器打开命令（%v）: %w", openers, lastErr)
	}
	return fmt.Errorf("没有可用的浏览器打开命令: %v", openers)
}
