package main

// events.go — 核心层与界面/命令行之间的唯一耦合点。
//
// 引擎（conn_engine/session/relay/turn…）只认识 eventSink 与 filePicker 这两个接口：
//   - UI 版把事件转成 Wails EventsEmit、把附件选择转成系统文件对话框；
//   - CLI 版把事件按 NDJSON 写 stdout、附件路径由 --file 直接给出（无对话框）。
// 这样核心层不再 import wails，CLI 产物才能不含 webview 而把体积压到 UI 版一半以下。

// eventSink 接收引擎广播。实现必须自身保证并发安全且不得阻塞事件源。
type eventSink interface {
	Emit(event string, data any)
}

// pickFilter 一组文件过滤器（显示名 + 扩展名模式），与 UI 对话框的过滤器一一对应。
type pickFilter struct {
	Name     string
	Patterns []string
}

// filePicker 由 UI 层提供的附件选择对话框；CLI 版为 nil（改用命令行给出的路径）。
type filePicker interface {
	PickFiles(title string, filters []pickFilter) ([]string, error)
}

// dirPicker 是 filePicker 的可选扩展：只有 UI 版实现了它，
// CLI 版 picker 为 nil，调用处按"没有对话框"降级。
type dirPicker interface {
	PickDir(title string) (string, error)
}

// nullSink 丢弃全部事件：单元测试与不需要事件的调用方使用。
type nullSink struct{}

func (nullSink) Emit(string, any) {}
