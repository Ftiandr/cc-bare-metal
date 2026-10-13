# 3. Система

## Файлы

| Путь | Что там |
|---|---|
| `/bin`, `/sbin`, `/usr/bin`, `/usr/lib`, `/usr/include`, `/usr/share` | система (только чтение, обновляется вместе с модом) |
| `/usr/lib/llvm`, `/usr/lib/wasi` | компилятор clang и стандартные библиотеки C/C++ |
| `/usr/local` | ваши программы и установленные пакеты (`bin`, `lib`, `include`) |
| `/home/user` | домашняя папка (`/root` — у root) |
| `/etc` | настройки (`profile`, `rc`, `motd`, `pkg/repos`); создаются при первом запуске, дальше ваши |
| `/tmp` | временные файлы в памяти, очищаются при перезагрузке |
| `/run` | служебные файлы в памяти (метки `sudo`) |
| `/rom` | ROM CC: Tweaked (Lua-программы и API) |
| `/disk`, `/disk2`... | дискеты в дисководах CC |
| `/dev`, `/proc`, `/sys` | устройства, процессы, настройки экрана и редстоуна |

Размер диска — 16 МБ на компьютер, `/tmp` — 8 МБ (меняется в [настройках](08-config.md)).

## Оболочка sh

```sh
cmd args > file          # вывод в файл (>> дописать, 2> ошибки, &> всё, 2>&1)
cmd < file               # ввод из файла
a | b                    # конвейер: части работают одновременно, каждая своим процессом
a && b ; a || b          # по успеху / неудаче
cmd &                    # в фоне; jobs, fg %N, wait
$VAR ${VAR:-def} ${#V}   # переменные; $? $$ $! $1...
$(cmd)  $((1 + 2 * x))   # подстановка команды (подоболочка: видит все переменные и функции) и арифметика
*.c ? [abc]              # шаблоны имён
if ...; then ...; elif ...; else ...; fi
while ...; do ...; done   for x in a b; do ...; done   name() { ...; }
case $x in a*|b*) ...;; [0-9]*) ...;; *) ...;; esac
```

Встроенные: `cd pwd echo export unset set alias unalias source . type command eval read exit return jobs fg wait
history break continue shift true false : help exec`.

Редактирование строки: стрелки, Home/End, история (вверх/вниз), Tab — дополнение, Ctrl+A/E, Ctrl+U/K, Ctrl+W,
Ctrl+L. Ctrl+C сбрасывает строку и недописанную команду (незакрытую кавычку, `if` без `fi`), как в bash. Файлы запуска: `/etc/profile`, `~/.profile`, `~/.shrc`. Скрипты запускаются через `sh script.sh`.

Все программы работают в текущей папке оболочки (относительные пути считаются от неё).

## Команды

Около 175 команд, все написаны на C++. `coreutils`, `textutils`, `ccutils` без аргументов выводят свои списки,
`КОМАНДА --help` — краткую справку. Вывод и ключи — как у GNU coreutils в Linux: `ls -l` (права, размер, дата),
`which ls` → `/bin/ls`, `du`/`df` в килобайтах (`-h` — в удобных единицах), `cut -d: -f1,3`, `tr a-z A-Z`,
`tr -d '[:digit:]'`, `cat -n`, `echo -e`, `env -i`, `uname -a`, `stat -c FORMAT`, так что скрипты из Linux
работают без правок. Ссылок (`ln`) в файловой системе нет — `ln` честно об этом сообщает.

**Файлы и система** (coreutils): `cat echo ls cp mv rm mkdir rmdir touch pwd head tail wc grep sort uniq printf
tee yes seq basename dirname rev tr cut env sleep true false clear date uname ps kill uptime free df du find
stat hexdump test [ pgrep pkill killall reboot shutdown poweroff halt label hostname whoami redstone ldd wasm-info more less man
lsperiph ln chown chgrp`.

