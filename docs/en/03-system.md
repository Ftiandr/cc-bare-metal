# 3. System

## Files

| Path | Contents |
|---|---|
| `/bin`, `/sbin`, `/usr/bin`, `/usr/lib`, `/usr/include`, `/usr/share` | the system (read-only, updated together with the mod) |
| `/usr/lib/llvm`, `/usr/lib/wasi` | the clang compiler and the C/C++ standard libraries |
| `/usr/local` | your programs and installed packages (`bin`, `lib`, `include`) |
| `/home/user` | home folder (`/root` for root) |
| `/etc` | settings (`profile`, `rc`, `motd`, `pkg/repos`); created on first boot, yours afterwards |
| `/tmp` | temporary files in memory, cleared on reboot |
| `/run` | runtime files in memory (`sudo` timestamps) |
| `/rom` | CC: Tweaked's ROM (Lua programs and APIs) |
| `/disk`, `/disk2`... | floppy disks in CC disk drives |
| `/dev`, `/proc`, `/sys` | devices, processes, screen and redstone settings |

Disk size is 16 MB per computer, `/tmp` is 8 MB (see the [configuration](08-config.md)).

## The sh shell

```sh
cmd args > file          # output to a file (>> append, 2> errors, &> everything, 2>&1)
cmd < file               # input from a file
a | b                    # pipeline: the parts run at the same time, each as its own process
a && b ; a || b          # on success / on failure
cmd &                    # in the background; jobs, fg %N, wait
$VAR ${VAR:-def} ${#V}   # variables; $? $$ $! $1...
$(cmd)  $((1 + 2 * x))   # command substitution (a subshell that sees all variables and functions) and arithmetic
*.c ? [abc]              # file name patterns
if ...; then ...; elif ...; else ...; fi
while ...; do ...; done   for x in a b; do ...; done   name() { ...; }
case $x in a*|b*) ...;; [0-9]*) ...;; *) ...;; esac
```

Built-ins: `cd pwd echo export unset set alias unalias source . type command eval read exit return jobs fg wait
history break continue shift true false : help exec`.

Line editing: arrows, Home/End, history (up/down), Tab completion, Ctrl+A/E, Ctrl+U/K, Ctrl+W, Ctrl+L. Ctrl+C drops
the line and any unfinished command (an unclosed quote, an `if` without `fi`), as in bash. Startup files:
`/etc/profile`, `~/.profile`, `~/.shrc`. Scripts are run with `sh script.sh`.

Every program runs in the shell's current folder (relative paths start from it).

## Commands

About 175 commands, all written in C++. `coreutils`, `textutils` and `ccutils` without arguments list theirs,
`COMMAND --help` shows a short usage. Output and options follow GNU coreutils on Linux: `ls -l` (mode, size, date),
`which ls` → `/bin/ls`, `du`/`df` in kilobytes (`-h` for readable units), `cut -d: -f1,3`, `tr a-z A-Z`,
`tr -d '[:digit:]'`, `cat -n`, `echo -e`, `env -i`, `uname -a`, `stat -c FORMAT`, so scripts from Linux work
unchanged. The file system has no links; `ln` says so instead of pretending.

**Files and system** (coreutils): `cat echo ls cp mv rm mkdir rmdir touch pwd head tail wc grep sort uniq printf
tee yes seq basename dirname rev tr cut env sleep true false clear date uname ps kill uptime free df du find
stat hexdump test [ pgrep pkill killall reboot shutdown poweroff halt label hostname whoami redstone ldd wasm-info more less man
lsperiph ln chown chgrp`.

