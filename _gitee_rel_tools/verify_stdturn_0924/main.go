// 生产验证：标准 TURN（pion 客户端）→ 162.251.94.31:3478 全链路
// 用法：go run . -addr 162.251.94.31:3478 -secret <authSecret>
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"time"

	"github.com/pion/turn/v5"
)

func fail(format string, args ...any) {
	fmt.Printf("FAIL: "+format+"\n", args...)
	os.Exit(1)
}

func main() {
	addr := flag.String("addr", "162.251.94.31:3478", "TURN 服务器地址")
	secret := flag.String("secret", "", "TURN REST authSecret")
	flag.Parse()
	if *secret == "" {
		log.Fatal("-secret 必填")
	}

	user, pass, err := turn.GenerateLongTermTURNRESTCredentials(*secret, "verify-0924", time.Hour)
	if err != nil {
		fail("签发凭证: %v", err)
	}
	fmt.Printf("[1] REST 凭证签发 user=%s\n", user)

	conn, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		fail("客户端监听: %v", err)
	}
	defer conn.Close()

	client, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: *addr,
		TURNServerAddr: *addr,
		Conn:           conn,
		Username:       user,
		Password:       pass,
		Realm:          "voxlink",
	})
	if err != nil {
		fail("创建客户端: %v", err)
	}
	defer client.Close()
	if err = client.Listen(); err != nil {
		fail("Listen: %v", err)
	}

	relayConn, err := client.Allocate()
	if err != nil {
		fail("Allocate: %v", err)
	}
	defer relayConn.Close()
	fmt.Printf("[2] Allocate 成功 relay=%s\n", relayConn.LocalAddr())

	mapped, err := client.SendBindingRequest()
	if err != nil {
		fail("SendBindingRequest: %v", err)
	}
	fmt.Printf("[3] STUN Binding mapped=%s\n", mapped)

	if _, err = relayConn.WriteTo([]byte("hello"), mapped); err != nil {
		fail("relay→mapped 写（建 permission）: %v", err)
	}

	pinger, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		fail("pinger 监听: %v", err)
	}
	defer pinger.Close()

	type relayRead struct {
		data string
		from net.Addr
	}
	done := make(chan relayRead, 1)
	go func() {
		buf := make([]byte, 1600)
		_ = relayConn.SetReadDeadline(time.Now().Add(6 * time.Second))
		n, from, rerr := relayConn.ReadFrom(buf)
		if rerr != nil {
			done <- relayRead{data: "ERR:" + rerr.Error()}
			return
		}
		done <- relayRead{data: string(buf[:n]), from: from}
	}()

	if _, err = pinger.WriteTo([]byte("voxlink-stdturn-verify"), relayConn.LocalAddr()); err != nil {
		fail("pinger→relay 写: %v", err)
	}
	var peerFrom net.Addr
	select {
	case got := <-done:
		if got.data != "voxlink-stdturn-verify" {
			fail("relay 侧收到 %q", got.data)
		}
		peerFrom = got.from
		fmt.Printf("[4] pinger→relay→client 数据到达（relay 侧源地址 %s）\n", peerFrom)
	case <-time.After(7 * time.Second):
		fail("relay 侧超时未收到（permission/转发链路故障）")
	}

	// echo 回 relay 侧看到的 pinger 公网映射地址（0.0.0.0 LocalAddr 不可路由）
	if _, err = relayConn.WriteTo([]byte("voxlink-echo"), peerFrom); err != nil {
		fail("relay echo: %v", err)
	}
	_ = pinger.SetReadDeadline(time.Now().Add(6 * time.Second))
	buf := make([]byte, 1600)
	n, _, err := pinger.ReadFrom(buf)
	if err != nil {
		fail("pinger 未收到 echo: %v", err)
	}
	fmt.Printf("[5] relay→pinger echo 到达 %q\n", string(buf[:n]))

	fmt.Println("ALL PASS")
}
