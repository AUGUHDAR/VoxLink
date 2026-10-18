package main

// fed_state.go — 互通（fed / Happy Link，模组 1.2.0）状态层。
//
// 逐字段对齐模组侧两处权威：
//   - room/RoomInfo.java:53-125（fedSource/fedAddress + fedHost/fedTunnelPort/fedToken/fedPort/fedCode
//     与 isHappyLink()/isFedMirror() 两个谓词）
//   - room/RoomManager.java:358-369（create 回包 fed 解析 + 镜像门）、766-771（join 回包 source/fedAddress）、
//     1113-1140（startFedTunnel/stopFedTunnel、fedCode 回退在 :1116）
//   - terracotta/RoomCodeRouter.java:8-10,28-30（房码路由正则）
//
// app 侧房间状态就是 App.room 这个 map（app.go:45），没有 RoomInfo 类，
// 因此这里用同一套键名把 fed 字段挂进 map，谓词函数与 Java 判据一一对应。

import (
	"fmt"
	"regexp"
	"strings"
	"sync"
)

// fed 字段在房间 map 里的键名（与 Java RoomInfo 字段同名，便于逐字段核对）。
const (
	fedKeyHost       = "fedHost"       // RoomInfo.java:77 fedHost
	fedKeyTunnelPort = "fedTunnelPort" // RoomInfo.java:78 fedTunnelPort
	fedKeyToken      = "fedToken"      // RoomInfo.java:79 fedToken
	fedKeyPort       = "fedPort"       // RoomInfo.java:80 fedPort
	fedKeyCode       = "fedCode"       // RoomInfo.java:81 fedCode
	fedKeySource     = "source"        // RoomInfo.java:53 fedSource（wire 名）
	fedKeyAddress    = "fedAddress"    // RoomInfo.java:54 fedAddress（wire 名）
	fedKeyCodeField  = "code"          // RoomInfo.java:13 code（本房码，fedCode 回退目标）
)

// fedSourceHappyLink 是加入侧唯一的互通判定值：RoomInfo.java:57
// "happylink".equals(this.fedSource) —— 大小写敏感、精确相等。
const fedSourceHappyLink = "happylink"

// 房码正则（RoomCodeRouter.java:8,10）。FED_CODE 恒 7 位、宽于服务端 hlImportCode 的实际产出
// （server-go/hlmaster.go:907-914 "HL"+前5位），刻意不收紧：多出来的形态交给服务端判 ROOM_NOT_FOUND。
var (
	fedVoxLinkCodeRe = regexp.MustCompile(`^[A-HJ-NP-Z2-9]{6}$`)
	fedCodeRe        = regexp.MustCompile(`^HL[A-Z0-9]{5}$`)
)

// IsFedCode 对应 RoomCodeRouter.isFedCode（:28-30）：先 ToUpper(Locale.ROOT) 再匹配 ^HL[A-Z0-9]{5}$。
// Go 的 strings.ToUpper 与 locale 无关，等价于 Locale.ROOT。
func IsFedCode(code string) bool {
	return fedCodeRe.MatchString(strings.ToUpper(code))
}

// IsVoxLinkCode 对应 RoomCodeRouter.isVoxLinkCode（:24-26）：6 位 VoxLink 码。
func IsVoxLinkCode(code string) bool {
	return fedVoxLinkCodeRe.MatchString(strings.ToUpper(code))
}

// ValidateJoinRoomCode 是加入页的房码放行判据，对应 RoomManager.java:655-661：
//
//	if (!normalizedCode.matches("^[A-HJ-NP-Z2-9]{6}$") && !RoomCodeRouter.isFedCode(normalizedCode)) → invalid
//
// 即 6 位 VoxLink 码或 7 位互通码都放行，由服务端 join 分流。
func ValidateJoinRoomCode(code string) bool {
	upper := strings.ToUpper(code)
	return IsVoxLinkCode(upper) || IsFedCode(upper)
}

// FedRouteName 对应 RoomCodeRouter.getRouteName（:36-42）的互通分支取名用（日志/诊断）。
// app 侧没有陶瓦路径，故只区分 HappyLink / VoxLink / Unknown。
func FedRouteName(code string) string {
	if IsFedCode(code) {
		return "HappyLink"
	}
	if IsVoxLinkCode(code) {
		return "VoxLink"
	}
	return "Unknown"
}

// ---- 房主侧：create 回包 fed 解析 ----