- `grep [-ivcnlohHrqFEwx] [-e PAT] PAT [FILE...]`, with `-r` through folders.
- `find [PATH...] [-name|-iname P] [-type f|d] [-maxdepth N] [-mindepth N] [-size [+-]N[ckM]] [-exec CMD {} ;] [-delete]`.
- `head`/`tail`: `-n N`, `-N`, `-c N`; `tail -n +N` from line N, `tail -f` follows a file.
- Users (userutils): `login su sudo visudo passwd lock autologin useradd userdel usermod groupadd groupdel groups`; network: `ssh sshd scp ssh-keygen ssh-copy-id`.
- `ls [-aAlh1drtSFR]`, `du [-sahc]`, `df [-h]`, `cut -f|-c LIST [-d C] [-s]`, `tr [-cds]` (ranges and classes).

**Text and data** (textutils): `sed awk diff cmp comm join paste column fold fmt nl tac split expand strings
shuf od xxd base64 md5sum sha1sum sha256sum cksum tar realpath readlink mktemp truncate dd tree chmod sync
xargs which file time timeout watch nohup expr factor cal nproc id tty printenv`.

- `sed`: addresses (`N`, `$`, `/re/`, ranges, `!`), `s///gpNI` with `&` and `\1-\9`, `d D p P q Q a i c y = n N g G
  h H x l b t T`, `{}` blocks, options `-n -e -f -i -E`.
- `awk`: the full language: patterns, `BEGIN/END`, ranges, arrays, functions, `printf`, `getline`, redirections
  `> >> |`, `split sub gsub match substr sprintf tolower toupper system`...
- `diff [-u] [-q] [-r] [-U N]`, `tar -c|-x|-t [-v] -f FILE [-C DIR]` (no compression).

**CC: Tweaked** (ccutils): `rednet gps speaker lp disk eject monitor inv energy fbset evtest turtle curl wget nc ss netstat ip lsdev`; see
[section 6](06-peripherals.md), [section 5](05-graphics-input.md) and [section 2](02-blocks.md).

**Development**: `c++ g++ clang++ cc gcc clang c99` and `ar ranlib nm objdump objcopy strip size c++filt addr2line
ld`; see [section 4](04-cpp.md).

## The edit editor

`edit FILE` is a full-screen editor with line numbers, C/C++/Lua/sh highlighting and UTF-8. **F1** inside lists
every key, and so does `man edit`.

| What | Keys |
|---|---|
| Moving | arrows, PgUp/PgDn, Home (start of text, again: of the line), End, Ctrl+←/→ by words, Ctrl+Home/End: start/end of file, mouse click, wheel scrolls |
| Selecting | **Shift** + any movement, mouse drag (scrolls at the edge), double click: word, triple click: line, **Ctrl+A**: everything, Esc: clear |
| Editing | typing replaces the selection; **Backspace/Delete** remove the selection; Ctrl+Backspace: word; Enter keeps the indentation; **Tab / Shift+Tab** indent the selected lines; Ctrl+D duplicates the line or selection; Ctrl+K deletes the line |
| Clipboard | **Ctrl+C / Ctrl+X** copy / cut (the whole line without a selection); what you copy also goes into your own clipboard, so it pastes anywhere; **Ctrl+V** pastes (in the game window a paste arrives as one block, without extra indentation) |
| Undo | **Ctrl+Z / Ctrl+Y** undo / redo (continuous typing is undone in one step) |
| File and search | **Ctrl+S** save, **Ctrl+Q** quit (twice if there are changes), Ctrl+F find, F3 or Ctrl+N next, Ctrl+R replace all, Ctrl+G go to line |

The status line shows `line:column` and the size of the selection. While the editor is open, select text in the
game window with Shift (the editor gets the mouse).

## Packages

```sh
pkg search [text]        # available packages
pkg install NAME         # install (with dependencies) into /usr/local
pkg install FILE.ccpkg   # from a file;  pkg install URL  downloads it
pkg remove NAME  |  pkg list  |  pkg files NAME  |  pkg info NAME
pkg build DIR [OUT]      # build a package from DIR/manifest.json and DIR/files/
pkg update               # refresh the network repositories, list newer versions of what is installed
pkg upgrade [NAME...]    # install them
pkg repo [list|add X|remove X]   # repositories: folders or URLs (/etc/pkg/repos)
pkg index DIR            # write DIR/index.json: DIR becomes a network repository once served over HTTP
```

