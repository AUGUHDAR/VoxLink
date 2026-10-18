//go:build desktop

package main

// 13 份语言包 embed 进带界面的产物。CLI 不带这个文件，也就不为多语言付体积
// （lang/ 目录约 260KB，且 UPX 对 Mach-O 无效，mac 那格尤其要省）。

import (
	"embed"
	"strings"
)

//go:embed lang/*.json
var embeddedLangFS embed.FS

func init() {
	entries, err := embeddedLangFS.ReadDir("lang")
	if err != nil {
		return
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		raw, err := embeddedLangFS.ReadFile("lang/" + e.Name())
		if err != nil {
			continue
		}
		code := strings.TrimSuffix(e.Name(), ".json")
		registerLangPack(code, raw)
	}
	langsEmbedded = true
}
