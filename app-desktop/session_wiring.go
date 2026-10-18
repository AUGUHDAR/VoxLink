package main

// session_wiring.go — 建房 / 加入 / 退出这三个时刻把外围模块接上。
// 模组在这些位置是直接调用而不是可选路径，本端此前只落了模块没落调用点，
// 结果是 UPnP 端口映射、STUN 精探、日志上传样本全都"有代码没流量"。
//
// 调用点对照（fabric/1.20_1.20.1 权威模板）：
//   RoomManager.java:164-256  createRoom 的后台任务：autoUPnP 映射 → StunProbe.probeAsync → setNatType → updateRoom
//   RoomManager.java:556      退出房间：UPnPManager.closePort(hostPort)
//   RoomManager.java:377      房主 arm 日志上传；AttemptingJoinScreen.java:258 房客 arm、:249 取消时 disarm
//   ConnectionManager.java:15723 / :15077 / :13576  onConnected / onDisconnected / onTerminalFailure

import (
	"fmt"
	"sync/atomic"
	"time"
)

// autoUPnPEnabled 与模组 isAutoUPnP 同一个开关、同一个默认值 true（VoxLinkConfig.java:35/58/86）。
func (a *App) autoUPnPEnabled() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.settings.AutoUPnP == nil {
		return true
	}
	return *a.settings.AutoUPnP
}

// hostSessionStarted 房主建房成功后。模组的 createRoom 是 "instant create + NAT probe deferred"
// （:161 的日志原文），探测与映射都不许挡住建房返回，所以整段放后台。
func (a *App) hostSessionStarted(code, name string, hostPort int, hostIp, hostIpv6 string) {
	setActiveAppForTransport(a)
	a.logUploadArm(code, true) // RoomManager.java:377
	go a.hostNatMapAndProbe(code, name, hostPort, hostIp, hostIpv6)
}

// guestSessionStarted 房客开始加入。模组在 AttemptingJoinScreen 打开时 arm（:258），
// 取消加入时 disarm（:249）——本端的"开始加入"就是 JoinRoom 入口，"取消/离开"是 leaveInternal。
func (a *App) guestSessionStarted(code string) {
	setActiveAppForTransport(a)
	a.logUploadArm(code, false) // AttemptingJoinScreen.java:258
	// 本机 /info 服务与房主同形态常驻（PeerServer.java:54-107 在游戏启动时起，不分角色）
	setPeerCacheProvider(a.peerCacheFromApp)
	startPeerServer(a)
}

// peerCacheFromApp 把模组的三个进程内取值换成本端等价来源（裁定的"允许等价替换"）：
//
//	mc.getLaunchedVersion() → 本端房间记录里的 gameVersion
//	FabricLoader.getMods() → modsync 已建好的本地清单（同一批 jar 的 id/version），
//	  只读内存里现成的缓存，绝不在 /info 的请求路径上触发重建
//	ViaCompat.getServerProtocolVersion() → 本端没有对应物（应用端不承载 MC 进程，
//	  也没有 Via 转换层），按模组"取不到"的初值给 0，而不是猜一个协议号
//	Geyser → 本端不承载 Geyser，恒 false（模组 :158-160 也是"没加载就 false"）
func (a *App) peerCacheFromApp() peerCacheValues {
	v := peerCacheValues{VoxLinkVersion: appVersion}
	a.mu.Lock()
	if a.room != nil {
		v.GameVersion = roomStr(a.room, "gameVersion")
	}
	a.mu.Unlock()
	if s := a.modSync(); s != nil {
		s.mu.Lock()
		cache := s.cachedRoot
		s.mu.Unlock()
		if cache == nil {
			return v
		}
		if m := cache.scopeManifest(modSyncScopeAll); m != nil {
			for _, e := range m.Mods {
				if e == nil {
					continue
				}
				id := e.Slug
				if id == "" {
					id = e.ProjectID
				}
				if id == "" {
					id = e.FileName
				}
				v.Mods = append(v.Mods, peerModEntry{id: id, version: e.VersionNumber})
			}
		}
	}
	return v
}

// hostNatMapAndProbe 后台那一整段（RoomManager.java:164-256 + :467-524）。
func (a *App) hostNatMapAndProbe(code, name string, hostPort int, hostIp, hostIpv6 string) {
	effectivePort := hostPort
	if a.autoUPnPEnabled() { // :166
		tcp := upnpOpenPort(hostPort, name)           // :173
		udp := upnpOpenUdpPort(hostPort, name+"-UDP") // :174
		switch {
		case tcp.success: // :176-178
			effectivePort = tcp.externalPort
			a.netLog("info", fmt.Sprintf("upnp_tcp_mapped effective_port=%d", effectivePort))
		case udp.success: // :179-181
			effectivePort = udp.externalPort
			a.netLog("info", fmt.Sprintf("upnp_udp_mapped effective_port=%d", effectivePort))
		default:
			// 没有网关/网关拒绝映射：模组同样是静默继续（玩家不需要知道 UPnP 这件事）
			a.netLog("info", "upnp_not_mapped available=false")
		}
		// :183-188 的 Geyser 基岩端口映射分支本端没有对应物（应用端不承载 Geyser），故无。
	}

	natType := "unknown"
	if probe := stunProbeAsync(stunServerGroupsFromDetector()).join(); probe != nil { // :190-198
		natType = probe.NatType.key()
		a.netLog("info", fmt.Sprintf("stun_probe_done nat=%s reachable=%d", natType, len(probe.ReachableStunURLs)))
	}

	a.applyDeferredNat(code, natType, effectivePort)
	// 端口可达性：模组在 :467-524 用 UPnP 后的 ctx.port 去问服务端 /stun/check。
	// 房主侧多 socket 阵列的规模要读这个结论（CM:3941-3943）。
	a.probeCreatedRoomPorts(hostIp, hostIpv6, effectivePort)
}

