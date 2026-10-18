package main

// modsync_hasher.go — mods 目录扫描与文件哈希，逐句对应
// `modsync/ModSyncFileHasher.java`（147 行）。纯标准库，无加载器 API 依赖
// （Java 侧亦然：ModSyncFileHasher.java:12 的类注释明确"纯 JDK"）。
//
// 常量：
//   MAX_JARS = 1024      （ModSyncFileHasher.java:18）扫描硬上限，超出截断并 warn
//   HASH_BUF = 64 * 1024 （ModSyncFileHasher.java:19）流式 digest 缓冲
//
// 扫描规则（:28-83）：只取 mods 目录的直接子文件（Files.list + isRegularFile，不递归），
// 收 .jar 与 .jar.disabled；跳过 .part 与其他扩展名的 .disabled；按文件名排序；
// disabled 集合记的是"去掉 .disabled 尾巴后的原始 .jar 名"（:58-61），供调用方判定
// "已安装但被玩家禁用"。

import (
	"crypto/sha1"
	"crypto/sha512"
	"encoding/hex"
	"hash"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const (
	modSyncMaxJars = 1024      // ModSyncFileHasher.java:18
	modSyncHashBuf = 64 * 1024 // ModSyncFileHasher.java:19
)

// modSyncJarScan 对应 Java 的 ListModJarsResult（:91-99）。
type modSyncJarScan struct {
	Jars     []string        // 活跃 .jar 的完整路径（按文件名排序）
	Disabled map[string]bool // 被禁用 jar 的"原 .jar 文件名"集合
}

func newModSyncJarScan() modSyncJarScan {
	return modSyncJarScan{Jars: []string{}, Disabled: map[string]bool{}}
}

// modSyncListJarsWithDisabled 对应 listModJarsWithDisabled()（:28-83）。
// dir 为空或不是目录时返回空结果（Java：Files.isDirectory 判定，:32）。
func modSyncListJarsWithDisabled(dir string) modSyncJarScan {
	out := newModSyncJarScan()
	if dir == "" {
		return out
	}
	if st, err := os.Stat(dir); err != nil || !st.IsDir() {
		return out
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		// Java 的 IOException 分支（:66-68）：warn 后带已有结果返回
		modSyncLogWarn("listModJars failed: {}", err.Error()) // ModSyncFileHasher.java:67
		return out
	}

	type named struct {
		name string
		path string
	}
	var all []named
	for _, e := range entries {
		if e.IsDir() {
			// Files::isRegularFile —— 子目录一律跳过
			continue
		}
		info, err := e.Info()
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		name := e.Name()
		low := strings.ToLower(name)
		switch {
		case strings.HasSuffix(low, ".part"):
			continue
		case strings.HasSuffix(low, ".jar.disabled"):
			// 保留
		case strings.HasSuffix(low, ".disabled"):
			// 其他扩展名的 .disabled 跳过（不是 jar 衍生）
			continue
		case strings.HasSuffix(low, ".jar"):
			// 保留
		default:
			continue
		}
		all = append(all, named{name: name, path: filepath.Join(dir, name)})
	}

	// 按文件名字典序（Java: Comparator.comparing(fileName)，UTF-16 码元序；
	// Go 的字符串比较是字节序，纯 ASCII 名一致；非 ASCII 时排序差异不影响任何判定，
	// 只影响 unknownMods 的截断顺序）
	sort.SliceStable(all, func(i, j int) bool { return all[i].name < all[j].name })

	for _, n := range all {
		if strings.HasSuffix(strings.ToLower(n.name), ".jar.disabled") {
			// 记下原始 .jar 文件名（去掉 ".disabled" 末尾）
			out.Disabled[strings.TrimSuffix(n.name, ".disabled")] = true
			continue
		}
		out.Jars = append(out.Jars, n.path)
	}

	if len(out.Jars)+len(out.Disabled) > modSyncMaxJars {
		modSyncLogWarn("mods 数量超过 {}，超出部分未参与校验 (jars={}, disabled={})", // ModSyncFileHasher.java:72
			modSyncMaxJars, len(out.Jars), len(out.Disabled))
		// 仅截断活跃 jar（disabled 不参与哈希），保留 disabled 信息
		if len(out.Jars) > modSyncMaxJars {
			out.Jars = append([]string(nil), out.Jars[:modSyncMaxJars]...)
		}
	}
	return out
}

// modSyncListJars 旧接口：仅 .jar 列表（:86-88）。
func modSyncListJars(dir string) []string {
	return modSyncListJarsWithDisabled(dir).Jars
}

// modSyncSha1File / modSyncSha512File 对应 sha1(Path)/sha512(Path)（:101-107）。
func modSyncSha1File(path string) (string, error)   { return modSyncHashFile(path, "SHA-1") }
func modSyncSha512File(path string) (string, error) { return modSyncHashFile(path, "SHA-512") }

// modSyncSha1OfBytes 对应 sha1OfBytes(byte[])（:110-122）：字节数组的 sha1 hex，
// 用于 modsync 本地清单缓存的状态键。异常时 Java 返回长度的字符串（:120），
// Go 的 sha1 永不失败，故无对应分支。
func modSyncSha1OfBytes(data []byte) string {
	sum := sha1.Sum(data)
	return hex.EncodeToString(sum[:])
}

// modSyncHashFile 对应 private hash(Path, algorithm)（:124-146）：64KB 流式，
// 小写 hex。Java 的 `while ((n = in.read(buf)) > 0)` 与 Go 的 io.Read 循环等价。
func modSyncHashFile(path, algorithm string) (string, error) {
	var h hash.Hash
	switch algorithm {
	case "SHA-1":
		h = sha1.New()
	case "SHA-512":
		h = sha512.New()
	default:
		// Java: MessageDigest.getInstance 抛 NoSuchAlgorithmException → IOException
		// "digest unavailable: <algorithm>"（:126-130）
		return "", &modSyncIOError{msg: "digest unavailable: " + algorithm}
	}

	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	buf := make([]byte, modSyncHashBuf)
	for {
		n, rerr := f.Read(buf)
		if n > 0 {
			h.Write(buf[:n])
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return "", rerr
		}
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// modSyncIOError 对应 Java 侧包不了 IOException 的构造点（仅用于 digest 不可用）。
type modSyncIOError struct{ msg string }

func (e *modSyncIOError) Error() string { return e.msg }