Repositories: `/usr/share/pkg` (built in, for example `demos` with `gfxdemo`), `/disk*/pkg` on floppy disks, and what
`/etc/pkg/repos` lists (`sudo pkg repo add ...`): folders of `.ccpkg` files, or URLs of a folder with `index.json`
(made by `pkg index`). `pkg update` downloads the indexes of the network repositories; their packages are
downloaded when installed and checked against the index (size and SHA-256). `manifest.json`:
`{"name": "...", "version": "1.0", "description": "...", "depends": []}`. A package dropped onto the computer as a
`.ccpkg` file installs with `pkg install FILE.ccpkg`.

Installing is careful: the package must be whole and its files must fit on the disk (checked first), every file is
written next to the old one (`FILE.pkgnew`) and they replace the installed files only when all are written. A full
disk or a broken download leaves the installed version as it was.

Every installed package remembers where it came from: a repository, a file or a URL. `pkg update` looks there for a
newer version (versions compare by parts: 1.10 is newer than 1.9) and `pkg upgrade` installs it, removing the files
the new version no longer has.

## Internet

The computer reaches the internet through CC: Tweaked's own HTTP API, so it works exactly like CC computers and obeys
the same server settings (CC's config, the `http` section): whether HTTP is enabled, address rules (by default the
local network and the server itself are blocked), limits on requests and websockets, sizes and speed, the proxy.
Nothing needs to be set up separately: an admin who closed HTTP for CC has closed it here too.

```sh
curl https://example.com/                     # response body to the screen
curl -o page.html -i https://example.com/     # to a file; -i also shows status and headers
curl -X POST -d 'x=1' -H 'Accept: text/plain' https://example.com/api
wget https://example.com/archive.tar          # download into the current folder (-O NAME, -O - to the screen)
pkg install https://example.com/tool.ccpkg    # a package from the internet
```

Errors are CC's messages: "Domain not permitted", "Not Found", "Timed out"; `curl -f` exits with status 22 on an
error status. Response bodies arrive byte for byte, so binary files can be downloaded too.

