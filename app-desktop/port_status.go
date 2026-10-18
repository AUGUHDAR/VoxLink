package main

// port_status.go — 服务端端口可达性探测与状态登记，对位模组：
//   请求：network/SignalingClient.java:397-403 checkPortReachable（POST /stun/check）
//   调用点：room/RoomManager.java:467-524（建房成功后 ipv4 / ipv6 各一次，都是异步）
//   枚举：room/RoomInfo.java:678-682 PortStatus
//   消费点：room/ConnectionManager.java:3941-3943（portUnreachable 决定房主生日阵列规模）
//
// 应用端此前完全没有这一路：房主侧永远取不到"端口是否公网可达"，
// 于是 CM:3975-3977 那条"端口不可达 → 上 20 socket"的分支从未生效。

import "sync/atomic"

type portStatus int32

const (
	portStatusUnknown     portStatus = iota // RoomInfo.java:679
	portStatusReachable                     // :680
	portStatusUnreachable                   // :681
	portStatusNoAddress                     // :682
)

func (s portStatus) key() string {
	switch s {
	case portStatusReachable:
		return "reachable"
	case portStatusUnreachable:
		return "unreachable"
	case portStatusNoAddress:
		return "no_address"
	}
	return "unknown"
}

// unreachableOrUnknown = ConnectionManager.java:3943 的判定：
// UNREACHABLE 或 UNKNOWN 都算"端口打不通"（UNKNOWN 是建房后探测未回来的默认态）。
func (s portStatus) unreachableOrUnknown() bool {
	return s == portStatusUnreachable || s == portStatusUnknown // CM:3943
}

// 状态存在 App 上但用独立原子变量：信令分发与打洞协程都要读，不能和 a.mu 缠在一起。
var (
	appIpv4PortStatus atomic.Int32
	appIpv6PortStatus atomic.Int32
)

func (a *App) ipv4PortStatus() portStatus { return portStatus(appIpv4PortStatus.Load()) }
func (a *App) ipv6PortStatus() portStatus { return portStatus(appIpv6PortStatus.Load()) }

// checkPortReachable = SignalingClient.java:397-403：让服务端从公网侧拨一下 ip:port。
// 模组的判据是 success && data.has("reachable") && getAsBoolean()（RoomManager.java:487-490），
// 请求失败与 reachable=false 在模组里都落回"不可达"，这里同构。
func (a *App) checkPortReachable(ip string, port int) bool {
	if ip == "" || port <= 0 {
		return false
	}
	var out struct {
		Reachable *bool `json:"reachable"`
	}
	if err := a.api.post(a.baseURL(), "/stun/check",
		map[string]any{"ip": ip, "port": port, "action": "check_port"}, &out); err != nil {
		return false
	}
	return out.Reachable != nil && *out.Reachable // :488-490 缺 reachable 字段按不可达
}

// probeCreatedRoomPorts 建房成功后的两次可达性探测（RoomManager.java:467-524）。
// 模组是 thenAccept 异步、不阻塞建房返回，这里同样整段放后台；
// 先落 UNKNOWN 再等结果（:481/:504），没有地址就落 NO_ADDRESS（:500/:523）。
func (a *App) probeCreatedRoomPorts(hostIp, hostIpv6 string, hostPort int) {
	if hostIp != "" { // :470-481
		appIpv4PortStatus.Store(int32(portStatusUnknown))
		go func() {
			ok := a.checkPortReachable(hostIp, hostPort) // :484
			if ok {
				appIpv4PortStatus.Store(int32(portStatusReachable))
			} else {
				appIpv4PortStatus.Store(int32(portStatusUnreachable))
			}
			a.netLog("info", "ipv4_port_check "+hostIp+":"+itoa(hostPort)+" reachable="+boolStr(ok)) // :492
		}()
	} else {
		appIpv4PortStatus.Store(int32(portStatusNoAddress)) // :500
	}
	if hostIpv6 != "" { // :503-504
		appIpv6PortStatus.Store(int32(portStatusUnknown))
		go func() {
			ok := a.checkPortReachable(hostIpv6, hostPort) // :507
			if ok {
				appIpv6PortStatus.Store(int32(portStatusReachable))
			} else {
				appIpv6PortStatus.Store(int32(portStatusUnreachable))
			}
			a.netLog("info", "ipv6_port_check ["+hostIpv6+"]:"+itoa(hostPort)+" reachable="+boolStr(ok)) // :515
		}()
	} else {
		appIpv6PortStatus.Store(int32(portStatusNoAddress)) // :523
	}
	// 两路都回来后模组的 warnPortBlockedCombined(:495/:518) 只是往聊天栏提示"端口被挡"，
	// 应用端把同样的事实留在这两行探测日志里，由界面/启动器决定怎么说。
}

// resetPortStatusOnLeave 退房清状态：模组 roomInfo 随房销毁，本端是进程级变量，
// 不清会让下一间房沿用上间的可达性结论。
func resetPortStatusOnLeave() {
	appIpv4PortStatus.Store(int32(portStatusUnknown))
	appIpv6PortStatus.Store(int32(portStatusUnknown))
}

func boolStr(v bool) string {
	if v {
		return "true"
	}
	return "false"
}
