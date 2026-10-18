package main

// portprobe.go — CreateRoom 前的本地端口可达性探测。
//
//   - 用 net.DialTimeout("tcp","127.0.0.1:<hostPort>",1500ms) 直接拨号：
//     能连上 → 端口有服务在监听（MC / 任何 TCP 服务都算）；
//     不能连 → 拒绝建房，提示用户先在 MC 端开放局域网或先启动服务端。
//   - 探测本身纯本地，不消耗服务端配额。
//   - probeHostPort 是包级函数（不依赖 App），便于单测。

import (
	"fmt"
	"net"
	"time"
)

// portProbeTimeout 单次拨号超时。
const portProbeTimeout = 1500 * time.Millisecond

// probeHostPort 探测 127.0.0.1:hostPort 是否在监听。返回 nil 表示端口可达。
func probeHostPort(hostPort int) error {
	if hostPort < 1 || hostPort > 65535 {
		return fmt.Errorf("端口 %d 不在合法范围", hostPort)
	}
	addr := fmt.Sprintf("127.0.0.1:%d", hostPort)
	conn, err := net.DialTimeout("tcp", addr, portProbeTimeout)
	if err != nil {
		return err
	}
	_ = conn.Close()
	return nil
}
