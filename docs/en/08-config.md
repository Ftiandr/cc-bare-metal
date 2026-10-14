# 8. Configuration

## Server (`<world>/serverconfig/ccbaremetal-server.toml`)

| Key | Default | What it sets |
|---|---|---|
| `computer.memory_per_process_mb` | 256 | memory of one WebAssembly process, MB |
| `computer.compiler_memory_mb` | 1024 | memory of the clang compiler, MB (only taken while it runs) |
| `computer.max_processes` | 64 | processes per computer |
| `computer.threads_per_process` | 64 | threads one program may have (`-pthread`; most of them wait) |
| `computer.cpus_per_process` | 4 | processors a program sees (`sysconf`, `hardware_concurrency`): how many cores it keeps busy |
| `computer.disk_space_mb` | 64 | computer disk, MB |
| `computer.tmp_space_mb` | 16 | in-memory `/tmp`, MB |
| `computer.max_sync_kb_per_tick` | 128 | soft limit of compressed screen data per player per tick, KB |
| `computer.upload_max_kb` | 65536 | how much a player may drop onto a computer window at once, KB (0 disables it) |
| `monitor.max_width`, `monitor.max_height` | 8, 6 | maximum pixel monitor size in blocks |
| `monitor.pixels_per_block` | 64 | default resolution of a monitor block |

Internet access is configured in CC: Tweaked's own config (the `http` section): see [section 3](03-system.md).

## Client (`config/ccbaremetal-client.toml`)

| Key | Default | What it sets |
|---|---|---|
| `screen.text_zoom` | 0 | terminal font size (0: from the window size); also changed in the window: Ctrl+'+'/'-'/0 |
| `cc_tweaked.unicode` | true | Unicode for CC: Tweaked computers (see [section 7](07-unicode.md)) |

## Files on disk

- `<world>/computercraft/ccbaremetal/computer/<id>`: computer files, with `<id>.meta` next to it: their owners and
  modes (only those that differ from the usual);
- `<game folder>/ccbaremetal/toolchain`: the unpacked compiler (created automatically, safe to delete);
- `<game folder>/ccbaremetal/jit-cache`: cache of programs translated to JVM bytecode (safe to delete).
