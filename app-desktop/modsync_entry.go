package main

// modsync_entry.go — 清单条目 DTO，逐句对应 `modsync/ModSyncEntry.java`（206 行）。
//
// 线上字段名（toJson/fromJson，ModSyncEntry.java:148-188）是跨端契约，必须逐字一致：
//
//	projectId, slug, title, versionNumber, fileName, url, sha1, sha512, iconUrl, size,
//	loaders, gameVersions
//
// Java 的 downloadUrl 字段在 JSON 里叫 "url"（:155）。foundOn / depProjectIds / cfDepIds
// 只在客户端内部使用、不参与序列化（:20-25）—— Go 用不导出字段实现（encoding/json
// 自动忽略），等价。
// 结构体字段声明顺序 = Go 的 marshal 顺序 = Java toJson 的插入顺序，键序亦一致。
//
// JsonObject 在 Go 侧统一用 map[string]any（json.Unmarshal 的产物），配套的
// jstr/jbool/jnum64/jobj/jarr 取值器对应 Gson 的 has/isJsonPrimitive/isJsonObject/…
// 语义：缺键或 null 一律得零值，绝不 panic。

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
)

// modSyncEntry 清单条目。序列化字段与顺序对齐 ModSyncEntry.java:148-167。
type modSyncEntry struct {
	ProjectID     string   `json:"projectId"`
	Slug          string   `json:"slug"`
	Title         string   `json:"title"`
	VersionNumber string   `json:"versionNumber"`
	FileName      string   `json:"fileName"`
	DownloadURL   string   `json:"url"`
	Sha1          string   `json:"sha1"`
	Sha512        string   `json:"sha512"`
	IconURL       string   `json:"iconUrl"`
	Size          int64    `json:"size"`
	Loaders       []string `json:"loaders"`
	GameVersions  []string `json:"gameVersions"`

	// 以下三项不序列化（ModSyncEntry.java:20-25）
	foundOn      string   // "mr"/"cf"，仅分组展示用
	depProjectID []string // resolver 用：MR required 前置 project_id
	cfDepID      []string // resolver 用：CF required 前置 modId:fileId
}

// newModSyncEntry 对应 Java 的字段初始化（:8-28：字符串恒为 ""、列表恒为空表、size=0）。
func newModSyncEntry() *modSyncEntry {
	return &modSyncEntry{
		Loaders:      []string{},
		GameVersions: []string{},
		foundOn:      "",
		depProjectID: []string{},
		cfDepID:      []string{},
	}
}

// modSyncEntryFromVersion：MR version JSON + project JSON → 条目（ModSyncEntry.java:30-69）。
func modSyncEntryFromVersion(version, project map[string]any) *modSyncEntry {
	e := newModSyncEntry()
	if version == nil {
		return e
	}
	e.ProjectID = jstr(version, "project_id")
	e.VersionNumber = jstr(version, "version_number")
	e.Loaders = jstrList(version, "loaders")
	e.GameVersions = jstrList(version, "game_versions")
	if project != nil {
		e.Slug = jstr(project, "slug")
		e.Title = jstr(project, "title")
		e.IconURL = jstr(project, "icon_url")
	}
	if file := modrinthPrimaryFile(version); file != nil {
		e.FileName = jstr(file, "filename")
		e.DownloadURL = jstr(file, "url")
		e.Size = jnum64(file, "size")
		e.Sha1, e.Sha512 = jhashes(file)
	}
	if strings.TrimSpace(e.Title) == "" {
		guess := modSyncGuessTitleFromFileName(e.FileName)
		if guess != "" {
			e.Title = guess
		} else if e.Slug != "" {
			e.Title = e.Slug
		} else {
			e.Title = e.ProjectID
		}
	}
	return e
}

var (
	// ModSyncEntry.java:83 —— 掐掉版本号段（含其后一切）
	modSyncVersionTailRe = regexp.MustCompile(`(?i)[-_. ](mc)?[0-9]+([._-][0-9]+)+.*$`)
	// ModSyncEntry.java:84 —— 掐掉加载器后缀
	modSyncLoaderTailRe = regexp.MustCompile(`(?i)[-_. ]?(fabric|forge|neoforge|fml|quilt)([-_. ].*)?$`)
)

// modSyncGuessTitleFromFileName 对应 ModSyncEntry.guessTitleFromFileName（:72-87）：
// 去 .jar、掐版本号段与加载器后缀、连字符转空格。
func modSyncGuessTitleFromFileName(fileName string) string {
	if fileName == "" {
		return ""
	}
	s := fileName
	// Java: toLowerCase(ROOT).lastIndexOf(".jar")，且只在 i>0 时截断
	if i := strings.LastIndex(strings.ToLower(s), ".jar"); i > 0 {
		s = s[:i]
	}
	s = modSyncVersionTailRe.ReplaceAllString(s, "")
	s = modSyncLoaderTailRe.ReplaceAllString(s, "")
	s = strings.NewReplacer("-", " ", "_", " ").Replace(s)
	return strings.TrimSpace(s)
}

// modSyncEntryFromFileName：unknownMods 文件名 → 手动安装条目（ModSyncEntry.java:90-106）。
func modSyncEntryFromFileName(fileName string) *modSyncEntry {
	e := newModSyncEntry()
	e.FileName = fileName
	guess := modSyncGuessTitleFromFileName(e.FileName)
	if guess == "" {
		bare := e.FileName
		if i := strings.LastIndex(strings.ToLower(bare), ".jar"); i > 0 {
			bare = bare[:i]
		}
		guess = bare
	}
	e.Title = guess
	return e
}

