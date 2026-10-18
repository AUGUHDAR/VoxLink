package main

// punch_auth.go — punchAuthV1 认证工具（对齐模组 PunchAuth.java）：
// key = SHA-256("VOXLINK-PUNCH-AUTH-V1" || roomCode || 0x00 || joinerClientId)；
// 报文尾附 4 字节截断 HMAC-SHA256（覆盖 [2..len)），双方声明 punchAuthV1 才启用。

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
)

func cryptRandRead(b []byte) (int, error) { return rand.Read(b) }

var punchAuthTag = []byte("VOXLINK-PUNCH-AUTH-V1")

// punchAuthDeriveDirectKey 直接 host↔joiner 链路密钥；任一输入为空返回 nil。
func punchAuthDeriveDirectKey(roomCode, joinerClientID string) []byte {
	if roomCode == "" || joinerClientID == "" {
		return nil
	}
	h := sha256.New()
	h.Write(punchAuthTag)
	h.Write([]byte(roomCode))
	h.Write([]byte{0})
	h.Write([]byte(joinerClientID))
	return h.Sum(nil)
}

// punchAuthTruncated4 计算 buf[off..off+len) 的 4 字节截断 HMAC-SHA256。
func punchAuthTruncated4(key, buf []byte, off, l int) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write(buf[off : off+l])
	sum := mac.Sum(nil)
	return sum[:4]
}

// punchAuthAppendTrailer4 给帧追加 4 字节截断 MAC（覆盖 frame[2..len)）。
func punchAuthAppendTrailer4(key, frame []byte) []byte {
	out := make([]byte, len(frame)+4)
	copy(out, frame)
	copy(out[len(frame):], punchAuthTruncated4(key, frame, 2, len(frame)-2))
	return out
}

// punchAuthVerifyTrailer4 校验帧尾 4 字节截断 MAC（覆盖 [2..len-4)）。
func punchAuthVerifyTrailer4(key, buf []byte) bool {
	if len(buf) < 4+3 {
		return false
	}
	expect := punchAuthTruncated4(key, buf, 2, len(buf)-2-4)
	for i := 0; i < 4; i++ {
		if expect[i] != buf[len(buf)-4+i] {
			return false
		}
	}
	return true
}

// punchAuthNonce 从 5 字节明文控制包取 nonce（大端）。
func punchAuthNonce(b []byte) uint16 { return binary.BigEndian.Uint16(b[3:5]) }
