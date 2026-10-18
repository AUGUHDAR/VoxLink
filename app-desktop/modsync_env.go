package main

// modsync_env.go — ModSync 的"加载器 + 目录"唯一收敛点，逐句对应
// `fabric/.../modsync/ModSyncEnv.java`（29 行）。
//
// Java 侧只有四个静态量：
//   LOADER      = "fabric"                      （ModSyncEnv.java:13）
//   GAME_VERSION= VoxLinkConstants.GAME_VERSION  （ModSyncEnv.java:15，取本端 MC 版本）
//   getModsDir()= gameDir/mods                   （ModSyncEnv.java:21-23）
//   getCacheFile()= gameDir/voxlink/modsync_cache.json（ModSyncEnv.java:26-28）
//
// 桌面应用没有"当前游戏实例"这一运行期事实，只能由用户声明：这里把它做成
// 一份可选的实例清单（每个实例 = 名字 + 加载器 + MC 版本 + mods 目录），
// 活动实例即本端的 (loader, gameVersion, modsDir) 三元组 —— 与 Java 一样，
// 全部 modsync 代码只通过本文件取这四个量，跨加载器传播时只改这里。
// 没有引入任何"实例框架"：解析顺序、字段、缓存文件位置都在这里收敛。
//
// 持久化：不改动 settings.go（其他并行任务的所有权），设置另存一份
// `<配置目录>/VoxLinkApp/modsync.json`（与 settings.json 同目录，见 settings.go:24-36）。
// 需要把它并进 appSettings 的话见交付报告的 hunk。

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

// modSyncDefaultLoader = ModSyncEnv.java:13 的 LOADER 常量。
const modSyncDefaultLoader = "fabric"

// modSyncProtocolVersion = ModSyncManifestService.java:413 写进清单骨架的协议串。
const modSyncProtocolVersion = "modSync.v1"

// scope 取值：ModSyncManifestService.java:44-45。
const (
	modSyncScopeRequired = "required"
	modSyncScopeAll      = "all"
)

// modSyncInstance 一个可选的 MC 实例（mods 目录 + 加载器 + MC 版本）。
type modSyncInstance struct {
	Name        string `json:"name"`
	Loader      string `json:"loader"`
	GameVersion string `json:"gameVersion"`
	ModsDir     string `json:"modsDir"`
}

// modSyncSettings ModSync 侧的设置（对应 VoxLinkConfig 的两个开关 + 实例选择）：
// joinRequiredModsCheck 默认 true（VoxLinkConfig.java:48,97），
// hostModSyncPublish 默认 true（VoxLinkConfig.java:50,98）。
type modSyncSettings struct {
	Instances          []modSyncInstance `json:"instances"`
	ActiveInstance     string            `json:"activeInstance"`
	JoinRequiredMods   bool              `json:"joinRequiredModsCheck"`
	HostModSyncPublish bool              `json:"hostModSyncPublish"`
}

func defaultModSyncSettings() modSyncSettings {
	return modSyncSettings{
		Instances:          nil,
		ActiveInstance:     "",
		JoinRequiredMods:   true,
		HostModSyncPublish: true,
	}
}

// modSyncEnv 本端解析出的四元组（ModSyncEnv.java 的 1:1 形态）。
type modSyncEnv struct {
	Loader      string
	GameVersion string
	ModsDir     string
	CacheFile   string
}

// configured=false 表示用户还没声明任何实例：此时无法扫描/diff。
func (e modSyncEnv) configured() bool { return e.ModsDir != "" }

// modSyncState ModSync 全局状态单例：App 引用（供日志/事件/HTTP 出口）、
// 设置、房主侧单线程执行器、清单内存缓存。
// Java 侧这些都是 static 字段（ModSyncManifestService.java:33-38,105-106 等），
// 应用同一进程同一时刻只有一个房间会话，单例成立。
type modSyncState struct {
	mu  sync.Mutex
	app *App
	// dir 设置/缓存文件的落盘目录，跟着绑定它的 App.settingsPath 走。
	// 之前这里恒用 defaultSettingsPath()，于是任何一条 Go 测试只要调一次
	// SaveModSyncSettings，就会把测试实例写进玩家真实的 %APPDATA%/VoxLinkApp/modsync.json
	// （本轮就是这么翻车的）。绑定 App 时记下目录，派生文件全部落在同一处。
	dir      string
	settings modSyncSettings
	loaded   bool

	hostQueue   *modSyncTaskQueue
	hostOnce    sync.Once
	guestQueue  *modSyncTaskQueue
	guestOnce   sync.Once
	envOverride *modSyncEnv // 测试注入

	cachedStateHash string
	cachedRoot      *modSyncCacheRoot
	cacheMu         sync.Mutex
}

