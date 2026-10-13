// cc::net: stream sockets over modems (the kernel's kernel/net/Net does the work; these are thin wrappers).
#include <baremetal.hpp>

#include <cerrno>
#include <cstdio>
#include <unistd.h>

namespace cc::net {

int listen(int port) {
    int32_t fd = -1;
    int r = cc_net_listen(port, &fd);
    return r == 0 ? fd : -r;
}

int accept(int listenFd, bool wait) {
    int32_t fd = -1;
    int r = cc_net_accept(listenFd, wait ? 0 : 1, &fd);
    return r == 0 ? fd : -r;
}

int connect(const std::string &host, int port, int timeoutMs) {
    int32_t fd = -1;
    int r = cc_net_connect(host.data(), static_cast<int32_t>(host.size()), port, timeoutMs, &fd);
    return r == 0 ? fd : -r;
}

int shutdown(int fd) {
    return -cc_net_shutdown(fd);
}

static Socket socketOf(const Json &j) {
    Socket s;
    s.state = j["state"].asString("");
    s.local = j["local"].asString("");
    s.remote = j["remote"].asString("");
    s.localPort = j["lport"].asInt();
    s.remotePort = j["rport"].asInt();
    s.uid = j["uid"].asInt();
    s.recvQueue = static_cast<long>(j["recvq"].asNumber(j["queue"].asNumber(0)));
    s.sendQueue = static_cast<long>(j["sendq"].asNumber(0));
    return s;
}

std::optional<Socket> info(int fd) {
    int n = __cc_net_info(fd);
    if (n < 0) {
        errno = -n;
        return std::nullopt;
    }
    return socketOf(Json::parse(take(n)).value_or(Json()));
}

// /proc/net/ccnet: a header line, then "state local lport remote rport uid recvq sendq" per socket.
Status status() {
    Status s;
    s.hostname = hostname();
    s.channel = channel(s.hostname);
    FILE *f = std::fopen("/proc/net/ccnet", "r");
    if (!f) return s;
    char line[512];
    bool header = true;
    while (std::fgets(line, sizeof(line), f)) {
        if (header) {
            header = false;
            continue;
        }
        char state[32], local[128], remote[128];
        Socket k;
        if (std::sscanf(line, "%31s %127s %d %127s %d %d %ld %ld", state, local, &k.localPort, remote, &k.remotePort, &k.uid,
                        &k.recvQueue, &k.sendQueue) != 8) {
            continue;
        }
        k.state = state;
        k.local = local;
        k.remote = remote;
        s.sockets.push_back(k);
    }
    std::fclose(f);
    return s;
}

int channel(const std::string &name) {
    uint32_t h = 2166136261u;
    for (char c : name) {
        auto b = static_cast<unsigned char>(c);
        if (b >= 'A' && b <= 'Z') b = static_cast<unsigned char>(b + 32);
        h = (h ^ b) * 16777619u;
    }
    return 100 + static_cast<int>(h % 60000);
}

bool sendAll(int fd, const void *data, size_t n) {
    auto *p = static_cast<const char *>(data);
    while (n > 0) {
        ssize_t w = ::write(fd, p, n);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return false;
        p += w;
        n -= static_cast<size_t>(w);
    }
    return true;
}

} // namespace cc::net