// applyFedCreatePayload 照抄 RoomManager.java:359-369：仅当 fed 是 JSON 对象时逐字段写入房间 map。
// 缺省值与 Java 逐字对齐：host=""、tunnelPort=0、token=""、port=0、code=""。
// 返回 isFedMirror()（RoomInfo.java:83-85）判定，即模组 :366 的启动门。
func applyFedCreatePayload(room map[string]any, fedAny any) bool {
	if room == nil {
		return false
	}
	fed, ok := fedAny.(map[string]any)
	if !ok {
		// Java: response.data.has("fed") && get("fed").isJsonObject() 为假时整块跳过
		return false
	}
	room[fedKeyHost] = fedStringOf(fed, "host")
	room[fedKeyTunnelPort] = fedIntOf(fed, "tunnelPort")
	room[fedKeyToken] = fedStringOf(fed, "token")
	room[fedKeyPort] = fedIntOf(fed, "port")
	room[fedKeyCode] = fedStringOf(fed, "code")
	return roomIsFedMirror(room)
}

// fedStringOf：键缺失或为 null → ""（RoomInfo 的 setter 把 null 归一成空串，:91-93,107-109,123-125）。
func fedStringOf(m map[string]any, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

// fedIntOf：键缺失 → 0（RoomManager.java:362,364）。
// 注意：Java 对 JSON null 走 getAsInt 会抛异常（那两行没有 isJsonNull 判定），
// 服务端实测不发 null；Go 侧按 0 处理，不复制这个崩溃路径。
func fedIntOf(m map[string]any, key string) int {
	return roomInt(m, key)
}

// roomIsFedMirror 对应 RoomInfo.isFedMirror()（RoomInfo.java:83-85）：
// fedPort > 0 && !fedToken.isEmpty()。刻意不校验 fedHost / fedCode（见规格 D7）：
// Go 租户模式下 fed 无 code 键（server-go/hlmaster.go:1028-1033），fedCode 恒空但仍需起隧道。
func roomIsFedMirror(room map[string]any) bool {
	if room == nil {
		return false
	}
	return roomInt(room, fedKeyPort) > 0 && roomStr(room, fedKeyToken) != ""
}

// fedHelloCode 对应 RoomManager.java:1116：
// String fedCode = roomInfo.getFedCode().isEmpty() ? roomInfo.getCode() : roomInfo.getFedCode();
// fedCode 在模组里只有这一个用途（HELLO 的第 1 字段），不参与 UI 展示与 join。
func fedHelloCode(room map[string]any) string {
	c := roomStr(room, fedKeyCode)
	if c == "" {
		return roomStr(room, fedKeyCodeField)
	}
	return c
}

// ---- 房客侧：join 回包 source / fedAddress ----

// applyFedJoinPayload 对应 RoomManager.java:766-771：两个键缺失/null 时保持 RoomInfo 的初值 ""。
// app 的 room map 直接来自服务端 JSON，这里只做归一，保证前端拿到的键恒存在。
func applyFedJoinPayload(room map[string]any) {
	if room == nil {
		return
	}
	room[fedKeySource] = roomStr(room, fedKeySource)
	room[fedKeyAddress] = roomStr(room, fedKeyAddress)
}

// roomFedSource 对应 RoomInfo.getFedSource()（:60-62）。
func roomFedSource(room map[string]any) string { return roomStr(room, fedKeySource) }

// roomFedAddress 对应 RoomInfo.getFedAddress()（:68-70）。
func roomFedAddress(room map[string]any) string { return roomStr(room, fedKeyAddress) }

// roomIsHappyLink 对应 RoomInfo.isHappyLink()（:56-58）：精确等于 "happylink"，大小写敏感。
func roomIsHappyLink(room map[string]any) bool {
	if room == nil {
		return false
	}
	return fedSourceHappyLink == roomFedSource(room)
}

// ---- 房主侧隧道生命周期（RoomManager.java:1111-1140） ----

// fedTunnelRegistry 一 App 一条隧道。模组的隧道挂在 RoomManager 实例字段上（:1111），
// 本轮 App 结构体不可改，用同语义的登记表替代：start 前必 stop 旧的，take 时摘除引用。
type fedTunnelRegistry struct {
	mu sync.Mutex
	m  map[*App]*FedTunnelClient
}

func (r *fedTunnelRegistry) put(a *App, t *FedTunnelClient) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.m == nil {
		r.m = make(map[*App]*FedTunnelClient)
	}
	r.m[a] = t
}

