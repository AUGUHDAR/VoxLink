package main

// modsync_hasher_test.go — mods 目录扫描规则与哈希，对照 ModSyncFileHasher.java:28-83。

import (
	"os"
	"path/filepath"
	"testing"
)

func writeJar(t *testing.T, dir, name, content string) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", p, err)
	}
	return p
}

func TestModSyncListJarsScanRules(t *testing.T) {
	dir := t.TempDir()
	writeJar(t, dir, "b.jar", "bbb")
	writeJar(t, dir, "a.jar", "aaa")
	writeJar(t, dir, "c.jar.disabled", "ccc")
	writeJar(t, dir, "d.jar.part", "ddd") // 下载中：不入扫描（:39-41）
	writeJar(t, dir, "e.zip.disabled", "eee")
	writeJar(t, dir, "f.txt", "fff")
	sub := filepath.Join(dir, "nested")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	writeJar(t, sub, "g.jar", "ggg") // 子目录不递归（Files.list + isRegularFile）

	res := modSyncListJarsWithDisabled(dir)

	gotNames := []string{}
	for _, p := range res.Jars {
		gotNames = append(gotNames, filepath.Base(p))
	}
	want := []string{"a.jar", "b.jar"}
	if len(gotNames) != len(want) {
		t.Fatalf("jars=%v want %v", gotNames, want)
	}
	for i := range want {
		if gotNames[i] != want[i] {
			t.Fatalf("排序/内容不符：%v want %v", gotNames, want)
		}
	}
	// disabled 记的是"原 .jar 名"（:58-61），且 .zip.disabled 不算
	if !res.Disabled["c.jar"] {
		t.Fatalf("disabled 应含 c.jar：%v", res.Disabled)
	}
	if len(res.Disabled) != 1 {
		t.Fatalf("disabled 只应有 1 项：%v", res.Disabled)
	}
}

func TestModSyncListJarsMissingDir(t *testing.T) {
	res := modSyncListJarsWithDisabled(filepath.Join(t.TempDir(), "nope"))
	if len(res.Jars) != 0 || len(res.Disabled) != 0 {
		t.Fatal("目录不存在应得空结果（Java: Files.isDirectory 判定，:32）")
	}
	if got := modSyncListJars(""); len(got) != 0 {
		t.Fatal("空目录名应得空列表")
	}
}

func TestModSyncSha1KnownValues(t *testing.T) {
	dir := t.TempDir()
	p := writeJar(t, dir, "x.jar", "abc")
	// sha1("abc") 的标准值
	if got, err := modSyncSha1File(p); err != nil || got != "a9993e364706816aba3e25717850c26c9cd0d89d" {
		t.Fatalf("sha1=%q err=%v", got, err)
	}
	if got, err := modSyncSha512File(p); err != nil || len(got) != 128 {
		t.Fatalf("sha512 长度应 128：%q err=%v", got, err)
	}
	if got := modSyncSha1OfBytes([]byte("abc")); got != "a9993e364706816aba3e25717850c26c9cd0d89d" {
		t.Fatalf("sha1OfBytes=%q", got)
	}
}

func TestModSyncStateHashIsContentOrdered(t *testing.T) {
	a := t.TempDir()
	b := t.TempDir()
	writeJar(t, a, "1.jar", "hello")
	writeJar(t, a, "2.jar", "world")
	// 同一批内容、不同文件名顺序 → 状态哈希必须一致（sha1 排序后 join，:153-154）
	writeJar(t, b, "z.jar", "world")
	writeJar(t, b, "y.jar", "hello")

	ha, err := modSyncComputeStateHash(modSyncEnv{ModsDir: a})
	if err != nil {
		t.Fatal(err)
	}
	hb, err := modSyncComputeStateHash(modSyncEnv{ModsDir: b})
	if err != nil {
		t.Fatal(err)
	}
	if ha != hb {
		t.Fatalf("状态哈希应只与内容有关：%s vs %s", ha, hb)
	}
	if len(ha) != 40 {
		t.Fatalf("状态哈希应是 sha1 hex：%s", ha)
	}

	// 内容变了 → 状态哈希必须变（缓存键的语义，:143）
	writeJar(t, b, "extra.jar", "!!")
	hc, err := modSyncComputeStateHash(modSyncEnv{ModsDir: b})
	if err != nil {
		t.Fatal(err)
	}
	if hc == ha {
		t.Fatal("内容变化后状态哈希不应相同")
	}
}
