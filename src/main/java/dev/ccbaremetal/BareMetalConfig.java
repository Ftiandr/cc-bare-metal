package dev.ccbaremetal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server configuration (per world: serverconfig/ccbaremetal-server.toml). */
public final class BareMetalConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MEMORY_PER_PROCESS_MB;
    public static final ModConfigSpec.IntValue COMPILER_MEMORY_MB;
    public static final ModConfigSpec.IntValue MAX_PROCESSES;
    public static final ModConfigSpec.IntValue THREADS_PER_PROCESS;
    public static final ModConfigSpec.IntValue DISK_SPACE_MB;
    public static final ModConfigSpec.IntValue TMP_SPACE_MB;
    public static final ModConfigSpec.IntValue MONITOR_MAX_WIDTH;
    public static final ModConfigSpec.IntValue MONITOR_MAX_HEIGHT;
    public static final ModConfigSpec.IntValue MONITOR_PIXELS_PER_BLOCK;
    public static final ModConfigSpec.IntValue MAX_SYNC_KB_PER_TICK;
    public static final ModConfigSpec.IntValue UPLOAD_MAX_KB;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.comment("WASM computers").push("computer");
        MEMORY_PER_PROCESS_MB = builder.comment("Maximum linear memory of one WebAssembly process, in MiB.")
            .defineInRange("memory_per_process_mb", 256, 1, 4096);
        COMPILER_MEMORY_MB = builder.comment("Memory limit of the in-game C/C++ compiler (clang), in MiB. Memory is only",
                "allocated as the compiler needs it; compiling C++ with the standard library takes a few hundred MiB.")
            .defineInRange("compiler_memory_mb", 1024, 64, 4096);
        MAX_PROCESSES = builder.comment("Maximum number of processes per computer.")
            .defineInRange("max_processes", 64, 4, 1024);
        THREADS_PER_PROCESS = builder.comment("Maximum number of threads of one WebAssembly process (pthreads; each is a",
                "thread of the server, so this is how many cores one program may keep busy).")
            .defineInRange("threads_per_process", 8, 1, 256);
        DISK_SPACE_MB = builder.comment("Disk space of each computer, in MiB.")
            .defineInRange("disk_space_mb", 64, 1, 4096);
        TMP_SPACE_MB = builder.comment("Size of the in-memory /tmp of each computer, in MiB.")
            .defineInRange("tmp_space_mb", 16, 1, 1024);
        MAX_SYNC_KB_PER_TICK = builder.comment("Soft limit of compressed screen data sent to one player per tick, in KiB.")
            .defineInRange("max_sync_kb_per_tick", 128, 4, 1024);
        UPLOAD_MAX_KB = builder.comment("Largest total size of files a player may drop onto a computer window at once, in KiB (0 disables it).")
            .defineInRange("upload_max_kb", 65536, 0, 1 << 20);
        builder.pop();

        builder.comment("Pixel monitors").push("monitor");
        MONITOR_MAX_WIDTH = builder.comment("Maximum width of a pixel monitor, in blocks.").defineInRange("max_width", 8, 1, 16);
        MONITOR_MAX_HEIGHT = builder.comment("Maximum height of a pixel monitor, in blocks.").defineInRange("max_height", 6, 1, 16);
        MONITOR_PIXELS_PER_BLOCK = builder.comment("Default resolution of a monitor block, in pixels per block side.")
            .defineInRange("pixels_per_block", 64, 8, 128);
        builder.pop();
        SPEC = builder.build();
    }

    private BareMetalConfig() {
    }

    /** Values safe to read before the config is loaded (falls back to defaults). */
    public static int get(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
