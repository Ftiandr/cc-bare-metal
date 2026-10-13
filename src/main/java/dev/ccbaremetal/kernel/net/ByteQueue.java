package dev.ccbaremetal.kernel.net;

import java.nio.ByteBuffer;

/** A growable FIFO of bytes (a socket's send or receive buffer). Not thread safe: the owner locks. */
final class ByteQueue {
    private byte[] data = new byte[4096];
    private int head;
    private int size;

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void add(byte[] src, int offset, int length) {
        if (size + length > data.length) {
            var grown = new byte[Math.max(data.length * 2, size + length)];
            copyOut(0, grown, 0, size);
            data = grown;
            head = 0;
        }
        for (var i = 0; i < length; i++) data[(head + size + i) % data.length] = src[offset + i];
        size += length;
    }

    void add(ByteBuffer src, int length) {
        var bytes = new byte[length];
        src.get(bytes);
        add(bytes, 0, length);
    }

    /** {@code length} bytes starting {@code offset} bytes after the head, without removing them. */
    byte[] peek(int offset, int length) {
        var out = new byte[length];
        copyOut(offset, out, 0, length);
        return out;
    }

    int take(ByteBuffer dst) {
        var n = Math.min(dst.remaining(), size);
        for (var i = 0; i < n; i++) dst.put(data[(head + i) % data.length]);
        drop(n);
        return n;
    }

    void drop(int n) {
        n = Math.min(n, size);
        head = (head + n) % data.length;
        size -= n;
        if (size == 0) head = 0;
    }

    void clear() {
        head = 0;
        size = 0;
    }

    private void copyOut(int offset, byte[] dst, int at, int length) {
        for (var i = 0; i < length; i++) dst[at + i] = data[(head + offset + i) % data.length];
    }
}