func (r *fedTunnelRegistry) take(a *App) *FedTunnelClient {
	r.mu.Lock()
	defer r.mu.Unlock()
	t := r.m[a]
	delete(r.m, a)
	return t
}

func (r *fedTunnelRegistry) get(a *App) *FedTunnelClient {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.m[a]
}

var fedHostTunnels = &fedTunnelRegistry{}

// fedTunnelForApp 测试/诊断用：取当前登记的隧道（不做所有权转移）。
func fedTunnelForApp(a *App) *FedTunnelClient { return fedHostTunnels.get(a) }

// startFedTunnelForRoom 对应 RoomManager.startFedTunnel（:1113-1132）：
// 先 stopFedTunnel()，再用 fed 凭据起新隧道；HELLO 用 fedHelloCode 的回退结果，
// localPort 是"本机 MC 监听端口"（模组传 ctx.port，:367 与 :352 同源；规格 B7）。
func (a *App) startFedTunnelForRoom(room map[string]any, localPort int) {
	a.stopFedTunnel() // :1114
	code := fedHelloCode(room)
	t := NewFedTunnelClient(
		roomStr(room, fedKeyHost),       // getFedHost()
		roomInt(room, fedKeyTunnelPort), // getFedTunnelPort()
		code,
		roomStr(room, fedKeyToken), // getFedToken()
		localPort,
		a.fedHostAlive(),
		a.fedPlayerCount(),
		a.fedTunnelStatus,
	)
	fedHostTunnels.put(a, t)
	t.start() // :1129
	// :1130-1131 的日志用的是本房码 roomInfo.getCode()，不是 fedCode
	a.netLog("info", fmt.Sprintf("[fed-tunnel] started code=%s host=%s tunnelPort=%d localPort=%d",
		roomStr(room, fedKeyCodeField), roomStr(room, fedKeyHost), roomInt(room, fedKeyTunnelPort), localPort))
}

// startFedTunnel 用当前会话快照启动隧道（会话已建立时的便捷入口，等价于上面的显式传参）。
func (a *App) startFedTunnel(localPort int) {
	a.startFedTunnelForRoom(a.snapshotRoom(), localPort)
}

// stopFedTunnel 对应 RoomManager.stopFedTunnel（:1134-1140）：摘引用后 stop()，幂等。
// 模组的调用点是 stopScheduledTasks()（:1105）——退房/关房/权威掉线的统一拆解路径，
// app 侧等价落点是 App.clearSession（会话 goroutine 结束时必走）。
func (a *App) stopFedTunnel() {
	t := fedHostTunnels.take(a) // :1135-1136
	if t != nil {
		t.stop() // :1138
	}
}

// fedHostAlive 是模房源谓词 `mc != null && mc.getSingleplayerServer() != null && mc.level != null`
// （RoomManager.java:1119）在 app 侧的对应物：app 不是单人世界，宿主即"本端房间会话仍存活"。
// 规格 §1.10 明确要求此谓词不可留 null。
func (a *App) fedHostAlive() func() bool {
	return func() bool {
		a.mu.Lock()
		s := a.session
		a.mu.Unlock()
		return s != nil && !s.isDone()
	}
}

// fedPlayerCount 是模组 IntegratedServer.getPlayerCount()（RoomManager.java:1120-1123）的对应物。
// app 只做转发、MC 服务端在进程外，能观测到的只有本会话已桥接的房客数（见 connEngine.fedHostPeerCount）。
func (a *App) fedPlayerCount() func() int {
	return func() int { return a.engine.fedHostPeerCount() }
}

// fedHostPeerCount 本会话在册的房客数（hPeers 由 bindSession 建、teardown 置 nil）。
// 语义偏差已在移植报告中记录：模组读的是单人世界人数（含房主），app 只能观测房客隧道数。
func (e *connEngine) fedHostPeerCount() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.hPeers)
}

// fedTunnelStatus 对应模组的 Listener（RoomManager.java:1124-1128）：只写日志，fatal 用 warn 级。
// 隧道状态串是硬编码中文、不进 lang 文件（规格 6.3 / D4），这里同样只入日志，
// 经 App.netLog 出口做统一消敏（IP/端口在事件里打码）。
func (a *App) fedTunnelStatus(message string, fatal bool) {
	if fatal {
		a.netLog("warn", message)
		return
	}
	a.netLog("info", message)
}