var modSyncSvc = &modSyncState{}

// modSyncCurrentApp 供 modsync_log.go 取事件出口；未绑定回落 log.Printf。
func modSyncCurrentApp() *App {
	modSyncSvc.mu.Lock()
	defer modSyncSvc.mu.Unlock()
	return modSyncSvc.app
}

// modSyncBind 由 *App 的绑定方法调用（这些方法定义在本包内，不改动 app.go）。
func (a *App) modSync() *modSyncState {
	modSyncSvc.mu.Lock()
	modSyncSvc.app = a
	if a.settingsPath != "" {
		modSyncSvc.dir = filepath.Dir(a.settingsPath)
	}
	modSyncSvc.mu.Unlock()
	modSyncSvc.ensureSettingsLoaded()
	return modSyncSvc
}

func (s *modSyncState) currentApp() *App {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.app
}

// ---- 设置持久化 ----

// modSyncSettingsPath = settings.json 同目录的 modsync.json。
// 保留无参版本给"没有 App 绑定"的场合兜底；有 App 时必须走 settingsFile()。
func modSyncSettingsPath() string {
	return filepath.Join(modSyncBaseDirOf(""), "modsync.json")
}

// modSyncBaseDirOf 返回派生文件该落在哪个目录：给了目录就用它（测试/绑定 App 的目录），
// 否则退回用户配置目录。
func modSyncBaseDirOf(dir string) string {
	if dir != "" {
		return dir
	}
	return filepath.Dir(defaultSettingsPath())
}

// settingsFile 读本状态绑定的 App 目录下的 modsync.json（调用方须持 s.mu）。
func (s *modSyncState) settingsFile() string {
	return filepath.Join(modSyncBaseDirOf(s.dir), "modsync.json")
}

func (s *modSyncState) ensureSettingsLoaded() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loaded {
		return
	}
	s.loaded = true
	s.settings = s.readSettingsLocked()
}

