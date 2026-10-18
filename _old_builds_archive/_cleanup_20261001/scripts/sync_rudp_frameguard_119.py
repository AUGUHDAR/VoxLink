"""1.1.9 安全批量：ReliableUdpTransport 帧长下界按类型收紧（28 模板）。

修复前只校验 packetLen>=11，但 DATA 要读 buf[11..12]（需 13B）、FEC_XOR 还要读
buf[13]（需 14B）。直连路径下复用的大缓冲不会抛异常但会读到上一包残留长度；
TURN 路径 codec.decode 返回的是精确长度数组 → ArrayIndexOutOfBoundsException
落到外层 catch(Throwable) 直接判死整条隧道。后果：任何能向该 UDP 端口发 11 字节
"56 4C .." 裸包的一方，无需伪造任何信令即可远程拆链。
"""
import glob, io, sys

OLD = """         byte type = buf[2];
         if (type != 1 && type != 2) {
            if (packetLen >= 11) {"""

NEW = """         byte type = buf[2];
         if (type != 1 && type != 2) {
            // 帧长下界按类型取：DATA 含 payloadLen(需 13B)、FEC_XOR 还含 count(需 14B)。
            // 一律用 11B 会让短帧越界读，TURN 路径抛异常即被判死整条隧道（11 字节裸包=远程拆链）。
            int frameMinLen = type == 9 ? 14 : (type == 3 ? 13 : 11);
            if (packetLen >= frameMinLen) {"""

files = sorted(glob.glob("*/src/main/java/icu/wuhui/voxlink/network/ReliableUdpTransport.java")
               + glob.glob("*/*/src/main/java/icu/wuhui/voxlink/network/ReliableUdpTransport.java"))
files = [f for f in files if "/build/" not in f.replace("\\", "/")]
done, failed = [], []
for f in files:
    s = io.open(f, encoding="utf-8").read()
    if "frameMinLen" in s:
        done.append(f); continue
    if s.count(OLD) != 1:
        failed.append("%s (锚点 %d 次)" % (f, s.count(OLD))); continue
    io.open(f, "w", encoding="utf-8").write(s.replace(OLD, NEW, 1))
    done.append(f)

print("模板 %d，已修 %d" % (len(files), len(done)))
if failed:
    print("需人工处理:"); [print("   ", x) for x in failed]; sys.exit(2)
print("全部完成")