- `grep [-ivcnlohHrqFEwx] [-e PAT] PAT [FILE...]` — с `-r` по папкам.
- `find [PATH...] [-name|-iname P] [-type f|d] [-maxdepth N] [-mindepth N] [-size [+-]N[ckM]] [-exec CMD {} ;] [-delete]`.
- `head`/`tail`: `-n N`, `-N`, `-c N`; `tail -n +N` — с N-й строки, `tail -f` — следить за файлом.
- Пользователи (userutils): `login su sudo visudo passwd lock autologin useradd userdel usermod groupadd groupdel groups`; сеть: `ssh sshd scp ssh-keygen ssh-copy-id`.
- `ls [-aAlh1drtSFR]`, `du [-sahc]`, `df [-h]`, `cut -f|-c LIST [-d C] [-s]`, `tr [-cds]` (диапазоны и классы).

**Текст и данные** (textutils): `sed awk diff cmp comm join paste column fold fmt nl tac split expand strings
shuf od xxd base64 md5sum sha1sum sha256sum cksum tar realpath readlink mktemp truncate dd tree chmod sync
xargs which file time timeout watch nohup expr factor cal nproc id tty printenv`.

- `sed` — адреса (`N`, `$`, `/re/`, диапазоны, `!`), `s///gpNI` с `&` и `\1-\9`, `d D p P q Q a i c y = n N g G
  h H x l b t T`, блоки `{}`, ключи `-n -e -f -i -E`.
- `awk` — полноценный язык: шаблоны, `BEGIN/END`, диапазоны, массивы, функции, `printf`, `getline`,
  перенаправления `> >> |`, `split sub gsub match substr sprintf tolower toupper system`...
- `diff [-u] [-q] [-r] [-U N]`, `tar -c|-x|-t [-v] -f FILE [-C DIR]` (без сжатия).

**CC: Tweaked** (ccutils): `rednet gps speaker lp disk eject monitor inv energy fbset evtest turtle curl wget nc ss netstat ip lsdev` — см.
[раздел 6](06-peripherals.md), [раздел 5](05-graphics-input.md) и [раздел 2](02-blocks.md).

**Разработка**: `c++ g++ clang++ cc gcc clang c99` и `ar ranlib nm objdump objcopy strip size c++filt addr2line
ld` — см. [раздел 4](04-cpp.md).

## Редактор edit

`edit FILE` — полноэкранный редактор с номерами строк, подсветкой C/C++/Lua/sh и UTF-8. **F1** внутри показывает
все клавиши, то же — `man edit`.

| Что | Клавиши |
|---|---|
| Перемещение | стрелки, PgUp/PgDn, Home (начало текста, повторно — строки), End, Ctrl+←/→ по словам, Ctrl+Home/End — начало/конец файла, щелчок мышью, колесо — прокрутка |
| Выделение | **Shift** + любое перемещение, перетаскивание мышью (у края — с прокруткой), двойной щелчок — слово, тройной — строка, **Ctrl+A** — всё, Esc — снять |
| Правка | ввод заменяет выделение; **Backspace/Delete** удаляют выделенное; Ctrl+Backspace — слово; Enter сохраняет отступ; **Tab / Shift+Tab** — отступ выделенных строк; Ctrl+D — дублировать строку или выделение; Ctrl+K — удалить строку |
| Буфер обмена | **Ctrl+C / Ctrl+X** — копировать / вырезать (без выделения — всю строку); скопированное попадает и в ваш буфер обмена, так что вставляется где угодно; **Ctrl+V** — вставить (в окне игры вставка приходит одним блоком, без лишних отступов) |
| Отмена | **Ctrl+Z / Ctrl+Y** — отменить / вернуть (непрерывный ввод отменяется одним шагом) |
| Файл и поиск | **Ctrl+S** — сохранить, **Ctrl+Q** — выйти (дважды, если есть изменения), Ctrl+F — найти, F3 или Ctrl+N — дальше, Ctrl+R — заменить все, Ctrl+G — к строке |

В строке состояния — `строка:столбец` и размер выделения. Пока редактор открыт, выделение текста в окне игры
делается с Shift (мышь получает редактор).

## Пакеты

```sh
pkg search [text]        # доступные пакеты
pkg install NAME         # установить (с зависимостями) в /usr/local
pkg install FILE.ccpkg   # из файла
pkg remove NAME  |  pkg list  |  pkg files NAME
pkg build DIR [OUT]      # собрать пакет из DIR/manifest.json и DIR/files/
pkg update               # новые версии установленных пакетов
pkg upgrade [NAME...]    # установить их
```

