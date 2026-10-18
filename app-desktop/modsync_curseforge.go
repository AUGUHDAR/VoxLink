package main

// modsync_curseforge.go — CurseForge 免 key 查询（走 MCIM 镜像），逐句对应
// `modsync/CurseForgeClient.java`（195 行）。零 MC 依赖。
//
// 事实要点（Java 类注释 :17-20）：
//   - 只查项目元数据（真名/图标）与"本端可用文件"，**不在这里下载** ——
//     下载统一走 ModrinthClient.downloadVerified（CF CDN 域名也在 mirrorDownloadUrl 覆盖内）；
//   - 免 key 的原因是借道 MCIM：https://mod.mcimirror.top/curseforge/v1（:22）
//
// 常量：
//   MIRROR    https://mod.mcimirror.top/curseforge/v1   CurseForgeClient.java:22
//   UA        "VoxLink/<版本> (modsync)"                :23
//   TIMEOUT   12s                                       :24
//   connect   10s                                       :45
//   modLoaderType Forge=1 / Fabric=4 / NeoForge=6 / 未知=0（:175-190）

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	cfMirror          = "https://mod.mcimirror.top/curseforge/v1" // :22
	cfMirrorPrefixURL = "https://mod.mcimirror.top"               // :36 同源，CDN 镜像映射用
	cfTimeout         = 12 * time.Second                          // :24
)

// cfSearch 对应 search(query, gameVersion, modLoaderType)（:62-95）。
// modLoaderType<=0 时不带该参数（0=不过滤）。
func cfSearch(query, gameVersion string, modLoaderType int) ([]any, error) {
	if strings.TrimSpace(query) == "" {
		return []any{}, nil
	}
	qs := cfMirror + "/mods/search?gameId=432&classId=6&sortField=2&sortOrder=desc&pageSize=8"
	if modLoaderType > 0 {
		qs += "&modLoaderType=" + strconv.Itoa(modLoaderType)
	}
	if strings.TrimSpace(gameVersion) != "" {
		qs += "&gameVersion=" + url.QueryEscape(gameVersion)
	}
	qs += "&searchFilter=" + url.QueryEscape(query)
	data, err := cfGet(qs)
	if err != nil {
		return nil, err
	}
	var o any
	if json.Unmarshal(data, &o) != nil {
		return []any{}, nil
	}
	obj, ok := o.(map[string]any)
	if !ok {
		return []any{}, nil
	}
	if arr := jarr(obj, "data"); arr != nil {
		return arr, nil
	}
	return []any{}, nil
}

// cfSearchByLoader 对应 search(query, gameVersion, loader) 重载（:57-59）。
func cfSearchByLoader(query, gameVersion, loader string) ([]any, error) {
	return cfSearch(query, gameVersion, cfLoaderType(loader))
}

// cfFileDetail 对应 fileDetail(modId, fileId)（:98-112）：
// 返回 data 对象（downloadUrl/sha1/fileName/fileLength/dependencies），无则 nil。
func cfFileDetail(modID int, fileID int64) (map[string]any, error) {
	data, err := cfGet(fmt.Sprintf("%s/mods/%d/files/%d", cfMirror, modID, fileID))
	if err != nil {
		return nil, err
	}
	return cfDataObject(data), nil
}

// cfModDetail 对应 modDetail(modId)（:115-129）：返回 data（name/slug/logo），无则 nil。
func cfModDetail(modID int) (map[string]any, error) {
	data, err := cfGet(fmt.Sprintf("%s/mods/%d", cfMirror, modID))
	if err != nil {
		return nil, err
	}
	return cfDataObject(data), nil
}

// cfCDNURL 对应 cdnUrl(fileId, fileName)（:132-134）：downloadUrl 缺失时按 fileId 推导。
func cfCDNURL(fileID int64, fileName string) string {
	return fmt.Sprintf("https://edge.forgecdn.net/files/%d/%d/%s", fileID/1000, fileID%1000, fileName)
}

// cfPickFileIndex 对应 pickFileIndex(mod, gameVersion, loader)（:137-172）：
// latestFilesIndexes 中挑本端 版本+加载器 的文件，releaseType 数值最小者优先
// （CF 语义 0=release；初值 bestRank=99）。
func cfPickFileIndex(mod map[string]any, gameVersion, loader string) map[string]any {
	idxs := jarr(mod, "latestFilesIndexes")
	if idxs == nil {
		return nil
	}
	lt := cfLoaderType(loader)
	var best map[string]any
	bestRank := 99
	for _, el := range idxs {
		idx, ok := el.(map[string]any)
		if !ok {
			continue
		}
		if gameVersion != jstr(idx, "gameVersion") {
			continue
		}
		if ml := jstr(idx, "modLoader"); lt > 0 && isAllDigits(ml) {
			if n, err := strconv.Atoi(ml); err == nil && n != lt {
				continue
			}
		}
		release := 3
		if n, err := strconv.Atoi(jstr(idx, "releaseType")); err == nil {
			release = n
		}
		if release < bestRank {
			bestRank = release
			best = idx
		}
	}
	return best
}

// cfLoaderType 对应 loaderType(loader)（:175-190）：Forge=1、Fabric=4、NeoForge=6、未知=0。
func cfLoaderType(loader string) int {
	switch strings.ToLower(loader) {
	case "forge":
		return 1
	case "fabric":
		return 4
	case "neoforge":
		return 6
	default:
		return 0
	}
}

// ---- 传输 ----

// cfGet 单源 GET：超时 12s、UA 同 Modrinth、跟随重定向（复用同一 http.Client）。
// 非 2xx → "CF mirror HTTP <code>"（:84-86, 105-107, 121-123）。
func cfGet(rawURL string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), cfTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", modrinthUserAgent())
	resp, err := modrinthClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("CF mirror HTTP %d", resp.StatusCode)
	}
	return data, nil
}

func cfDataObject(data []byte) map[string]any {
	var o any
	if json.Unmarshal(data, &o) != nil {
		return nil
	}
	obj, ok := o.(map[string]any)
	if !ok {
		return nil
	}
	return jobj(obj, "data")
}

func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
