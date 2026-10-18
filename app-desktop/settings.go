package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

// appSettings 持久化设置，存于 %APPDATA%/VoxLinkApp/settings.json。
type appSettings struct {
	ServerURL string `json:"serverUrl"`
	Theme     string `json:"theme"`
	// ClientTag 启动器/用户来源标记（如 kamucl），随 create 上报服务端供后台展示
	// （server-go/roommanager.go:409-413 同款校验）；默认空 = 不上报。
	ClientTag string `json:"clientTag"`
	// RelayEnabled 与模组 VoxLinkConfig.relayEnabled 同一个开关、同一个默认值 true（:41/:64/:92）。
	// 一个开关管两件事："为别人中继"与"用别人的中继"（模组 tryRelay 在关闭时以
	// connection.relay_unavailable 直接退出，CM:10520-10526），它不管 TURN（fireAutoTurn 无此判，CM:12830-12837）。
	// 用 *bool 是为了区分"老配置文件没这一项"（nil→按默认 true）与用户显式关掉。
	RelayEnabled *bool `json:"relayEnabled"`
	// AutoUPnP 与模组 VoxLinkConfig.autoUPnP 同一个开关、同一个默认值 true（VoxLinkConfig.java:35/58/86）。
	// 房主建房时是否向路由器请求端口映射（RoomManager.java:166 的那道 if）。
	AutoUPnP *bool `json:"autoUPnP"`
	// Language 界面语言："auto"（默认，跟随系统/WebView 语言）或 lang/ 下的具体代码（zh_cn 等）。
	// 只有带界面的版本用得上，CLI 按用户裁决不做多语言。
	Language string `json:"language"`
}

func defaultSettings() appSettings {
	on := true
	return appSettings{
		ServerURL:    defaultServerURL,
		Theme:        "light",
		ClientTag:    "",
		RelayEnabled: &on,
		Language:     "auto",
	}
}

// relayEnabled 当前中继开关（nil 表示历史配置没这一项，按模组默认 true）。
func (a *App) relayEnabled() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.settings.RelayEnabled == nil {
		return true
	}
	return *a.settings.RelayEnabled
}

// defaultSettingsPath 返回设置文件路径（基于 os.UserConfigDir，Windows 即 %APPDATA%）。
func defaultSettingsPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		// 兜底：用户目录下
		home, herr := os.UserHomeDir()
		if herr != nil {
			return "VoxLinkApp-settings.json"
		}
		dir = home
	}
	return filepath.Join(dir, "VoxLinkApp", "settings.json")
}

// loadSettings 从磁盘加载设置，缺失/损坏时回落默认值。
// 契约：serverUrl 恒为默认（磁盘上的历史自定义值一律忽略，只应用 theme）。
func (a *App) loadSettings() {
	s := defaultSettings()
	data, err := os.ReadFile(a.settingsPath)
	if err == nil {
		_ = json.Unmarshal(data, &s)
	}
	s.ServerURL = defaultServerURL
	if s.Theme == "" {
		s.Theme = "light"
	}
	s.ClientTag = normalizeClientTag(s.ClientTag)
	a.mu.Lock()
	a.settings = s
	a.mu.Unlock()
}

// saveSettings 把当前设置写盘。
func (a *App) saveSettings() error {
	a.mu.Lock()
	s := a.settings
	a.mu.Unlock()

	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return fmt.Errorf("设置序列化失败: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(a.settingsPath), 0o755); err != nil {
		return fmt.Errorf("创建设置目录失败: %w", err)
	}
	if err := os.WriteFile(a.settingsPath, data, 0o644); err != nil {
		return fmt.Errorf("写入设置文件失败: %w", err)
	}
	return nil
}

// validateServerURL 校验服务器地址（允许 http/https）。
func validateServerURL(u string) bool {
	u = strings.TrimSpace(u)
	if u == "" {
		return false
	}
	lower := strings.ToLower(u)
	return strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://")
}

// clientTagMaxRunes 标签长度上限（按"字"算，中文一个字算一个：后台就是一行徽章，
// 再长会挤掉房名）。CLI 与 SaveSettings 都以此拒绝，服务端同值兜底。
const clientTagMaxRunes = 10

// clientTagPattern 清洗后的字符集：小写字母/数字/下划线/连字符 + 任意 CJK。
// 空格保留在"词"之间（启动器名常带空格，如"PCL2"→"pcl2"、"My Launcher"），
// 但首尾空白先 Trim。控制字符、引号、尖括号一律剔掉——后台是 HTML 渲染，
// 就算模板会转义，也不该让这类字符进入存储。
var clientTagPattern = regexp.MustCompile(`[^0-9a-z_\-\p{Han} ]`)

// normalizeClientTag 是"静默"版：与服务端 roommanager.normalizeClientTag 同一套口径
// （清洗 + 截到 10 字），用于读磁盘上可能被手改过的设置值 —— 那里不该因为一个脏标签
// 就整个不上报，截短了照样是有效标识。
func normalizeClientTag(tag string) string {
	r := []rune(cleanClientTag(tag))
	if len(r) > clientTagMaxRunes {
		return string(r[:clientTagMaxRunes])
	}
	return string(r)
}

// sanitizeClientTag 是 normalizeClientTag 的报错版：CLI 建房与设置写回用它，
// 因为面向开发者的入口必须告诉他"你传的标签为什么没生效"（超 10 个字直接拒绝）。
func sanitizeClientTag(tag string) (string, error) {
	clean := cleanClientTag(tag)
	if n := utf8.RuneCountInString(clean); n > clientTagMaxRunes {
		return "", fmt.Errorf("clientTag 最多 %d 个字（当前 %d 个）", clientTagMaxRunes, n)
	}
	return clean, nil
}

// cleanClientTag 只做字符清洗与空格归一，不管长度。
func cleanClientTag(tag string) string {
	t := strings.ToLower(strings.TrimSpace(tag))
	t = clientTagPattern.ReplaceAllString(t, "")
	return strings.Join(strings.Fields(t), " ") // 连续空格压成一个
}

// validateRoomCode 校验 6 位房间码字符集。
func validateRoomCode(code string) bool {
	if len(code) != 6 {
		return false
	}
	const charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	for _, c := range code {
		if !strings.ContainsRune(charset, c) {
			return false
		}
	}
	return true
}
