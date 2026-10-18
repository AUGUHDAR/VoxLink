package main

// modsync_resolver_test.go — 兜底解析里不依赖网络的部分：匹配质量闸门
// （ModSyncResolver.java:749-765）、候选挑选（:719-742）、分桶（:391-397）、
// CF required 前置提取（:618-645）、限速窗（:808-825）。

import "testing"

func TestModSyncAcceptQualityGate(t *testing.T) {
	cases := []struct {
		query, title, slug string
		want               bool
	}{
		// Java 注释里的两个实证（:744-748）
		{"sophisticatedbackpacks", "Evil Seagull", "evil-seagull", false},
		{"ironchestsforgemod269j", "Iron Chests", "iron-chests", true},
		{"sodium", "Sodium", "sodium", true},
		{"ab", "Anything", "ab", false}, // 太短：a 只有 2 字符，b 需 >=4 且被 a 包含
		{"xaero", "Xaero's Minimap", "xaeros-minimap", true},
		{"", "Whatever", "whatever", false},
	}
	for _, c := range cases {
		if got := modSyncAccept(c.query, c.title, c.slug); got != c.want {
			t.Errorf("accept(%q,%q,%q)=%v want %v", c.query, c.title, c.slug, got, c.want)
		}
	}
}

func TestModSyncPickCandidateSlugExactFirst(t *testing.T) {
	hits := []any{
		map[string]any{"title": "Other", "slug": "other"},
		map[string]any{"title": "Sodium", "slug": "sodium"},
	}
	// slug 与"查询把空格换成 -"完全一致时直接命中（:732-734）
	got := modSyncPickCandidate(hits, "sodium", "title")
	if got == nil || jstr(got, "slug") != "sodium" {
		t.Fatalf("应命中 slug=sodium：%v", got)
	}
	if modSyncPickCandidate(nil, "x", "title") != nil || modSyncPickCandidate([]any{}, "x", "title") != nil {
		t.Fatal("空候选集返回 nil")
	}
	// CF 用 name 字段（:715-717）
	cfHits := []any{map[string]any{"name": "Iron Chests", "id": float64(1)}}
	if got := modSyncPickCf(cfHits, "iron chests"); got == nil {
		t.Fatal("CF 候选应按 name 匹配")
	}
}

func TestModSyncBucketOf(t *testing.T) {
	withURL := newModSyncEntry()
	withURL.DownloadURL = "https://cdn.modrinth.com/a.jar"
	if modSyncBucketOf(withURL) != modSyncBucketDownloadable {
		t.Fatal("有 downloadUrl → DOWNLOADABLE")
	}
	cfNoURL := newModSyncEntry()
	cfNoURL.foundOn = "cf"
	if modSyncBucketOf(cfNoURL) != modSyncBucketNotFound {
		t.Fatal("无 URL 且 foundOn=cf → NOT_FOUND")
	}
	mrNoURL := newModSyncEntry()
	mrNoURL.foundOn = "mr"
	if modSyncBucketOf(mrNoURL) != modSyncBucketIncompatible {
		t.Fatal("无 URL 且 MR 有项目 → INCOMPATIBLE")
	}
}

func TestModSyncCfDepIDsOf(t *testing.T) {
	f := map[string]any{"dependencies": []any{
		map[string]any{"relationType": "3", "modId": float64(3061), "fileId": float64(4528)},
		map[string]any{"relationType": "2", "modId": float64(1), "fileId": float64(2)}, // optional：不收
		map[string]any{"relationType": "3", "modId": float64(0), "fileId": float64(5)}, // modId<=0：不收
		map[string]any{"relationType": "3", "modId": "x", "fileId": "y"},               // 非数字：跳过
	}}
	got := modSyncCfDepIDsOf(f)
	if len(got) != 1 || got[0] != "3061:4528" {
		t.Fatalf("只应收 relationType==3 的 modId:fileId：%v", got)
	}
}

func TestModSyncRequiredProjectIDs(t *testing.T) {
	v := map[string]any{"dependencies": []any{
		map[string]any{"dependency_type": "required", "project_id": "P1"},
		map[string]any{"dependency_type": "REQUIRED", "project_id": "P2"},
		map[string]any{"dependency_type": "optional", "project_id": "P3"},
		map[string]any{"dependency_type": "required", "project_id": ""},
	}}
	got := modSyncRequiredProjectIDs(v)
	if len(got) != 2 || got[0] != "P1" || got[1] != "P2" {
		t.Fatalf("大小写不敏感且剔除空 id：%v", got)
	}
}

func TestModSyncFillCfHashOnlySha1(t *testing.T) {
	out := newModSyncEntry()
	out.Sha512 = "should-be-cleared"
	f := map[string]any{"hashes": []any{
		map[string]any{"algo": "1", "value": "cfsha1"},
	}}
	modSyncFillCfHash(out, f)
	if out.Sha1 != "cfsha1" || out.Sha512 != "" {
		t.Fatalf("CF 文件只有 sha1，sha512 必须清空（:671-688）：%+v", out)
	}
}

func TestModSyncStripJarAndKeyOf(t *testing.T) {
	e := newModSyncEntry()
	e.FileName = "a.jar"
	if modSyncKeyOf(e) != "file:a.jar" {
		t.Fatalf("无 projectId 时按文件名做键：%s", modSyncKeyOf(e))
	}
	e.ProjectID = "P"
	if modSyncKeyOf(e) != "id:P" {
		t.Fatalf("有 projectId 时按 id 做键（:855-857）")
	}
	if modSyncStripJar("x.y.jar") != "x.y" {
		t.Fatal("stripJar 只切最后一个 .jar")
	}
	if modSyncStripJar(".jar") != ".jar" {
		t.Fatal("i==0 不切")
	}
}

func TestModSyncGuardWindow(t *testing.T) {
	// 限速窗必须存在且不会在预算内阻塞：连续 3 次调用应瞬间完成
	before := modSyncWindowCount.Load()
	modSyncGuard()
	modSyncGuard()
	modSyncGuard()
	if modSyncWindowCount.Load() < before {
		t.Fatal("计数不应倒退")
	}
	if modSyncMaxPerMinute != 240 || modSyncResolveWindowMillis != 60000 {
		t.Fatal("限速预算常量与 Java(:45-46) 不一致")
	}
	if modSyncMaxNetFailures != 16 || modSyncMaxDepAdd != 24 || modSyncMaxResolveDepDepth != 2 {
		t.Fatal("解析预算常量与 Java(:50-54) 不一致")
	}
	if modSyncResolvePoolSize != 6 {
		t.Fatal("线程池必须是 6（Java:40）")
	}
}
