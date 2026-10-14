# 8. Настройки

## Сервер (`<мир>/serverconfig/ccbaremetal-server.toml`)

| Ключ | По умолчанию | Что задаёт |
|---|---|---|
| `computer.memory_per_process_mb` | 256 | память одного процесса WebAssembly, МБ |
| `computer.compiler_memory_mb` | 1024 | память компилятора clang, МБ (занимается только пока он работает) |
| `computer.max_processes` | 64 | процессов на компьютер |
| `computer.threads_per_process` | 64 | сколько потоков может быть у одной программы (`-pthread`; большинство из них ждут) |
| `computer.cpus_per_process` | 4 | сколько процессоров видит программа (`sysconf`, `hardware_concurrency`): сколько ядер она займёт |
| `computer.disk_space_mb` | 64 | диск компьютера, МБ |
| `computer.tmp_space_mb` | 16 | `/tmp` в памяти, МБ |
| `computer.max_sync_kb_per_tick` | 128 | мягкий предел сжатых данных экрана на игрока за тик, КБ |
| `computer.upload_max_kb` | 65536 | сколько файлов игрок может перетащить в окно за раз, КБ (0 — запретить) |
| `monitor.max_width`, `monitor.max_height` | 8, 6 | максимальный размер пиксельного монитора в блоках |
| `monitor.pixels_per_block` | 64 | разрешение блока монитора по умолчанию |

Доступ в интернет настраивается в конфиге самого CC: Tweaked (раздел `http`), см. [раздел 3](03-system.md).

## Клиент (`config/ccbaremetal-client.toml`)

| Ключ | По умолчанию | Что задаёт |
|---|---|---|
| `screen.text_zoom` | 0 | размер шрифта терминала (0 — по размеру окна); меняется и в окне: Ctrl+'+'/'-'/0 |
| `cc_tweaked.unicode` | true | Unicode для компьютеров CC: Tweaked (см. [раздел 7](07-unicode.md)) |

## Файлы на диске

- `<мир>/computercraft/ccbaremetal/computer/<номер>` — файлы компьютеров, `<номер>.meta` рядом — их владельцы и
  права (только отличающиеся от обычных);
- `<папка игры>/ccbaremetal/toolchain` — распакованный компилятор (создаётся сам, можно удалить);
- `<папка игры>/ccbaremetal/jit-cache` — кэш перевода программ в байткод JVM (можно удалить).
