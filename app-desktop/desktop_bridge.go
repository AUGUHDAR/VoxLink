//go:build desktop

package main

// desktop_bridge.go — UI 版与核心层之间的那一层薄适配：事件出口、文件对话框、生命周期回调。
// 只有这个文件（和 main.go）允许 import wails，其余核心层保持无 GUI 依赖，
// 这样 CLI 版才能不带上 webview 编译、体积压到 UI 版一半以下。

import (
	"context"
	"fmt"
	"strings"

	wailsrt "github.com/wailsapp/wails/v2/pkg/runtime"
)

// wailsSink 把核心层事件转成前端事件。ctx 为空时静默丢弃（窗口已关闭的收尾期）。
type wailsSink struct{ ctx context.Context }

func (s wailsSink) Emit(event string, data any) {
	if s.ctx == nil {
		return
	}
	wailsrt.EventsEmit(s.ctx, event, data)
}

// wailsPicker 系统文件多选框，过滤器与工单附件的三档一致。
type wailsPicker struct{ ctx context.Context }

func (p wailsPicker) PickFiles(title string, filters []pickFilter) ([]string, error) {
	if p.ctx == nil {
		return nil, fmt.Errorf("窗口尚未就绪，无法打开文件对话框")
	}
	opts := wailsrt.OpenDialogOptions{Title: title}
	for _, f := range filters {
		opts.Filters = append(opts.Filters, wailsrt.FileFilter{
			DisplayName: f.Name,
			Pattern:     strings.Join(f.Patterns, ";"),
		})
	}
	return wailsrt.OpenMultipleFilesDialog(p.ctx, opts)
}

// PickDir 系统目录选择框（mods 目录、实例目录用）。工单附件只要文件，
// 目录这一档只有界面层用得到，所以单独一个方法，不塞进 filePicker 的必选契约。
func (p wailsPicker) PickDir(title string) (string, error) {
	if p.ctx == nil {
		return "", fmt.Errorf("窗口尚未就绪，无法打开目录对话框")
	}
	return wailsrt.OpenDirectoryDialog(p.ctx, wailsrt.OpenDialogOptions{Title: title})
}

// startup 由 Wails OnStartup 调用：先接上事件出口，再加载设置
// （loadSettings 期间若有日志要出口，此时 sink 已就位）。
func (a *App) startup(ctx context.Context) {
	a.AttachSink(wailsSink{ctx: ctx}, wailsPicker{ctx: ctx})
	a.openLogFiles(true) // 在任何日志之前就绪（log4j 在 MC 启动早期就挂好 AppenderRef）
	a.Init()
}

// shutdown 由 Wails OnShutdown 调用。
func (a *App) shutdown(ctx context.Context) { a.Shutdown() }