Репозитории: `/usr/share/pkg` (встроенный, например `demos` с `gfxdemo`), `/disk*/pkg` на дискетах и папки из
`/etc/pkg/repos`. `manifest.json`: `{"name": "...", "version": "1.0", "description": "...", "depends": []}`. Пакет, перетащенный на компьютер файлом `.ccpkg`, ставится командой `pkg install ФАЙЛ.ccpkg`.

Каждый установленный пакет помнит, откуда он: репозиторий, файл или URL. `pkg update` ищет там новую версию (версии
сравниваются по частям: 1.10 новее 1.9), а `pkg upgrade` её ставит и убирает файлы, которых в новой версии больше
нет.

## Интернет

Компьютер ходит в интернет через HTTP-API самого CC: Tweaked, поэтому работает в точности как компьютеры CC и
подчиняется тем же настройкам сервера (конфиг CC, раздел `http`): включён ли HTTP, правила адресов (по умолчанию
локальная сеть и сам сервер запрещены), лимиты запросов и веб-сокетов, размеров и скорости, прокси. Отдельно
настраивать ничего не нужно: админ, закрывший HTTP для CC, закрыл его и здесь.

```sh
curl https://example.com/                     # тело ответа на экран
curl -o page.html -i https://example.com/     # в файл; -i — ещё статус и заголовки
curl -X POST -d 'x=1' -H 'Accept: text/plain' https://example.com/api
wget https://example.com/archive.tar          # скачать в текущую папку (-O ИМЯ, -O - — на экран)
pkg install https://example.com/tool.ccpkg    # пакет из интернета
```

Ошибки — сообщения CC: «Domain not permitted», «Not Found», «Timed out»; `curl -f` при ошибочном статусе выходит
с кодом 22. Тела ответов приходят байт в байт, так что качать можно и двоичные файлы.

В C++ — `cc::http::get/post/request/checkURL` и `cc::http::websocket` (`man http`), в Lua и CraftOS — глобальный
`http`, как на CC (работают `wget` и `pastebin` из CraftOS).

## Пользователи и права

Пользователи и права устроены как в Linux. Новый компьютер при первом включении запускает установщик: имя
компьютера, первый пользователь с паролем (он в группе `wheel`, то есть может `sudo`) и, по желанию, пароль root.
Учётная запись `root` есть всегда; без пароля она заблокирована — пользуйтесь `sudo`.

```sh
passwd                    # задать свой пароль: дальше вход по паролю при включении
sudo команда              # от root (для группы wheel); пароль спрашивается раз в 15 минут
sudo -i                   # оболочка root
su - bob                  # стать bob (по его паролю)
sudo useradd -G wheel bob && sudo passwd bob    # новый пользователь
sudo usermod -aG wheel bob   |   sudo userdel -r bob   |   id   |   groups
chmod 755 f   chmod u+x,go-w f   chown bob:users f   chgrp g f   umask 077
```

- `lock` прячет экран, пока не введён пароль (свой или root): можно отойти от компьютера на сервере, Ctrl+C
  блокировку не снимает. Программы под блокировкой продолжают работать, а клавиатуру получает только окно пароля
  (читающие `/dev/input` тоже ничего не получают). `lock -t 5m` блокирует сам после 5 минут без ввода (`lock -t 0` —
  выключить, `lock -s` — показать настройку); `LOCK_AFTER=5m` в `/etc/lock.conf` включает это для каждого входа с
  паролем. `export TMOUT=600` в `~/.profile` (или в `/etc/profile` для всех) выходит из оболочки после 10 минут без
  ввода.
- `sudo autologin user` при каждой загрузке входит под `user` на экране компьютера без пароля, как autologin у
  getty (один раз за загрузку: после выхода показывается приглашение; `ssh`, `su`, `sudo` пароль спрашивают).
  `autologin -d` — выключить. Вместе с `LOCK_AFTER` экран сам заблокируется, когда за компьютером никого нет.
- Выключение по таймеру, как в Linux: `shutdown +10` (через 10 минут), `shutdown 22:30`, `shutdown -r +5`
  (перезагрузка), `shutdown -c` (отмена), `shutdown --show`; просто `shutdown` ждёт минуту, `poweroff`/`reboot`
  срабатывают сразу. Все терминалы (экран и сеансы ssh) получают предупреждения заранее. Выключать может root и тот,
  кто сидит за самим компьютером; пользователю, вошедшему по ssh, нужен `sudo`.
