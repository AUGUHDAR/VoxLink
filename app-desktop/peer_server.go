package main

// peer_server.go — 本机 /info 服务（逐句对齐模组 network/PeerServer.java，1.2.0，197 行）。
//
// 形态：只绑回环、随机端口（:60 HttpServer.create(getLoopbackAddress(), 0)），
// GET /info?token=<房间令牌> 校验通过后返回房主侧房间与版本信息，失败一律 403 +
// 固定错误体（:89）。令牌比较走常量时间（:77-78 的安全修复：MessageDigest.isEqual
// 防逐字节猜测房间令牌的 timing 侧信道），本端用 crypto/subtle 同一算法。
//
// JSON 体是 Gson 紧凑序列化的产物：字段顺序即 addProperty 的插入顺序（:165-195），
// 且带 Gson 默认的 HTML-safe 转义（房间名里的 = & < > ' 会写成 \u00xx）。
// 因此这里复用 overlay.go 的保序 JSON（ovObject）而不是 struct/map，
// 保证与模组逐字节一致（同一份信息，两种客户端形态）。
//
// 模组侧的三个缓存字段（cachedGameVersion / cachedProtocolVersion / cachedMods，:37-39）
// 由 refreshCache() 从 Minecraft/ViaCompat/FabricLoader 现取（:127-163）。桌面端没有
// Minecraft 运行时，故本端把它做成可注入的 provider（setPeerCacheProvider），
// 默认值与模组"取不到"时的初值完全一致：""、0、空表、geyserLoaded=false。
// 注入点（用 modsync 的实例版本与 mods 清单来填）是 app 侧的取舍，见交付报告。

import (
	"context"
	"crypto/subtle"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	peerInfoPath      = "/info"                       // :63
	peerUnauthorized  = `{"error":"unauthorized"}`    // :89 逐字节
	peerContentType   = "application/json"            // :82/:90
	peerStopGraceSecs = 1                             // :111 httpServer.stop(1)
	peerRoomMissing   = "RoomManager not initialized" // :169
)

// peerModEntry 对应 Java 的 LinkedHashMap{"id","version"}（:154-157），键序一致。
type peerModEntry struct {
	id      string
	version string
}

// peerCacheValues refreshCache() 写进去的三个缓存 + 两个常量项（:190-194）。
type peerCacheValues struct {
	GameVersion           string // Java mc.getLaunchedVersion()
	ServerProtocolVersion int    // Java ViaCompat.getServerProtocolVersion()
	Mods                  []peerModEntry
	GeyserLoaded          bool
	VoxLinkVersion        string // Java VoxLinkMod.MOD_VERSION
}

var peerSrv = struct {
	mu      sync.Mutex
	srv     *http.Server
	ln      net.Listener
	port    int
	app     *App
	cache   peerCacheValues
	started bool
}{port: -1} // :35 static int port = -1

// peerCacheProvider 由 app 注入（默认不注入=保持 Java 初值）。
var peerCacheProvider atomic.Pointer[func() peerCacheValues]

// setPeerCacheProvider 挂上数据来源（见交付报告的接线 hunk）。
func setPeerCacheProvider(fn func() peerCacheValues) {
	p := new(func() peerCacheValues)
	*p = fn
	peerCacheProvider.Store(p)
}

// startPeerServer 对应 :54-107：已启动则直接返回既有端口（幂等、同步）。
// 取缓存与打日志都排在锁外做：provider 会回头拿 a.mu，而 /info 那条路是
// 先 a.mu 再 peerSrv.mu（peerHandleInfo→peerBuildInfo），锁序反了会成环。
func startPeerServer(app *App) int {
	port, fresh := startPeerServerLocked(app)
	if fresh {
		refreshPeerCache()
		if app != nil {
			app.netLog("info", "Peer server started, port "+strconv.Itoa(port))
		}
	}
	return port
}

func startPeerServerLocked(app *App) (int, bool) {
	peerSrv.mu.Lock()
	defer peerSrv.mu.Unlock()
	if peerSrv.srv != nil {
		return peerSrv.port, false
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		// Java: catch(IOException) → LOGGER.error("Peer server start failed: {}") → return -1
		peerSrv.port = -1
		if app != nil {
			app.netLog("error", "Peer server start failed: "+err.Error())
		}
		return -1, false
	}
	mux := http.NewServeMux()
	mux.HandleFunc(peerInfoPath, peerHandleInfo)
	srv := &http.Server{Handler: mux}

	peerSrv.ln = ln
	peerSrv.srv = srv
	peerSrv.app = app
	peerSrv.port = ln.Addr().(*net.TCPAddr).Port
	peerSrv.started = true

	go func() {
		if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
			if a := peerServerApp(); a != nil {
				a.netLog("error", "Peer server serve error: "+err.Error())
			}
		}
	}()
	return peerSrv.port, true
}

// stopPeerServer 对应 :109-121：stop(1) 给在途请求 1s 收尾，然后端口复位 -1。
func stopPeerServer() {
	peerSrv.mu.Lock()
	srv := peerSrv.srv
	peerSrv.mu.Unlock()
	if srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), peerStopGraceSecs*time.Second)
		_ = srv.Shutdown(ctx)
		cancel()
		_ = srv.Close()
		peerSrv.mu.Lock()
		peerSrv.srv = nil
		peerSrv.ln = nil
		peerSrv.port = -1
		peerSrv.app = nil
		peerSrv.started = false
		peerSrv.mu.Unlock()
	}
}

