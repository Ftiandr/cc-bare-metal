package dev.ccbaremetal.kernel.fs;

/** WASI filetype values. */
public enum FileType {
    UNKNOWN(0), BLOCK_DEVICE(1), CHARACTER_DEVICE(2), DIRECTORY(3), REGULAR_FILE(4),
    SOCKET_DGRAM(5), SOCKET_STREAM(6), SYMBOLIC_LINK(7);

    public final int wasi;

    FileType(int wasi) {
        this.wasi = wasi;
    }
}