- Кто что может через `sudo` — `/etc/sudoers` (и `/etc/sudoers.d`), как в Linux: правила вида
  `bob ALL=(root) /bin/pkg, NOPASSWD: /bin/redstone`, запреты `!/bin/su`, псевдонимы, `Defaults timestamp_timeout`,
  `secure_path`, `env_keep`. Править — `sudo visudo` (проверяет перед сохранением), посмотреть свои права —
  `sudo -l`. По умолчанию группе `wheel` можно всё. Каждое использование и отказ пишутся в `/var/log/auth.log`.
  Подробно — `man sudoers`.
- Учётные записи — `/etc/passwd`, `/etc/group`, `/etc/shadow` (только root; хеши `crypt()` SHA-512, как в Linux).
- `ls -l` показывает права и владельцев. Система (`/bin`, `/usr`) и `/etc` принадлежат root, `/usr/local` — группе
  `wheel` (`pkg install` работает без sudo), в общей `/tmp` удалить файл может только его владелец.
- `cc` делает собранные программы исполняемыми; скачанной программе нужен `chmod +x`. `su`, `sudo`, `passwd` —
  setuid root.
- Из C/C++: `getuid`, `setuid`, `getpwnam`, `getgrouplist`, `getspnam`, `crypt`, `chmod`, `chown`, `umask`, `access`,
  `stat` (с `st_mode`, `st_uid`, `st_gid`) — как в Linux. Подробно — `man users`.
- Компьютеры, созданные до появления пользователей, при первой загрузке получают владельцев: `/etc` — root, их
  программы остаются исполняемыми.

## SSH

