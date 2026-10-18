# 生产 TURN 新二进制（pion 模型）端到端验证：BIND 双角色 → DATA 互转 → 端口漂移重绑 → UNBIND
import socket, struct, hmac, hashlib, os, sys, time

NODE = ("162.251.94.31", 37000)
NODEKEY = bytes.fromhex("5bcec0f7aa39af26a4a3a9935be9ea92b74ef02c768e1da5b5c7d8255cac199e")

def ticket(sid, role, exp):
    sidhex = sid.hex()
    msg = f"T1.{sidhex}.{role}.{exp}".encode()
    mac = hmac.new(NODEKEY, msg, hashlib.sha256).hexdigest()
    return f"T1.{sidhex}.{role}.{exp}.{mac}".encode()

def pkt(ptype, body=b""):
    return bytes([0x56, 0x4C, 0x01, ptype]) + body

def bind(sid, role, tk):
    return pkt(0x03, sid + bytes([role]) + struct.pack(">H", len(tk)) + tk)

def data(sid, frm, to, payload):
    return pkt(0x05, sid + bytes([frm, to]) + struct.pack(">H", len(payload)) + payload)

def unbind(sid, role):
    return pkt(0x08, sid + bytes([role]))

def expect(sock, what, timeout=4):
    sock.settimeout(timeout)
    try:
        d, _ = sock.recvfrom(2048)
        return d
    except socket.timeout:
        print(f"FAIL: 等 {what} 超时"); sys.exit(1)

sid = os.urandom(16)
exp = int(time.time()) + 600

host = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
guest = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

host.sendto(bind(sid, 1, ticket(sid, 1, exp)), NODE)
r = expect(host, "host BIND_RESULT")
assert r[3] == 0x04 and r[21] == 0, f"host BIND code={r[21]}"
print("OK host BIND")

guest.sendto(bind(sid, 2, ticket(sid, 2, exp)), NODE)
r = expect(guest, "guest BIND_RESULT")
assert r[3] == 0x04 and r[21] == 0, f"guest BIND code={r[21]}"
print("OK guest BIND（会话激活）")

host.sendto(data(sid, 1, 2, b"pion-model-check-h2g"), NODE)
r = expect(guest, "guest 收 DATA")
assert r[3] == 0x05 and r[24:] == b"pion-model-check-h2g", f"DATA 内容错: {r[24:]}"
print("OK DATA host->guest")

guest.sendto(data(sid, 2, 1, b"pion-model-check-g2h"), NODE)
r = expect(host, "host 收 DATA")
assert r[3] == 0x05 and r[24:] == b"pion-model-check-g2h", f"DATA 内容错: {r[24:]}"
print("OK DATA guest->host")

# 端口漂移：host 换新 socket（同机同 IP 新端口）发 DATA → guest 应收到；guest 回包应落到新 socket
host2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
host2.sendto(data(sid, 1, 2, b"drifted"), NODE)
r = expect(guest, "guest 收漂移 DATA")
assert r[3] == 0x05 and r[24:] == b"drifted", f"漂移 DATA 被丢: {r}"
print("OK 端口漂移 DATA 放行（pion Permission=IP 语义）")

guest.sendto(data(sid, 2, 1, b"to-new-port"), NODE)
r = expect(host2, "host2 收回包")
assert r[3] == 0x05 and r[24:] == b"to-new-port"
print("OK 回包落到漂移后新端口（重绑生效）")

host2.settimeout(1)
host.sendto(unbind(sid, 1), NODE)
r = expect(host2, "UNBIND_RESULT", 4)
assert r[3] == 0x09 and r[20] == 0
print("OK UNBIND（会话回收）")

host.close(); host2.close(); guest.close()
print("ALL PASS — pion 模型生产数据面验证通过")