// applyDeferredNat 对应 :204-256：把探测结果写回本地房间信息并 updateRoom 一次；
// 房间此刻已经不在了（玩家秒退）则按模组那样 2s 后再看一次，不再发请求。
func (a *App) applyDeferredNat(code, natType string, effectivePort int) {
	if !a.setRoomNatType(code, natType, effectivePort) {
		a.netLog("info", "create_room_room_not_ready_retry_nat_update_in_2s") // :229
		time.AfterFunc(2*time.Second, func() {
			if a.setRoomNatType(code, natType, effectivePort) { // :236-238
				a.touchRoomAfterNat(code)
			}
		})
		return
	}
	a.touchRoomAfterNat(code) // :209-222
}

// setRoomNatType 等价 state.roomInfo.setNatType(...)：只改当前会话那间房，
// 房号对不上（已经换房/退出）返回 false，让调用方走"未就绪"分支。
func (a *App) setRoomNatType(code, natType string, effectivePort int) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.room == nil || a.code != code || a.session == nil {
		return false
	}
	a.room["natType"] = natType
	if effectivePort > 0 {
		a.room["hostPort"] = effectivePort // :176-181 的 effectivePort 语义：映射后大厅显示外部端口
	}
	return true
}

// touchRoomAfterNat 发一次 /room/update（模组的 updateRoom 参数里并不带 natType，
// 这一步的实际作用是刷新房间记录与服务端缓存；本端同样只发既有可更新字段）。
func (a *App) touchRoomAfterNat(code string) {
	a.mu.Lock()
	token := a.token
	name := ""
	category := ""
	visible := true
	if a.room != nil {
		if v, ok := a.room["name"].(string); ok {
			name = v
		}
		if v, ok := a.room["category"].(string); ok {
			category = v
		}
		if v, ok := a.room["visible"].(bool); ok {
			visible = v
		}
	}
	a.mu.Unlock()

	body := map[string]any{"code": code, "token": token, "isHost": true}
	if name != "" {
		body["name"] = name
	}
	if category != "" {
		body["category"] = category
	}
	body["visible"] = visible
	if err := a.api.post(a.baseURL(), "/room/update", body, nil); err != nil {
		a.netLog("warn", "create_room_nat_update_failed") // :220-222 只记一条 warn
		return
	}
	a.netLog("info", "create_room_nat_updated") // :219
}

// sessionStopped 退出房间时的收尾：关端口映射 + disarm 日志上传。
// isHost/hostPort 由调用方在解锁后传进来（leaveInternal 已经取过快照）。
func (a *App) sessionStopped(isHost bool, hostPort int) {
	setActiveAppForTransport(nil)
	if isHost && a.autoUPnPEnabled() {
		upnpClosePort(hostPort) // RoomManager.java:556（本端无 Geyser，:558 那条基岩分支无对应物）
	}
	a.logUploadDisarm() // AttemptingJoinScreen.java:249
}

// ---- 数据面（rudp / bridge）到日志上传的两个事件 ----
//
// 模组的 ReliableUdpTransport 是静态调用 LogUploadManager（:599 每收到一个对端包记一次活跃、
// :1148/:1174/:1180/:1206 四处判死各记一次）。本端的 rudp 是独立层、不持 App 指针，
// 所以用一个包级"当前会话 App"入口，语义与模组的静态调用等价（同一时刻只有一个活动会话）。

var activeAppForTransport atomic.Pointer[App]

func setActiveAppForTransport(a *App) {
	if a == nil {
		activeAppForTransport.Store(nil)
		return
	}
	activeAppForTransport.Store(a)
}

// transportActivity 对应 ReliableUdpTransport.java:599（内部 1s 节流，每包调用开销可忽略）。
func transportActivity() {
	if a := activeAppForTransport.Load(); a != nil {
		a.logUploadOnTransportActivity()
	}
}

// transportLinkDead 对应 ReliableUdpTransport.java:1148/1174/1180/1206 —— 这四处都是
// "心跳零入站 / 60s 硬超 / 24s 静默且有待确认 / 重传超限"，本端统一在传输层关闭回调里落点，
// 因为该回调正是这四条判据的共同出口。
func transportLinkDead(reason string) {
	a := activeAppForTransport.Load()
	if a == nil {
		return
	}
	a.netLog("warn", "rudp_link_dead") // 消敏口径：不把 reason 原文写进日志（可能含地址）
	a.logUploadOnLinkDead()
}