`ssh` входит на другой компьютер по сети (см. [Сеть](#сеть) ниже) через модемы CC: беспроводные (в радиусе), эндер
(где угодно) и проводные (в одной сети). Всё шифруется (обмен ключами X25519, ChaCha20-Poly1305), а компьютер подтверждает, что это он, ключом
хоста (Ed25519), который запоминается при первом входе.

```sh
# на компьютере, куда входят:
sudo hostname base        # имя (по умолчанию computer-N)
passwd                    # нужен пароль (без пароля и root по ssh не пускают, как в OpenSSH)
sudo sshd                 # сервер (работает в фоне); для запуска при включении добавьте sshd в /etc/rc
sudo sshd -p 2222         # другой порт (несколько серверов одновременно); настройки — /etc/ssh/sshd_config
# с другого компьютера:
ssh user@base             # войти на компьютер base
ssh -p 2222 user@base     # на другой порт
ssh base 'ls /tmp'        # одна команда
ssh-keygen && ssh-copy-id user@base   # вход по ключу, без пароля
scp notes.txt user@base:  # файл в домашнюю папку на base (-r — папки, -P 2222 — порт)
scp user@base:docs/a.txt .   # и обратно
```

`scp` передаёт файлы и папки байт в байт по протоколу SCP, как OpenSSH (`-r`, `-p`, `-P`, `-i`). Команда
`ssh base команда` работает без терминала (`-t` — с терминалом), поэтому `ssh base 'cat f' > копия` тоже точна.

`/etc/ssh/sshd_config` — как в OpenSSH: `Port`, `PermitRootLogin`, `PasswordAuthentication`,
`PubkeyAuthentication`, `PermitEmptyPasswords`, `AllowUsers`/`DenyUsers`/`AllowGroups`, `MaxAuthTries`,
`MaxSessions`, `ClientAliveInterval`, `Banner`, `PrintMotd` (`sudo sshd -t` проверяет). Входы и неудачные попытки
пишутся в `/var/log/auth.log`. Клиент читает `~/.ssh/config` (`Host`, `HostName`, `User`, `Port`, `IdentityFile`,
`ConnectTimeout`).

Компьютеры находятся по имени и порту, а не по номеру: номер у скопированного компьютера тот же. Как ssh поверх
TCP/IP, весь сеанс — одно соединение с `имя:порт`. Если на одно имя отвечают два компьютера, `ssh` откажется
(«two computers answer as») — переименуйте один из них. `Connection refused` значит, что на этом порту нет `sshd`,
`Connection timed out` — что компьютера с таким именем нет в досягаемости.

Удалённая оболочка — полноценный терминал: `edit`, цвета, мышь, Ctrl+C, размер окна. `~.` после Enter разрывает
зависшее соединение. Ключ хоста хранится в `~/.ssh/known_hosts`; если он изменится, `ssh` откажется подключаться.
Подробно — `man ssh`.

## Сеть

Компьютеры общаются как по TCP/IP: компьютер находится по **имени**, служба на нём — по **порту**. Имя — это
`/etc/hostname` (`sudo hostname base`), иначе метка компьютера, иначе `computer-N`; `localhost` — сам компьютер.
Порты ниже 1024 — только для root (22 — порт `sshd`). Ядро даёт соединения (сокеты): упорядоченные потоки байтов,
которые доходят целиком или завершаются ошибкой. Ими пользуются `ssh`, `scp` и `nc`, а также ваши программы
(`cc::net` в C++, см. [раздел 4](04-cpp.md)).

```sh
nc -l 1234                    # ждать соединения на порту 1234 (пришедшее — на экран, набранное — обратно)
nc base 1234                  # подключиться к нему с другого компьютера
nc -l 1234 > file             # принять файл...
nc base 1234 < file           # ...и отправить его
nc -d -l 1234 > log &         # сервер в фоне; -k — принимать соединения одно за другим
nc -z base 20-30              # какие порты принимают соединения
ss -l                         # кто какой порт слушает; ss — соединения; ss -s — имя и канал модема
ip link                       # сетевые интерфейсы: lo, wlan0 (беспроводной/эндер-модем), eth0 (проводной); ip -s link — счётчики
sudo ip link set wlan0 down   # не пользоваться модемом
```

Каждый модем — сетевой интерфейс, который создаёт драйвер модема (`/sys/class/net/<имя>`: тип, состояние, канал,
счётчики), `lo` — сам компьютер; сокеты — в `/proc/net/ccnet`. Как это устроено: каждый компьютер с модемом слушает
один канал модема, вычисленный из его имени (хеш; его
показывает `ss -s`). Пакет уходит на канал того компьютера, которому адресован, а порт лежит внутри пакета, как в
заголовке TCP; остальные компьютеры его отбрасывают. Потерянные пакеты (на краю радиуса, модем ненадолго сняли)
отправляются снова; соединение рвётся после 30 секунд без ответа. Если программа закроет каналы модема
(`closeAll`), ядро откроет канал компьютера снова в течение секунды. Как в настоящей радиосети, любой в радиусе,
кто слушает этот канал, видит пакеты: секреты нужно шифровать, и `ssh` со `scp` так и делают. Формат пакетов
открыт, так что в разговоре могут участвовать и Lua-компьютеры (`man baremetal`, раздел NETWORK). Ошибки:
`Connection refused` (на этом порту никто не слушает), `Connection timed out` (неверное имя, вне радиуса, там нет
модема), `Network is down` (здесь нет модема), `Address in use` (порт занят; при подключении — два компьютера с
этим именем). Подробно — `man net`.

## Lua и CraftOS

- `lua` — интерактивный Lua CC; `lua FILE [ARGS]` — запустить программу CC.
- `craftos` — CraftOS: оболочка CC, multishell, все программы ROM.

Работают настоящий `bios.lua` и ROM CC: Tweaked: `term fs os peripheral redstone textutils parallel settings
rednet shell require ...`, плюс глобальные API аддонов (см. [раздел 6](06-peripherals.md)). Lua видит те же файлы,
что и всё остальное. Дополнительно:

- `gfx` — графика (см. [раздел 5](05-graphics-input.md));
- `wasm.run(path, args...)` — запустить программу, вернуть код выхода;
  `wasm.exec(path, {args}, {stdin = s})` — запустить и получить `status, stdout, stderr`;
  `wasm.list()`, `wasm.kill(pid)`.

Текст в UTF-8 (кириллица и т.д.) отображается правильно.

## Справка

`man PAGE`: `intro sh edit cc baremetal gfx dev periph pkg lua craftos keys turtle http users sudoers ssh net`. Примеры в
`/usr/share/examples`: `hello.cpp exceptions.cpp circles.cpp lua.cpp peripherals.cpp framebuffer.cpp paint.cpp
touchpaint.cpp hello.c hello.lua`.
