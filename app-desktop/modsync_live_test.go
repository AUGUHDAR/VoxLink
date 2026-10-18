package main

// modsync_live_test.go — ModSync 下载链的**真实外网**实测（默认 skip）。
//
// 仓库里既有的 ModSync 用例全对着 httptest 假源，能验解析与流程，验不了
// "Modrinth 真的返回这个文件、镜像真的能下下来、sha 校验真的会拒"。
// 这条打真源：查版本 → 取主文件 → 下载并校验 → 落盘到 t.TempDir()（不写任何玩家目录）。
//
// 开启：VOXLIVE_MR=1 go test -run TestModSyncLiveDownload -count=1
// 只读外网 + 只写临时目录，对生产与玩家配置零影响。

import (
	"crypto/sha1"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"testing"
)

const liveFabricAPIProject = "P7dR8mSH" // fabric-api 的 Modrinth project_id

func liveModrinthAllowed(t *testing.T) {
	t.Helper()
	if os.Getenv("VOXLIVE_MR") != "1" {
		t.Skip("需要真实外网：设 VOXLIVE_MR=1")
	}
}

func TestModSyncLiveDownload(t *testing.T) {
	liveModrinthAllowed(t)

	version, err := modrinthLatestVersionFor(liveFabricAPIProject, "1.20.1", "fabric")
	if err != nil {
		t.Fatalf("查 1.20.1/fabric 的 fabric-api 版本失败: %v", err)
	}
	file := modrinthPrimaryFile(version)
	if file == nil {
		t.Fatal("主文件为空")
	}
	dlURL, _ := file["url"].(string)
	name, _ := file["filename"].(string)
	hashes, _ := file["hashes"].(map[string]any)
	sha512, _ := hashes["sha512"].(string)
	sha1Hex, _ := hashes["sha1"].(string)
	if dlURL == "" || name == "" || sha512 == "" || sha1Hex == "" {
		t.Fatalf("文件字段不全 url=%q name=%q sha512=%q sha1=%q", dlURL, name, sha512, sha1Hex)
	}

	dir := t.TempDir()
	n, err := modrinthDownloadVerified(dlURL, sha512, sha1Hex, name, dir, nil)
	if err != nil {
		t.Fatalf("真实下载失败: %v", err)
	}
	path := filepath.Join(dir, name)
	st, err := os.Stat(path)
	if err != nil {
		t.Fatalf("落盘后找不到文件: %v", err)
	}
	if st.Size() != n || n <= 0 {
		t.Fatalf("返回字节数与实际不符: 报告 %d 实际 %d", n, st.Size())
	}
	// 独立复算 sha1：证明"校验通过"不是空转
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	h := sha1.New()
	if _, err := io.Copy(h, f); err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != sha1Hex {
		t.Fatalf("落盘文件 sha1 与源站不符: got %s want %s", got, sha1Hex)
	}
	t.Logf("真实下载通过: %s (%.1f KiB, 镜像优先)", name, float64(n)/1024)

	// 反向：校验必须真的生效。代码口径是"sha512 优先，缺位才看 sha1"
	// （modsync_modrinth.go:710-720），所以两个方向都要试到。
	dir2 := t.TempDir()
	if _, err := modrinthDownloadVerified(dlURL, flipHex(sha512), sha1Hex, name, dir2, nil); err == nil {
		t.Fatal("sha512 校验被绕过：期望不匹配却返回成功")
	}
	if _, err := os.Stat(filepath.Join(dir2, name)); err == nil {
		t.Error("校验失败的成品文件留在了 mods 目录（应删除或不落地）")
	}
	dir3 := t.TempDir()
	if _, err := modrinthDownloadVerified(dlURL, "", flipHex(sha1Hex), name, dir3, nil); err == nil {
		t.Fatal("只给 sha1 时校验被绕过：期望不匹配却返回成功")
	}
}

// flipHex 把十六进制串末位改掉，保证哈希必然不匹配。
func flipHex(s string) string {
	if s == "" {
		return "00"
	}
	last := s[len(s)-1]
	if last == 'a' {
		return s[:len(s)-1] + "b"
	}
	return s[:len(s)-1] + "a"
}