In C++: `cc::http::get/post/request/checkURL` and `cc::http::websocket` (`man http`); in Lua and CraftOS the global
`http`, as on CC (CraftOS's `wget` and `pastebin` work).

## Users and permissions

Users and permissions work as on Linux. A new computer runs a setup on its first start: the computer's name, the
first user with a password (in the group `wheel`, so it may `sudo`) and, optionally, a root password. The `root`
account always exists; without a password it is locked: use `sudo`.

```sh
passwd                    # set your password: from then on the computer asks for it at boot
sudo command              # as root (for the group wheel); the password is asked once per 15 minutes
sudo -i                   # a root shell
su - bob                  # become bob (with bob's password)
sudo useradd -G wheel bob && sudo passwd bob    # a new user
sudo usermod -aG wheel bob   |   sudo userdel -r bob   |   id   |   groups
chmod 755 f   chmod u+x,go-w f   chown bob:users f   chgrp g f   umask 077
```

- `lock` hides the screen until the password is typed (yours or root's): you can step away from a computer on a
  server, Ctrl+C does not unlock it. Programs go on running behind the lock, and only the password prompt gets the
  keyboard (`/dev/input` readers too get nothing). `lock -t 5m` locks by itself after 5 minutes without input
  (`lock -t 0` turns it off, `lock -s` shows the setting); `LOCK_AFTER=5m` in `/etc/lock.conf` does it for every
  login with a password. `export TMOUT=600` in `~/.profile` (or `/etc/profile` for everyone) logs a shell out after 10
  minutes without input.
- `sudo autologin user` logs `user` in on the computer's screen at every boot without the password, as getty's
  autologin (once per boot: after a logout the prompt is shown; `ssh`, `su`, `sudo` still ask). `autologin -d` turns
  it off. Together with `LOCK_AFTER` the screen locks itself when nobody uses it.
- Turning off on a timer, as on Linux: `shutdown +10` (in 10 minutes), `shutdown 22:30`, `shutdown -r +5` (reboot),
  `shutdown -c` (cancel), `shutdown --show`; plain `shutdown` waits one minute, `poweroff`/`reboot` act at once.
  Every terminal (the screen and ssh sessions) is warned beforehand. Turning off is for root and for whoever sits at
  the computer itself; a user logged in over ssh needs `sudo`.
- Who may do what with `sudo` is in `/etc/sudoers` (and `/etc/sudoers.d`), as on Linux: rules like
  `bob ALL=(root) /bin/pkg, NOPASSWD: /bin/redstone`, refusals `!/bin/su`, aliases, `Defaults timestamp_timeout`,
  `secure_path`, `env_keep`. Edit it with `sudo visudo` (checked before it is saved); `sudo -l` shows your rights. By
  default the group `wheel` may do anything. Every use and every refusal is logged in `/var/log/auth.log`. Details:
  `man sudoers`.
- Accounts are `/etc/passwd`, `/etc/group`, `/etc/shadow` (root only; `crypt()` SHA-512 hashes, as on Linux).
- `ls -l` shows modes and owners. The system (`/bin`, `/usr`) and `/etc` are root's, `/usr/local` belongs to the
  group `wheel` (`pkg install` works without sudo), and in the shared `/tmp` only a file's owner may delete it.
- `cc` makes the programs it builds runnable; a downloaded program needs `chmod +x`. `su`, `sudo`, `passwd` are
  setuid root.
- From C/C++: `getuid`, `setuid`, `getpwnam`, `getgrouplist`, `getspnam`, `crypt`, `chmod`, `chown`, `umask`,
  `access`, `stat` (with `st_mode`, `st_uid`, `st_gid`), as on Linux. Details: `man users`.
- Computers made before users existed get owners on their first boot: `/etc` becomes root's, their programs stay
  runnable.

## SSH

`ssh` logs in to another computer over the network (see [Network](#network) below), through CC modems: wireless
(in range), ender (anywhere) and wired (same network). Everything is encrypted (X25519 key exchange, ChaCha20-Poly1305), and the computer proves who it is with
its host key (Ed25519), remembered on the first login.

```sh
# on the computer you log in to:
sudo hostname base        # a name (computer-N by default)
passwd                    # a password is needed (accounts without one, and root, may not log in, as in OpenSSH)
sudo sshd                 # the server (runs in the background); add sshd to /etc/rc to start it at boot
sudo sshd -p 2222         # another port (several servers at once); settings: /etc/ssh/sshd_config
# from another computer:
ssh user@base             # log in to the computer base
ssh -p 2222 user@base     # on another port
ssh base 'ls /tmp'        # one command
ssh-keygen && ssh-copy-id user@base   # key login, no password
scp notes.txt user@base:  # a file into the home folder on base (-r folders, -P 2222 port)
scp user@base:docs/a.txt .   # and back
```

`scp` copies files and folders byte for byte with the SCP protocol, as OpenSSH's (`-r`, `-p`, `-P`, `-i`).
`ssh base command` runs without a terminal (`-t` asks for one), so `ssh base 'cat f' > copy` is exact too.

`/etc/ssh/sshd_config` works as OpenSSH's: `Port`, `PermitRootLogin`, `PasswordAuthentication`,
`PubkeyAuthentication`, `PermitEmptyPasswords`, `AllowUsers`/`DenyUsers`/`AllowGroups`, `MaxAuthTries`,
`MaxSessions`, `ClientAliveInterval`, `Banner`, `PrintMotd` (`sudo sshd -t` checks it). Logins and failed attempts go
to `/var/log/auth.log`. The client reads `~/.ssh/config` (`Host`, `HostName`, `User`, `Port`, `IdentityFile`,
`ConnectTimeout`).

Computers are found by name and port, not by id: a copied computer keeps its id. As ssh over TCP/IP, the whole
session is one connection to `name:port`. If two computers answer to one name, `ssh` refuses ("two computers answer
as"); rename one of them. `Connection refused` means no `sshd` on that port, `Connection timed out` means no
computer of that name in reach.

The remote shell is a full terminal: `edit`, colours, the mouse, Ctrl+C, window size. `~.` after Enter drops a hung
connection. The host key is kept in `~/.ssh/known_hosts`; if it ever changes, `ssh` refuses to connect. Details:
`man ssh`.

## Network

Computers talk to each other like over TCP/IP: a computer is reached by its **name**, a service on it by a
**port**. The name is `/etc/hostname` (`sudo hostname base`), else the computer's label, else `computer-N`;
`localhost` is the computer itself. Ports below 1024 are for root (22 is `sshd`'s). The kernel provides
connections (sockets): ordered streams of bytes that arrive complete, or fail with an error. `ssh`, `scp` and `nc`
use them, and so can your programs (`cc::net` in C++, see [section 4](04-cpp.md)).

```sh
nc -l 1234                    # wait for a connection on port 1234 (what arrives is shown, what you type goes back)
nc base 1234                  # connect to it from another computer
nc -l 1234 > file             # receive a file ...
nc base 1234 < file           # ... and send it
nc -d -l 1234 > log &         # a server in the background; -k takes one connection after another
nc -z base 20-30              # which ports accept connections
ss -l                         # who listens on which port; ss: connections; ss -s: the name and modem channel
ip link                       # network interfaces: lo, wlan0 (wireless/ender modem), eth0 (wired); ip -s link: counters
sudo ip link set wlan0 down   # stop using a modem
```

Each modem is a network interface made by the modem driver (`/sys/class/net/<name>`: type, state, channel,
counters), `lo` is the computer itself; the sockets are in `/proc/net/ccnet`. How it works: every computer with a
modem listens on one modem channel, derived from its name (a hash; `ss -s`
shows it). A packet goes to the channel of the computer it is for, and the port is inside it, as in a TCP header;
other computers ignore it. Lost packets (the edge of the range, a modem removed for a moment) are sent again; a
connection fails after 30 seconds without an answer. If a program closes the modem's channels (`closeAll`), the
kernel opens the computer's channel again within a second. As on a real radio network, anyone in range listening on
that channel sees the packets: secrets need encryption, which `ssh` and `scp` have. The packet format is open, so
Lua computers can take part too (`man baremetal`, section NETWORK). Errors: `Connection refused` (nothing listens on
that port), `Connection timed out` (wrong name, out of range, no modem there), `Network is down` (no modem here),
`Address in use` (the port is taken; when connecting: two computers have that name). Details: `man net`.

## Lua and CraftOS

- `lua`: CC's interactive Lua; `lua FILE [ARGS]` runs a CC program.
- `craftos`: CraftOS, CC's shell, with multishell and every ROM program.

The real `bios.lua` and ROM of CC: Tweaked are used: `term fs os peripheral redstone textutils parallel settings
rednet shell require ...`, plus addon global APIs (see [section 6](06-peripherals.md)). Lua sees the same files as
everything else. In addition:

- `gfx`: graphics (see [section 5](05-graphics-input.md));
- `wasm.run(path, args...)` runs a program and returns its exit status;
  `wasm.exec(path, {args}, {stdin = s})` runs one and returns `status, stdout, stderr`;
  `wasm.list()`, `wasm.kill(pid)`.

UTF-8 text (Cyrillic and so on) is displayed correctly.

## Help

`man PAGE`: `intro sh edit cc baremetal gfx dev periph pkg lua craftos keys turtle http users sudoers ssh net`. Examples in
`/usr/share/examples`: `hello.cpp exceptions.cpp circles.cpp lua.cpp peripherals.cpp framebuffer.cpp paint.cpp
touchpaint.cpp hello.c hello.lua`.
