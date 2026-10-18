//go:build !desktop && !cli

package main

// main_stub.go — 没带构建标签时的兜底入口。
// 两个产物共用这一个包：UI 版要 -tags desktop,production（webview + embed 前端），
// CLI 版要 -tags cli（零 GUI 依赖）。裸 `go build .` 两个都不是，这里明确说清该怎么构建，
// 而不是让编译期报"function main is undeclared"。

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "VoxLink 需要指定构建哪个版本：")
	fmt.Fprintln(os.Stderr, "  UI 版: go build -tags \"desktop,production\" -ldflags \"-s -w -H windowsgui\" -o VoxLink-UI.exe .")
	fmt.Fprintln(os.Stderr, "  CLI 版: go build -tags cli -ldflags \"-s -w\" -o VoxLink-CLI.exe .")
	fmt.Fprintln(os.Stderr, "（开发调试用 wails dev 会自动带上 desktop 标签）")
	os.Exit(2)
}