func (s *modSyncState) readSettingsLocked() modSyncSettings {
	def := defaultModSyncSettings()
	data, err := os.ReadFile(s.settingsFile())
	if err != nil {
		return def
	}
	var raw struct {
		Instances          []modSyncInstance `json:"instances"`
		ActiveInstance     *string           `json:"activeInstance"`
		JoinRequiredMods   *bool             `json:"joinRequiredModsCheck"`
		HostModSyncPublish *bool             `json:"hostModSyncPublish"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return def
	}
	out := def
	out.Instances = raw.Instances
	if raw.ActiveInstance != nil {
		out.ActiveInstance = *raw.ActiveInstance
	}
	// 缺键时保持默认 true（与 VoxLinkConfig.java:97-98 的 `? getAsBoolean() : true` 同构）
	if raw.JoinRequiredMods != nil {
		out.JoinRequiredMods = *raw.JoinRequiredMods
	}
	if raw.HostModSyncPublish != nil {
		out.HostModSyncPublish = *raw.HostModSyncPublish
	}
	return out
}

func (s *modSyncState) saveSettings(st modSyncSettings) error {
	s.mu.Lock()
	s.settings = st
	s.loaded = true
	s.mu.Unlock()

	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	file := s.settingsFile()
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return err
	}
	return os.WriteFile(file, data, 0o644)
}

func (s *modSyncState) snapshotSettings() modSyncSettings {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := s.settings
	out := modSyncSettings{
		ActiveInstance:     st.ActiveInstance,
		JoinRequiredMods:   st.JoinRequiredMods,
		HostModSyncPublish: st.HostModSyncPublish,
	}
	out.Instances = append([]modSyncInstance(nil), st.Instances...)
	return out
}

// isJoinRequiredModsCheck = VoxLinkConfig.isJoinRequiredModsCheck()（:281-283）
func (s *modSyncState) isJoinRequiredModsCheck() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.settings.JoinRequiredMods
}

// isHostModSyncPublish = VoxLinkConfig.isHostModSyncPublish()（:289-291）
func (s *modSyncState) isHostModSyncPublish() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.settings.HostModSyncPublish
}

// ---- 四元组解析（ModSyncEnv.java 的三个静态方法） ----

// env 得到本端 (loader, gameVersion, modsDir, cacheFile)。
// 未配置实例时返回 configured()=false 的空 env。
func (s *modSyncState) env() modSyncEnv {
	s.mu.Lock()
	ov := s.envOverride
	st := s.settings
	dir := s.dir
	s.mu.Unlock()
	if ov != nil {
		e := *ov
		if e.CacheFile == "" {
			e.CacheFile = modSyncCacheFileForDir(dir, e.ModsDir, st.ActiveInstance)
		}
		return e
	}

	ins, ok := s.activeInstance()
	if !ok {
		return modSyncEnv{}
	}
	loader := strings.TrimSpace(ins.Loader)
	if loader == "" {
		loader = modSyncDefaultLoader
	}
	modsDir := strings.TrimSpace(ins.ModsDir)
	return modSyncEnv{
		Loader:      loader,
		GameVersion: strings.TrimSpace(ins.GameVersion),
		ModsDir:     modsDir,
		CacheFile:   modSyncCacheFileForDir(s.dir, modsDir, ins.Name),
	}
}

func (s *modSyncState) activeInstance() (modSyncInstance, bool) {
	st := s.snapshotSettings()
	if len(st.Instances) == 0 {
		// 无显式实例时探测一个"启动器默认实例"（见 modSyncDetectDefaultInstance）
		if ins, ok := modSyncDetectDefaultInstance(); ok {
			return ins, true
		}
		return modSyncInstance{}, false
	}
	for _, in := range st.Instances {
		if in.Name == st.ActiveInstance {
			return in, true
		}
	}
	return st.Instances[0], true
}

// modSyncCacheFileFor 对应 ModSyncEnv.getCacheFile()（:26-28）：
// gameDir/voxlink/modsync_cache.json。mods 目录按启动器惯例是 gameDir/mods，
// 因此 base=="mods" 时取父目录当 gameDir；否则退回应用配置目录（不污染用户目录树）。
func modSyncCacheFileFor(modsDir, instanceName string) string {
	return modSyncCacheFileForDir("", modsDir, instanceName)
}

// modSyncCacheFileForDir 同 modSyncCacheFileFor，但"退回应用配置目录"那一支用传入的
// baseDir（= 绑定 App 的 settingsPath 目录），测试因此不会往玩家目录写缓存。
func modSyncCacheFileForDir(baseDir, modsDir, instanceName string) string {
	if modsDir != "" && strings.EqualFold(filepath.Base(modsDir), "mods") {
		return filepath.Join(filepath.Dir(modsDir), "voxlink", "modsync_cache.json")
	}
	name := "modsync_cache.json"
	if instanceName != "" {
		name = "modsync_cache_" + modSyncSafeName(instanceName) + ".json"
	}
	return filepath.Join(modSyncBaseDirOf(baseDir), name)
}

// modSyncSafeName 只留 [A-Za-z0-9_-]，其余换成 '-'，供缓存文件名使用。
func modSyncSafeName(s string) string {
	var sb strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '_', r == '-':
			sb.WriteRune(r)
		default:
			sb.WriteByte('-')
		}
	}
	out := sb.String()
	if len(out) > 40 {
		out = out[:40]
	}
	return out
}

// modSyncDetectDefaultInstance 启动器惯例路径（不是 Java 行为，Java 无需探测）：
// 只在本端存在该目录时把它当默认实例，避免"零配置即完全不可用"。
// Windows: %APPDATA%\.minecraft\mods；macOS: ~/Library/Application Support/minecraft\mods；
// Linux: ~/.minecraft/mods。MC 版本无法从目录得知，留空由用户填写。
func modSyncDetectDefaultInstance() (modSyncInstance, bool) {
	candidates := modSyncDefaultModsDirCandidates()
	for _, dir := range candidates {
		if st, err := os.Stat(dir); err == nil && st.IsDir() {
			return modSyncInstance{
				Name:        "default",
				Loader:      modSyncDefaultLoader,
				GameVersion: "",
				ModsDir:     dir,
			}, true
		}
	}
	return modSyncInstance{}, false
}

func modSyncDefaultModsDirCandidates() []string {
	switch runtime.GOOS {
	case "windows":
		if appdata, err := os.UserConfigDir(); err == nil && appdata != "" {
			return []string{filepath.Join(appdata, ".minecraft", "mods")}
		}
	case "darwin":
		if home, err := os.UserHomeDir(); err == nil && home != "" {
			return []string{filepath.Join(home, "Library", "Application Support", "minecraft", "mods")}
		}
	default:
		if home, err := os.UserHomeDir(); err == nil && home != "" {
			return []string{filepath.Join(home, ".minecraft", "mods")}
		}
	}
	return nil
}

// ---- 房主侧单线程执行器（ModSyncManifestService.java:33-37） ----

// Java：Executors.newSingleThreadExecutor(线程名 "VoxLink-ModSync-Host", daemon)，
// 任务队列无界（LinkedBlockingQueue）。Go 侧用 mutex + 切片队列 + 单 worker goroutine
// 复刻同一语义：串行执行、不丢任务、不阻塞提交方。
type modSyncTaskQueue struct {
	mu     sync.Mutex
	cond   chan struct{}
	tasks  []func()
	closed bool
}

func newModSyncTaskQueue() *modSyncTaskQueue {
	q := &modSyncTaskQueue{cond: make(chan struct{}, 1)}
	go q.run()
	return q
}

func (q *modSyncTaskQueue) submit(task func()) {
	if task == nil {
		return
	}
	q.mu.Lock()
	if q.closed {
		q.mu.Unlock()
		return
	}
	q.tasks = append(q.tasks, task)
	q.mu.Unlock()
	// 非阻塞唤醒
	select {
	case q.cond <- struct{}{}:
	default:
	}
}

func (q *modSyncTaskQueue) run() {
	for {
		q.mu.Lock()
		if len(q.tasks) == 0 && q.closed {
			q.mu.Unlock()
			return
		}
		if len(q.tasks) == 0 {
			q.mu.Unlock()
			select {
			case <-q.cond:
			case <-time.After(50 * time.Millisecond):
			}
			continue
		}
		task := q.tasks[0]
		q.tasks = q.tasks[1:]
		q.mu.Unlock()
		// Java 的每个任务体自带 try/catch(Throwable)（ModSyncManifestService.java:52-58），
		// 单个任务失败不得终止线程。
		func() {
			defer func() {
				if rec := recover(); rec != nil {
					modSyncLogWarn("host manifest task failed: {}", rec)
				}
			}()
			task()
		}()
	}
}

// hostExec 把任务交给房主侧单线程队列（懒启动）。
func (s *modSyncState) hostExec(task func()) {
	s.hostOnce.Do(func() { s.hostQueue = newModSyncTaskQueue() })
	if s.hostQueue != nil {
		s.hostQueue.submit(task)
	}
}

// guestExec 房客侧单线程队列：ModSyncGuestService.java:37-41 的
// "VoxLink-ModSync-Guest" daemon 执行器（同一套串行队列实现）。
func (s *modSyncState) guestExec(task func()) {
	s.guestOnce.Do(func() { s.guestQueue = newModSyncTaskQueue() })
	if s.guestQueue != nil {
		s.guestQueue.submit(task)
	}
}

// ---- 与 App 的接缝（不改动 app.go，方法定义在本包内） ----

// modSyncRoom 取当前"本端当房主"的房间码与 hostToken。
// 对应 ModSyncManifestService.java:81-85（room/code/token 任一为空即非当房主）。
func (a *App) modSyncRoom() (code, token string, ok bool) {
	a.mu.Lock()
	state, isHost := a.state, a.isHost
	code, token = a.code, a.token
	a.mu.Unlock()
	if !isHost || state != "hosting" || code == "" || token == "" {
		return "", "", false
	}
	return code, token, true
}

// modSyncCall 以模组侧的超时预算发一次信令 POST。
//
// 为什么要绕开 apiClient.post：api.go 的通用 client 有 10s 硬超时（api.go:20 httpTimeout），
// 而模组侧 /room/mods/answer 给 20s（ModSyncManifestService.java:94）、
// /room/mods/request 单次给 8s 且服务端长轮询 12s（ModSyncGuestService.java:299、
// server-go/modsmanifest.go:23）。这里复用 api.go 的 buildRequestURL 与信封解析
// （apiClient.finish），但用无整体超时的 a.api.upload 传输 + context 控制逐项预算，
// 从而把超时值保持成与 Java 逐字一致。
func (a *App) modSyncCall(route string, body any, timeout time.Duration) (map[string]any, error) {
	reqURL, err := buildRequestURL(a.baseURL(), route, nil)
	if err != nil {
		return nil, err
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, &APIError{Code: "INTERNAL", Message: "请求体序列化失败: " + err.Error()}
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, reqURL, strings.NewReader(string(payload)))
	if err != nil {
		return nil, &APIError{Code: "NETWORK", Message: "构造请求失败: " + err.Error()}
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", httpUserAgent)
	req.Header.Set("X-VoxLink-Version", appVersion)

	resp, err := a.api.upload.Do(req)
	if err != nil {
		return nil, &APIError{Code: "NETWORK", Message: "网络请求失败: " + err.Error()}
	}
	raw, err := a.api.finish(resp)
	if err != nil {
		return nil, err
	}
	data := map[string]any{}
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &data); err != nil {
			return map[string]any{}, nil
		}
	}
	return data, nil
}

// modSyncCapabilities 对齐 SignalingClient.java:202-211：
// 上报开关（hostModSyncPublish）关闭时不声明 modSyncV1 ——
// 房客据此与旧版房主一样秒判"无清单"，零等待直通。
func (a *App) modSyncCapabilities() []string {
	s := a.modSync()
	out := append([]string(nil), appCapabilities...)
	if s.isHostModSyncPublish() {
		out = append(out, modSyncCapName)
	}
	return out
}

// modSyncCapName = ProtocolNegotiator.CAP_MOD_SYNC_V1（ProtocolNegotiator.java:24）。
const modSyncCapName = "modSyncV1"

// modSyncIsVoxLinkCode 对应 RoomCodeRouter.isVoxLinkCode（RoomCodeRouter.java:8,24-26）：
// ^[A-HJ-NP-Z2-9]{6}$（不含 I/O/0/1）。
func modSyncIsVoxLinkCode(code string) bool {
	if code == "" {
		return false
	}
	upper := strings.ToUpper(code)
	if len(upper) != 6 {
		return false
	}
	for _, r := range upper {
		switch {
		case r >= 'A' && r <= 'H':
		case r >= 'J' && r <= 'N':
		case r >= 'P' && r <= 'Z':
		case r >= '2' && r <= '9':
		default:
			return false
		}
	}
	return true
}

// modSyncOsStat 薄封装：便于 diff 侧统一处理"文件不存在=跳过"。
func modSyncOsStat(path string) (os.FileInfo, error) {
	return os.Stat(path)
}

// ---- 绑定方法（设置面） ----

// modSyncSettingsView 前端可见的投影：活动实例 + 两个开关。
type modSyncSettingsView struct {
	Configured         bool              `json:"configured"`
	Instances          []modSyncInstance `json:"instances"`
	ActiveInstance     string            `json:"activeInstance"`
	JoinRequiredMods   bool              `json:"joinRequiredModsCheck"`
	HostModSyncPublish bool              `json:"hostModSyncPublish"`
	ModsDir            string            `json:"modsDir"`
	Loader             string            `json:"loader"`
	MCVersion          string            `json:"mcVersion"`
	CacheFile          string            `json:"cacheFile"`
}

// GetModSyncSettings 读回 ModSync 设置与当前生效的四元组。
func (a *App) GetModSyncSettings() string {
	s := a.modSync()
	env := s.env()
	st := s.snapshotSettings()
	return wrapOK(modSyncSettingsView{
		Configured:         env.configured(),
		Instances:          st.Instances,
		ActiveInstance:     st.ActiveInstance,
		JoinRequiredMods:   st.JoinRequiredMods,
		HostModSyncPublish: st.HostModSyncPublish,
		ModsDir:            env.ModsDir,
		Loader:             env.Loader,
		MCVersion:          env.GameVersion,
		CacheFile:          env.CacheFile,
	})
}

// SaveModSyncSettings 保存实例清单与开关；实例变化会让内存缓存失效
// （缓存键本身是 mods 状态哈希，天然随目录变化而变，这里只是省一次无谓命中）。
func (a *App) SaveModSyncSettings(settingsJSON string) string {
	var req modSyncSettingsView
	if err := parseParams(settingsJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	s := a.modSync()
	instances := make([]modSyncInstance, 0, len(req.Instances))
	for _, in := range req.Instances {
		in.Name = strings.TrimSpace(in.Name)
		in.ModsDir = strings.TrimSpace(in.ModsDir)
		in.Loader = strings.TrimSpace(in.Loader)
		in.GameVersion = strings.TrimSpace(in.GameVersion)
		if in.Name == "" || in.ModsDir == "" {
			continue
		}
		instances = append(instances, in)
	}
	active := strings.TrimSpace(req.ActiveInstance)
	if instances == nil {
		instances = []modSyncInstance{}
	}
	if active == "" && len(instances) > 0 {
		active = instances[0].Name
	}

	st := modSyncSettings{
		Instances:          instances,
		ActiveInstance:     active,
		JoinRequiredMods:   req.JoinRequiredMods,
		HostModSyncPublish: req.HostModSyncPublish,
	}
	if err := s.saveSettings(st); err != nil {
		return wrapErr("IO", err.Error())
	}
	s.cacheMu.Lock()
	s.cachedStateHash = ""
	s.cachedRoot = nil
	s.cacheMu.Unlock()
	return a.GetModSyncSettings()
}
