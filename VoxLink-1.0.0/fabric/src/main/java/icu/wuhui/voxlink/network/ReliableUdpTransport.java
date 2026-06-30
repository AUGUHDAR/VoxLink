package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReliableUdpTransport implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-rudp");

    private static final byte[] MAGIC = {0x56, 0x4C};
    private static final byte TYPE_DATA = 0x03;
    private static final byte TYPE_ACK = 0x04;
    private static final byte TYPE_DISCONNECT = 0x07;
    private static final byte TYPE_KEEPALIVE = 0x08;
    private static final byte TYPE_FEC_XOR = 0x09;
    private static final int FEC_GROUP_SIZE = 4;

    private static final int HEADER_SIZE = 11;
    private static final int PAYLOAD_LEN_SIZE = 2;
    private static final int MAX_PAYLOAD = 1024;
    private static final int WINDOW_SIZE = 64;
    private static final long RETRANSMIT_TIMEOUT_MS = 800;
    private static final int MAX_RETRANSMITS = 30;
    private static final int KEEPALIVE_INTERVAL_S = 2;
    private static final int KEEPALIVE_TIMEOUT_S = 60;
    // 无数据=对端挂了。用lastRecvTime(任何包都刷新)而非lastAckTime(仅ACK刷新)，
    // 避免丢包时接收方不发ACK导致误判。阈值=30*800ms=24s，容忍严重丢包。
    private static final int MAX_SILENT_RETRANSMIT_CYCLES = 30;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteAddress;
    private volatile boolean running = true;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile long lastRecvTime = System.currentTimeMillis();
    private volatile long lastAckTime = System.currentTimeMillis();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile int nextSendSeq = 0;
    private volatile int oldestUnackedSeq = 0;
    private final ConcurrentSkipListMap<Integer, PendingPacket> pendingAcks = new ConcurrentSkipListMap<>();
    private final Object sendLock = new Object();

    private final AtomicInteger nextExpectedSeq = new AtomicInteger(0);
    private final ConcurrentSkipListMap<Integer, byte[]> recvBuffer = new ConcurrentSkipListMap<>();
    private final Object recvLock = new Object();

    private final UdpInputStream inputStream = new UdpInputStream();
    private final UdpOutputStream outputStream = new UdpOutputStream();

    private Thread recvThread;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-Retransmit");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> retransmitTask;
    private ScheduledFuture<?> keepaliveTask;

    private final List<byte[]> fecSendGroup = new ArrayList<>();
    private int fecSendGroupSeq = -1;
    private final Object fecSendLock = new Object();

    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, byte[]>> fecRecvGroup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, byte[]> fecRecvXor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, int[]> fecRecvLengths = new ConcurrentHashMap<>();

    public ReliableUdpTransport(DatagramSocket socket, InetSocketAddress remoteAddress) {
        this.socket = socket;
        this.remoteAddress = remoteAddress;
        try {
            socket.setSoTimeout(100);
        } catch (Exception ignored) {}
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public void start() {
        if (!connected.compareAndSet(false, true)) return;
        // 刷新NAT映射
        try {
            byte[] data = new byte[HEADER_SIZE];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = TYPE_KEEPALIVE;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, 0);
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
        } catch (IOException ignored) {}

        recvThread = new Thread(this::receiveLoop, "VoxLink-UdpRecv");
        recvThread.setDaemon(true);
        recvThread.start();

        retransmitTask = scheduler.scheduleWithFixedDelay(this::retransmitCheck,
                RETRANSMIT_TIMEOUT_MS, RETRANSMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        keepaliveTask = scheduler.scheduleWithFixedDelay(this::sendKeepalive,
                KEEPALIVE_INTERVAL_S, KEEPALIVE_INTERVAL_S, TimeUnit.SECONDS);
    }

    public boolean isConnected() {
        return connected.get() && running;
    }

    private void receiveLoop() {
        byte[] buf = new byte[MAX_PAYLOAD + HEADER_SIZE + PAYLOAD_LEN_SIZE + 4];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(packet);
                if (packet.getLength() < HEADER_SIZE) continue;
                if (buf[0] != MAGIC[0] || buf[1] != MAGIC[1]) continue;

                byte type = buf[2];
                int seq = readInt32(buf, 3);
                int ack = readInt32(buf, 7);

                switch (type) {
                    case TYPE_DATA -> handleData(seq, ack, buf, packet.getLength());
                    case TYPE_ACK -> { handleAck(ack); lastRecvTime = System.currentTimeMillis(); lastAckTime = lastRecvTime; }
                    case TYPE_DISCONNECT -> handleDisconnect();
                    case TYPE_KEEPALIVE -> lastRecvTime = System.currentTimeMillis();
                    case TYPE_FEC_XOR -> handleFecXor(readInt32(buf, 3), buf, packet.getLength());
                }
            } catch (SocketTimeoutException e) {
                // continue
            } catch (IOException e) {
                if (running) {
                    LOGGER.warn("[ReliableUdp] 接收错误: {}", e.getMessage());
                }
            }
        }
    }

    private void handleData(int seq, int ack, byte[] buf, int packetLen) {
        lastRecvTime = System.currentTimeMillis();
        processAck(ack);

        byte[] payload = copyPayload(buf, packetLen);
        if (payload == null) {
            sendAck();
            return;
        }

        int groupId = seq / FEC_GROUP_SIZE;
        fecRecvGroup.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>()).put(seq, payload);

        synchronized (recvLock) {
            if (seq == nextExpectedSeq.get()) {
                inputStream.writeBuffer(payload);
                nextExpectedSeq.getAndIncrement();

                while (recvBuffer.containsKey(nextExpectedSeq.get())) {
                    byte[] cached = recvBuffer.remove(nextExpectedSeq.get());
                    inputStream.writeBuffer(cached);
                    nextExpectedSeq.getAndIncrement();
                }
                recvLock.notifyAll();
            } else if (seqAfter(seq, nextExpectedSeq.get()) && seq < nextExpectedSeq.get() + WINDOW_SIZE * 2) {
                recvBuffer.putIfAbsent(seq, payload);
            } else {
                sendAck();
                return;
            }
        }

        sendAck();
        tryFecRecovery(groupId);
    }

    private byte[] copyPayload(byte[] buf, int packetLen) {
        int payloadLen = readUint16(buf, HEADER_SIZE);
        if (HEADER_SIZE + PAYLOAD_LEN_SIZE + payloadLen > packetLen) return null;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(buf, HEADER_SIZE + PAYLOAD_LEN_SIZE, payload, 0, payloadLen);
        return payload;
    }

    private void deliverPayload(byte[] payload) {
        synchronized (recvLock) {
            inputStream.writeBuffer(payload);
            recvLock.notifyAll();
        }
    }

    private void handleFecXor(int groupId, byte[] buf, int packetLen) {
        lastRecvTime = System.currentTimeMillis();
        int xorPayloadLen = readUint16(buf, HEADER_SIZE);
        int count = buf[HEADER_SIZE + PAYLOAD_LEN_SIZE] & 0xFF;
        int lengthsOffset = HEADER_SIZE + PAYLOAD_LEN_SIZE + 1;
        int xorOffset = lengthsOffset + count * 2;
        if (xorOffset + xorPayloadLen > packetLen) return;
        int[] originalLengths = new int[count];
        for (int i = 0; i < count; i++) {
            originalLengths[i] = readUint16(buf, lengthsOffset + i * 2);
        }
        byte[] xorPayload = new byte[xorPayloadLen];
        System.arraycopy(buf, xorOffset, xorPayload, 0, xorPayloadLen);
        fecRecvXor.put(groupId, xorPayload);
        fecRecvLengths.put(groupId, originalLengths);
        tryFecRecovery(groupId);
    }

    private void tryFecRecovery(int groupId) {
        Map<Integer, byte[]> groupData = fecRecvGroup.get(groupId);
        byte[] xorPayload = fecRecvXor.get(groupId);
        int[] originalLengths = fecRecvLengths.get(groupId);
        if (groupData == null || xorPayload == null || originalLengths == null) return;
        int startSeq = groupId * FEC_GROUP_SIZE;
        int missingSeq = -1;
        int missingIndex = -1;
        int receivedCount = 0;
        for (int i = 0; i < FEC_GROUP_SIZE; i++) {
            int s = startSeq + i;
            if (groupData.containsKey(s)) {
                receivedCount++;
            } else if (seqAfter(nextExpectedSeq.get(), s)) {
                receivedCount++;
            } else {
                missingSeq = s;
                missingIndex = i;
            }
        }
        if (receivedCount == FEC_GROUP_SIZE - 1 && missingSeq >= 0) {
            byte[] recovered = xorPayload.clone();
            for (Map.Entry<Integer, byte[]> e : groupData.entrySet()) {
                byte[] p = e.getValue();
                int len = Math.min(recovered.length, p.length);
                for (int i = 0; i < len; i++) {
                    recovered[i] ^= p[i];
                }
            }
            int origLen = missingIndex < originalLengths.length ? originalLengths[missingIndex] : recovered.length;
            if (origLen < recovered.length) {
                byte[] trimmed = new byte[origLen];
                System.arraycopy(recovered, 0, trimmed, 0, origLen);
                recovered = trimmed;
            }
            synchronized (recvLock) {
                recvBuffer.put(missingSeq, recovered);
                while (recvBuffer.containsKey(nextExpectedSeq.get())) {
                    byte[] cached = recvBuffer.remove(nextExpectedSeq.get());
                    inputStream.writeBuffer(cached);
                    nextExpectedSeq.getAndIncrement();
                }
                recvLock.notifyAll();
            }
            LOGGER.debug("[ReliableUdp] FEC恢复seq {}", missingSeq);
            fecRecvGroup.remove(groupId);
            fecRecvXor.remove(groupId);
            fecRecvLengths.remove(groupId);
        } else if (receivedCount == FEC_GROUP_SIZE) {
            fecRecvGroup.remove(groupId);
            fecRecvXor.remove(groupId);
            fecRecvLengths.remove(groupId);
        }
    }

    private static byte[] computeXorPayload(List<byte[]> payloads) {
        int maxLen = 0;
        for (byte[] p : payloads) {
            if (p.length > maxLen) maxLen = p.length;
        }
        byte[] xor = new byte[maxLen];
        for (byte[] p : payloads) {
            for (int i = 0; i < p.length; i++) {
                xor[i] ^= p[i];
            }
        }
        return xor;
    }

    private void handleAck(int ack) {
        processAck(ack);
    }

    private void processAck(int ack) {
        synchronized (sendLock) {
            while (!pendingAcks.isEmpty() && seqAfter(ack, oldestUnackedSeq)) {
                pendingAcks.remove(oldestUnackedSeq);
                oldestUnackedSeq++;
            }
            sendLock.notifyAll();
        }
    }

    private void handleDisconnect() {
        LOGGER.warn("[ReliableUdp] 收到DISCONNECT包");
        running = false;
        connected.set(false);
        synchronized (recvLock) {
            recvLock.notifyAll();
        }
        synchronized (sendLock) {
            sendLock.notifyAll();
        }
    }

    private void sendAck() {
        try {
            byte[] data = new byte[HEADER_SIZE];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = TYPE_ACK;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, nextExpectedSeq.get());
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
        } catch (IOException e) {
            LOGGER.debug("[ReliableUdp] ACK发送失败: {}", e.getMessage());
        }
    }

    private void sendDataPacket(int seq, byte[] payload, boolean interleave) {
        try {
            byte[] data = new byte[HEADER_SIZE + PAYLOAD_LEN_SIZE + payload.length];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = TYPE_DATA;
            writeInt32(data, 3, seq);
            writeInt32(data, 7, nextExpectedSeq.get());
            writeUint16(data, HEADER_SIZE, payload.length);
            System.arraycopy(payload, 0, data, HEADER_SIZE + PAYLOAD_LEN_SIZE, payload.length);
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
            if (interleave && payload.length < 512 && running && !closed.get()) {
                byte[] dup = data.clone();
                scheduler.schedule(() -> {
                    if (running && !closed.get()) {
                        try {
                            socket.send(new DatagramPacket(dup, dup.length, remoteAddress));
                        } catch (IOException ignored) {}
                    }
                }, 50, TimeUnit.MILLISECONDS);
            }
        } catch (IOException e) {
            LOGGER.debug("[ReliableUdp] 数据发送失败: {}", e.getMessage());
        }
    }

    private void sendFecPacket(int groupId, byte[] xorPayload, int[] originalLengths) {
        try {
            int count = originalLengths.length;
            int bodyOffset = HEADER_SIZE + PAYLOAD_LEN_SIZE + 1 + count * 2;
            byte[] data = new byte[bodyOffset + xorPayload.length];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = TYPE_FEC_XOR;
            writeInt32(data, 3, groupId);
            writeInt32(data, 7, 0);
            writeUint16(data, HEADER_SIZE, xorPayload.length);
            data[HEADER_SIZE + PAYLOAD_LEN_SIZE] = (byte) count;
            for (int i = 0; i < count; i++) {
                writeUint16(data, HEADER_SIZE + PAYLOAD_LEN_SIZE + 1 + i * 2, originalLengths[i]);
            }
            System.arraycopy(xorPayload, 0, data, bodyOffset, xorPayload.length);
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
        } catch (IOException e) {
            LOGGER.debug("[ReliableUdp] FEC发送失败: {}", e.getMessage());
        }
    }

    private void sendKeepalive() {
        if (!running || !connected.get()) return;
        try {
            byte[] data = new byte[HEADER_SIZE];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = TYPE_KEEPALIVE;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, nextExpectedSeq.get());
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
        } catch (IOException e) {
            // ignore
        }
    }

    private void retransmitCheck() {
        if (!running) return;
        if (System.currentTimeMillis() - lastRecvTime > KEEPALIVE_TIMEOUT_S * 1000L) {
            LOGGER.warn("[ReliableUdp] {}秒没收到数据，连接死了", KEEPALIVE_TIMEOUT_S);
            close();
            return;
        }
        // 无数据=对端挂了。用lastRecvTime避免丢包时误判。
        if (!pendingAcks.isEmpty() && System.currentTimeMillis() - lastRecvTime > MAX_SILENT_RETRANSMIT_CYCLES * RETRANSMIT_TIMEOUT_MS) {
            LOGGER.warn("[ReliableUdp] {}ms没收到任何包，{}个包pending，对端大概挂了",
                    MAX_SILENT_RETRANSMIT_CYCLES * RETRANSMIT_TIMEOUT_MS, pendingAcks.size());
            close();
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (sendLock) {
            for (Map.Entry<Integer, PendingPacket> entry : pendingAcks.entrySet()) {
                PendingPacket pp = entry.getValue();
                if (now - pp.sendTime > RETRANSMIT_TIMEOUT_MS) {
                    if (pp.retries >= MAX_RETRANSMITS) {
                        LOGGER.warn("[ReliableUdp] seq {}重试{}次超限(未确认:{})",
                                entry.getKey(), MAX_RETRANSMITS, pendingAcks.size());
                        close();
                        return;
                    }
                    if (pp.retries == 0) {
                        LOGGER.info("[ReliableUdp] 重传seq {}(pending={})", entry.getKey(), pendingAcks.size());
                    }
                    pp.sendTime = now;
                    pp.retries++;
                    sendDataPacket(entry.getKey(), pp.data, false);
                }
            }
        }
    }

    private void sendBytes(byte[] data, int offset, int length) throws IOException {
        if (!running || !connected.get()) throw new IOException("Transport closed");

        int pos = offset;
        while (pos < offset + length) {
            int chunkLen = Math.min(MAX_PAYLOAD, offset + length - pos);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(data, pos, chunk, 0, chunkLen);
            pos += chunkLen;

            synchronized (sendLock) {
                while (running && connected.get() && seqDiff(nextSendSeq, oldestUnackedSeq) >= WINDOW_SIZE) {
                    try {
                        sendLock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Transport closed or interrupted");
                    }
                }
                if (!running || !connected.get()) throw new IOException("Transport closed");

                int seq = nextSendSeq++;
                PendingPacket pp = new PendingPacket(chunk, System.currentTimeMillis(), 0);
                pendingAcks.put(seq, pp);
                sendDataPacket(seq, chunk, true);

                synchronized (fecSendLock) {
                    int groupId = seq / FEC_GROUP_SIZE;
                    if (groupId != fecSendGroupSeq) {
                        fecSendGroup.clear();
                        fecSendGroupSeq = groupId;
                    }
                    fecSendGroup.add(chunk.clone());
                    if (fecSendGroup.size() == FEC_GROUP_SIZE) {
                        byte[] xorPayload = computeXorPayload(fecSendGroup);
                        int[] lengths = new int[FEC_GROUP_SIZE];
                        for (int i = 0; i < FEC_GROUP_SIZE; i++) {
                            lengths[i] = fecSendGroup.get(i).length;
                        }
                        sendFecPacket(groupId, xorPayload, lengths);
                        fecSendGroup.clear();
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running = false;
        connected.set(false);

        try {
            byte[] data = new byte[HEADER_SIZE];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = TYPE_DISCONNECT;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, 0);
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
        } catch (IOException ignored) {}

        if (retransmitTask != null) retransmitTask.cancel(false);
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        scheduler.shutdownNow();

        synchronized (recvLock) {
            recvLock.notifyAll();
        }
        synchronized (sendLock) {
            sendLock.notifyAll();
        }

        if (recvThread != null) {
            recvThread.interrupt();
            try {
                recvThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        P2PBridge.cancelPendingUdpTimeouts();
    }

    private static int readInt32(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24) | ((buf[offset + 1] & 0xFF) << 16) |
                ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
    }

    private static boolean seqAfter(int a, int b) {
        return (a > b && a - b < Integer.MAX_VALUE / 2) || (a < b && b - a > Integer.MAX_VALUE / 2);
    }

    private static int seqDiff(int newer, int older) {
        // 序列号回绕
        long diff = ((long) newer - (long) older) & 0xFFFFFFFFL;
        return (int) diff;
    }

    private static int readUint16(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }

    private static void writeInt32(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >> 24);
        buf[offset + 1] = (byte) (value >> 16);
        buf[offset + 2] = (byte) (value >> 8);
        buf[offset + 3] = (byte) value;
    }

    private static void writeUint16(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >> 8);
        buf[offset + 1] = (byte) value;
    }

    private static class PendingPacket {
        final byte[] data;
        long sendTime;
        int retries;

        PendingPacket(byte[] data, long sendTime, int retries) {
            this.data = data;
            this.sendTime = sendTime;
            this.retries = retries;
        }
    }

    private class UdpInputStream extends InputStream {
        private final ConcurrentLinkedQueue<byte[]> chunks = new ConcurrentLinkedQueue<>();
        private byte[] currentChunk = null;
        private int currentPos = 0;

        void writeBuffer(byte[] data) {
            chunks.offer(data);
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;

            synchronized (recvLock) {
                while (true) {
                    if (currentChunk != null && currentPos < currentChunk.length) {
                        int avail = currentChunk.length - currentPos;
                        int toRead = Math.min(len, avail);
                        System.arraycopy(currentChunk, currentPos, b, off, toRead);
                        currentPos += toRead;
                        if (currentPos >= currentChunk.length) {
                            currentChunk = null;
                            currentPos = 0;
                        }
                        return toRead;
                    }

                    currentChunk = chunks.poll();
                    currentPos = 0;

                    if (currentChunk != null) continue;

                    if (!running && chunks.isEmpty()) return -1;

                    try {
                        recvLock.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
            }
        }

        @Override
        public int available() {
            synchronized (recvLock) {
                int total = 0;
                if (currentChunk != null) total += currentChunk.length - currentPos;
                for (byte[] chunk : chunks) total += chunk.length;
                return total;
            }
        }
    }

    private class UdpOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                sendBytes(b, off, len);
            } catch (IOException e) {
                LOGGER.debug("[ReliableUdp] 写入失败: {}", e.getMessage());
                throw e;
            }
        }

        @Override
        public void flush() {
        }
    }
}
