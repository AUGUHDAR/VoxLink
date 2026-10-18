package main

// langui.go — 界面语言包（只有带界面的版本用得上，CLI 按裁决不做多语言）。
//
// 数据源是仓库里的 lang/*.json（13 份、从模组 assets/voxlink/lang 原样拷来，键名一致），
// desktop 构建把它们 embed 进二进制（见 langui_desktop.go），其余构建按磁盘目录加载，
// 这样测试与本机开发不必重新编译也能改文案。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

var (
	langMu        sync.RWMutex
	langPacks     = map[string]map[string]string{}
	langsEmbedded bool // desktop 构建置 true：文案已在二进制里，不再读盘
	loadLangsOnce sync.Once
)

// ensureLangsLoaded 非 embed 构建（CLI、本机开发、测试）第一次用到时才去磁盘找 lang/ 目录。
func ensureLangsLoaded() {
	if langsEmbedded {
		return
	}
	loadLangsOnce.Do(loadLangsFromDisk)
}

// flattenLangJSON 把模组的嵌套 lang 文件摊平成 "a.b.c" → 文案。
// 前端拿到的是扁平表，查表一次命中；模组侧是 Java 的嵌套 JSON，两边同一份数据。
func flattenLangJSON(raw []byte) map[string]string {
	var nested map[string]any
	if err := json.Unmarshal(raw, &nested); err != nil {
		return nil
	}
	out := map[string]string{}
	var walk func(prefix string, m map[string]any)
	walk = func(prefix string, m map[string]any) {
		for k, v := range m {
			key := k
			if prefix != "" {
				key = prefix + "." + k
			}
			if sub, ok := v.(map[string]any); ok {
				walk(key, sub)
				continue
			}
			if s, ok := v.(string); ok {
				out[key] = s
			}
		}
	}
	walk("", nested)
	return out
}

// registerLangPack 由构建侧（embed 或磁盘）灌入一份语言包。
func registerLangPack(code string, raw []byte) {
	flat := flattenLangJSON(raw)
	if len(flat) == 0 {
		return
	}
	langMu.Lock()
	langPacks[code] = flat
	langMu.Unlock()
}

func langPackOf(code string) map[string]string {
	ensureLangsLoaded()
	langMu.RLock()
	defer langMu.RUnlock()
	return langPacks[code]
}

// availableLangs 已加载的语言代码（排序后返回，前端下拉顺序稳定）。
func availableLangs() []string {
	ensureLangsLoaded()
	langMu.RLock()
	out := make([]string, 0, len(langPacks))
	for c := range langPacks {
		out = append(out, c)
	}
	langMu.RUnlock()
	sort.Strings(out)
	return out
}

// loadLangsFromDisk 非 desktop 构建（或 embed 缺失时）从可执行文件旁的 lang/ 目录读。
func loadLangsFromDisk() {
	dirs := []string{}
	if exe, err := os.Executable(); err == nil {
		dirs = append(dirs, filepath.Join(filepath.Dir(exe), "lang"))
	}
	dirs = append(dirs, "lang", filepath.Join(".", "lang"))
	for _, d := range dirs {
		entries, err := os.ReadDir(d)
		if err != nil {
			continue
		}
		for _, e := range entries {
			name := e.Name()
			if e.IsDir() || !strings.HasSuffix(name, ".json") {
				continue
			}
			raw, err := os.ReadFile(filepath.Join(d, name))
			if err != nil {
				continue
			}
			registerLangPack(strings.TrimSuffix(name, ".json"), raw)
		}
		return
	}
}

// GetLangPack 返回指定语言的扁平文案表；code 支持 "auto"（按前端传来的系统语言解析）。
// 前端启动时取一次，之后所有文案查表，不再硬编码。
func (a *App) GetLangPack(code string) string {
	a.mu.Lock()
	setting := a.settings.Language
	a.mu.Unlock()
	if setting == "" {
		setting = "auto"
	}
	resolved := resolveLangCode(setting, code)
	pack := langPackOf(resolved)
	if pack == nil {
		// 兜底链：目标语言 → 中文（本仓文案的原始语言）→ 英文。
		// 缺包时宁可显示中文，也不要整片空白。
		if pack = langPackOf("zh_cn"); pack == nil {
			pack = langPackOf("en_us")
		}
		if pack == nil {
			return wrapOK(map[string]any{"code": resolved, "strings": map[string]string{}})
		}
		resolved = langCodeOfPack(pack, resolved)
	}
	return wrapOK(map[string]any{"code": resolved, "strings": pack})
}

func langCodeOfPack(pack map[string]string, fallback string) string {
	langMu.RLock()
	defer langMu.RUnlock()
	for c, p := range langPacks {
		if len(p) == len(pack) {
			return c
		}
	}
	return fallback
}

// resolveLangCode 决定最终用哪份语言包：
// 用户在设置里显式选了语言就用它；"auto" 时按浏览器/系统语言列表依次匹配，
// 支持 zh / zh-CN / zh_TW / en / en-US 这类写法落到我们的文件名下。
func resolveLangCode(setting string, sysLangs ...string) string {
	if setting != "" && setting != "auto" {
		if langPackOf(setting) != nil {
			return setting
		}
	}
	for _, raw := range sysLangs {
		if raw == "" {
			continue
		}
		for _, cand := range langCandidates(raw) {
			if langPackOf(cand) != nil {
				return cand
			}
		}
	}
	if langPackOf("zh_cn") != nil {
		return "zh_cn"
	}
	return "en_us"
}

// langCandidates 一个 BCP47 标签对应的候选文件名，按精确度从高到低。
func langCandidates(tag string) []string {
	t := strings.ToLower(strings.TrimSpace(strings.ReplaceAll(tag, "_", "-")))
	out := []string{t}
	base, _, _ := strings.Cut(t, "-")
	out = append(out, base)
	switch base {
	case "zh":
		// 简体中文与繁体的文件名不同，必须先按地区细分再退到默认
		if strings.Contains(t, "hk") || strings.Contains(t, "tw") || strings.Contains(t, "mo") {
			out = append(out, "zh_tw", "zh_hk", "zh_cn")
		} else {
			out = append(out, "zh_cn", "zh_hk", "zh_tw")
		}
		out = append(out, "lzh")
	case "en":
		out = append(out, "en_us")
	case "pt":
		out = append(out, "pt_br")
	case "es":
		out = append(out, "es_es")
	case "fr":
		out = append(out, "fr_fr")
	case "de":
		out = append(out, "de_de")
	case "ru":
		out = append(out, "ru_ru")
	case "ja":
		out = append(out, "ja_jp")
	case "ko":
		out = append(out, "ko_kr")
	case "ar":
		out = append(out, "ar_sa")
	}
	return out
}