// modSyncEntryFromMetaAndVersion：MR 元数据 + 版本 → 条目（ModSyncEntry.java:109-146）。
// version 为 nil 时只填元数据。
func modSyncEntryFromMetaAndVersion(meta, version map[string]any) *modSyncEntry {
	e := newModSyncEntry()
	if meta == nil {
		return e
	}
	e.ProjectID = jstr(meta, "project_id")
	if e.ProjectID == "" {
		e.ProjectID = jstr(meta, "id")
	}
	e.Slug = jstr(meta, "slug")
	e.Title = jstr(meta, "title")
	e.IconURL = jstr(meta, "icon_url")
	if version != nil {
		e.VersionNumber = jstr(version, "version_number")
		e.Loaders = jstrList(version, "loaders")
		e.GameVersions = jstrList(version, "game_versions")
		if file := modrinthPrimaryFile(version); file != nil {
			e.FileName = jstr(file, "filename")
			e.DownloadURL = jstr(file, "url")
			e.Size = jnum64(file, "size")
			e.Sha1, e.Sha512 = jhashes(file)
		}
	}
	return e
}

// modSyncEntryFromWire：服务器返回的条目 JSON → 条目（ModSyncEntry.java:169-188）。
// title 为空时回落 slug → projectId（:174-176）。
func modSyncEntryFromWire(o map[string]any) *modSyncEntry {
	e := newModSyncEntry()
	e.ProjectID = jstr(o, "projectId")
	e.Slug = jstr(o, "slug")
	e.Title = jstr(o, "title")
	if strings.TrimSpace(e.Title) == "" {
		if e.Slug != "" {
			e.Title = e.Slug
		} else {
			e.Title = e.ProjectID
		}
	}
	e.VersionNumber = jstr(o, "versionNumber")
	e.FileName = jstr(o, "fileName")
	e.DownloadURL = jstr(o, "url")
	e.Sha1 = jstr(o, "sha1")
	e.Sha512 = jstr(o, "sha512")
	e.IconURL = jstr(o, "iconUrl")
	e.Size = jnum64(o, "size")
	e.Loaders = jstrList(o, "loaders")
	e.GameVersions = jstrList(o, "gameVersions")
	return e
}

// modSyncEntriesFromWire 把 manifest.mods 数组转成条目列表（跳过非对象元素，
// 对应 ModSyncGuestService.java:485-489 的 isJsonObject 过滤）。
func modSyncEntriesFromWire(raw []any) []*modSyncEntry {
	out := make([]*modSyncEntry, 0, len(raw))
	for _, el := range raw {
		if o, ok := el.(map[string]any); ok {
			out = append(out, modSyncEntryFromWire(o))
		}
	}
	return out
}

// modSyncEntryMap 条目 → map（供直接塞进 map[string]any 结构的清单）。
func modSyncEntryMap(e *modSyncEntry) map[string]any {
	b, err := json.Marshal(e)
	if err != nil {
		return map[string]any{}
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		return map[string]any{}
	}
	return m
}

// ---- map[string]any 取值器（Gson JsonObject 语义） ----

// jstr：ModSyncEntry.str(...)（:190-192）——缺键/null 得 ""。
func jstr(o map[string]any, key string) string {
	if o == nil {
		return ""
	}
	v, ok := o[key]
	if !ok || v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	// Gson 的 getAsString() 对数字/布尔也返回其字符串形式
	switch t := v.(type) {
	case bool:
		if t {
			return "true"
		}
		return "false"
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	case int64:
		return strconv.FormatInt(t, 10)
	case int:
		return strconv.Itoa(t)
	case json.Number:
		return t.String()
	default:
		return ""
	}
}

// jstrList：ModSyncEntry.strList(...)（:194-205）——只收数组里的原始字符串元素。
func jstrList(o map[string]any, key string) []string {
	out := []string{}
	arr, ok := o[key].([]any)
	if !ok {
		return out
	}
	for _, el := range arr {
		if s, ok := el.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

// jbool：Gson `get(key).getAsBoolean()`；缺键/false 都返回 false（调用方自行判存在性）。
func jbool(o map[string]any, key string) bool {
	v, ok := o[key]
	if !ok {
		return false
	}
	b, _ := v.(bool)
	return b
}

// jnum64：`has(key) && isJsonPrimitive` 时 getAsLong()（ModSyncEntry.java:50,131,184）。
func jnum64(o map[string]any, key string) int64 {
	v, ok := o[key]
	if !ok || v == nil {
		return 0
	}
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		n, err := t.Int64()
		if err != nil {
			return 0
		}
		return n
	case string:
		// Gson 的 getAsLong() 会尝试解析数字字符串
		f, err := strconv.ParseFloat(t, 64)
		if err != nil {
			return 0
		}
		return int64(f)
	default:
		return 0
	}
}

// jobj：`get(key).isJsonObject()` 时返回该对象，否则 nil（Gson isJsonObject 判定）。
func jobj(o map[string]any, key string) map[string]any {
	if o == nil {
		return nil
	}
	m, _ := o[key].(map[string]any)
	return m
}

// jarr：`get(key).isJsonArray()` 时返回该数组，否则 nil。
func jarr(o map[string]any, key string) []any {
	if o == nil {
		return nil
	}
	a, _ := o[key].([]any)
	return a
}

// jhashes：file.hashes.{sha1,sha512}（ModSyncEntry.java:51-60 的取法）。
func jhashes(file map[string]any) (sha1, sha512 string) {
	hashes := jobj(file, "hashes")
	if hashes == nil {
		return "", ""
	}
	return jstr(hashes, "sha1"), jstr(hashes, "sha512")
}