// peerServerPort 对应 :123-125 getPort()（未启动为 -1）。
func peerServerPort() int {
	peerSrv.mu.Lock()
	defer peerSrv.mu.Unlock()
	return peerSrv.port
}

func peerServerApp() *App {
	peerSrv.mu.Lock()
	defer peerSrv.mu.Unlock()
	return peerSrv.app
}

// peerServerStarted 仅在诊断里用（避免"已声明未使用"的静态告警）。
func peerServerStarted() bool {
	peerSrv.mu.Lock()
	defer peerSrv.mu.Unlock()
	return peerSrv.started
}

// refreshPeerCache 对应 :127-163：有 provider 就取，没有就保留现值
// （模组取不到时同样保留旧缓存 —— 三个 try 块各自吞异常）。
func refreshPeerCache() {
	provider := peerCacheProvider.Load()
	if provider == nil {
		return
	}
	v := (*provider)()
	peerSrv.mu.Lock()
	peerSrv.cache = v
	peerSrv.mu.Unlock()
}

// peerHandleInfo 对应 :63-97。
func peerHandleInfo(w http.ResponseWriter, r *http.Request) {
	app := peerServerApp()
	token, tokenPresent := peerQueryToken(r.URL.RawQuery)

	var room map[string]any
	var roomToken string
	appHasRoomManager := false
	if app != nil {
		appHasRoomManager = true
		room, roomToken = app.peerRoomAndToken()
	}

	// 安全修复：常量时间比较（:77-78）。room 不存在 / 没带 token / 比较不等 → 403。
	tokenValid := appHasRoomManager && room != nil && tokenPresent &&
		constantTimeEqualUTF8(token, roomToken)

	if !tokenValid {
		body := []byte(peerUnauthorized)
		w.Header().Set("Content-Type", peerContentType)
		w.Header().Set("Content-Length", strconv.Itoa(len(body)))
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write(body)
		return
	}

	info := peerBuildInfo(app, room)
	data := info.marshalGson()
	w.Header().Set("Content-Type", peerContentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// peerBuildInfo 对应 :165-195（字段顺序与 addProperty 调用顺序一致）。
func peerBuildInfo(app *App, room map[string]any) *ovObject {
	info := newOVObject()
	if app == nil {
		// Java 的 RoomManager==null 分支：整份信息只有一个 error 键（:168-171）
		info.putString("error", peerRoomMissing)
		return info
	}
	peerSrv.mu.Lock()
	cache := peerSrv.cache
	peerSrv.mu.Unlock()

	if room != nil {
		info.putBool("hasRoom", true)
		info.putString("code", roomStr(room, "code"))
		info.putString("name", roomStr(room, "name"))
		info.putInt("currentPlayers", roomInt(room, "currentPlayers"))
		info.putInt("maxPlayers", roomInt(room, "maxPlayers"))
		info.putBool("hasPassword", jbool(room, "hasPassword"))
		category, ok := topoStr(room, "category")
		if !ok {
			category = "other" // :181 的默认值
		}
		info.putString("category", category)
		info.putString("natType", roomStr(room, "natType"))
		info.putInt("hostPort", roomInt(room, "hostPort"))
		info.putInt("bedrockPort", roomInt(room, "bedrockPort"))
		info.putInt("protocolVersion", roomInt(room, "serverProtocolVersion"))
	} else {
		info.putBool("hasRoom", false)
	}

	info.putString("gameVersion", cache.GameVersion)
	info.putInt("serverProtocolVersion", cache.ServerProtocolVersion)
	info.putBool("geyserLoaded", cache.GeyserLoaded)
	voxVersion := cache.VoxLinkVersion
	if voxVersion == "" {
		voxVersion = appVersion // 模组取 VoxLinkMod.MOD_VERSION（:193）
	}
	info.putString("voxlinkVersion", voxVersion)
	info.put("mods", peerModsJSON(cache.Mods))
	return info
}

func peerModsJSON(mods []peerModEntry) []any {
	out := make([]any, 0, len(mods))
	for _, m := range mods {
		entry := newOVObject()
		entry.putString("id", m.id)
		entry.putString("version", m.version)
		out = append(out, entry)
	}
	return out
}

// peerQueryToken 对应 :64-74：按 & 切、按第一个 = 分左右，取名为 token 的项并 URL 解码。
// 解码失败在 Java 会抛出并让该次请求以异常收场（HttpServer 回 500，不吐数据），
// 本端折算成"未通过校验"（同样不吐数据，且不比 Java 宽松）。
func peerQueryToken(rawQuery string) (string, bool) {
	if rawQuery == "" {
		return "", false
	}
	for _, param := range strings.Split(rawQuery, "&") {
		kv := strings.SplitN(param, "=", 2)
		if len(kv) == 2 && kv[0] == "token" {
			decoded, err := url.QueryUnescape(kv[1])
			if err != nil {
				return "", false
			}
			return decoded, true
		}
	}
	return "", false
}

// constantTimeEqualUTF8 MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8)) 的等价物。
func constantTimeEqualUTF8(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// peerRoomAndToken 当前房间视图与本机令牌（RoomManager.getCurrentRoom() + RoomInfo.getToken()）。
func (a *App) peerRoomAndToken() (map[string]any, string) {
	a.mu.Lock()
	room := a.room
	token := a.token
	if room != nil {
		room = copyMap(room)
	}
	a.mu.Unlock()
	return room, token
}
