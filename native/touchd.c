/*
 * touchd v3 —— crosvm virtio-input 事件桥（健壮性重写）
 *
 * 角色：脚本以 root 在 crosvm 启动前拉起 4 个 touchd，各 bind 一个 unix socket
 * 供 crosvm connect（crosvm 只在启动那一刻连一次、guest 复位 virtio 设备会再连），
 * 同时监听一个 TCP 端口供 APP connect。本进程把 APP 发来的 8 字节 virtio_input_event
 * 原样透传给 crosvm。
 *
 * 与 v2 的区别（基于 8 版实测仍不稳的复盘，对齐参考实现 InputSocketHost）：
 *   1. crosvm 连上/断开时立即向 APP 推一个状态字节（0x01 / 0x00），状态栏据此**实时**
 *      刷新，不再只在 APP 连接那一下发一次 hello（避免假 ✓/✗ 卡死，满足"动态检测"）。
 *   2. unix socket chmod 0777（与参考一致），杜绝 crosvm 因权限/SELinux 偶发连不上。
 *   3. TCP 监听"持续重试直到 bind 成功"的循环——端口被旧 touchd 占着也能在其退出瞬间
 *      抢到，不再 10s 后放弃导致该路永久 ✗。
 *   4. crosvm 侧只侦测 POLLHUP/POLLERR 判定断开，不读它的控制字节（避免干扰 evdev 流）；
 *      断开后保持 listener，crosvm 复位 virtio 设备重连时自动接回。
 *   5. APP 侧断开/重连绝不触碰 crosvm 侧连接，反之亦然——两路彻底解耦。
 *
 * 编译：
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang \
 *       -O2 -Wall -Wextra -s -o touchd touchd.c
 *
 * 用法：touchd <tcp_port> <unix_socket_path>
 */
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define ST_UP   0x01   /* crosvm 已连接输入事件源 */
#define ST_DOWN 0x00   /* crosvm 未连接 */

static int g_port = 0;

static void log_line(const char *s) {
    if (g_port)
        printf("touchd[%d]: %s\n", g_port, s);
    else
        printf("touchd: %s\n", s);
    fflush(stdout);
}

static int listen_unix(const char *path) {
    unlink(path);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket(unix)"); return -1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        perror("bind(unix)"); close(fd); return -1;
    }
    if (listen(fd, 16) < 0) {
        perror("listen(unix)"); close(fd); return -1;
    }
    chmod(path, 0777);   /* 参考实现用 0777，确保 crosvm(root) 一定能 connect */
    return fd;
}

static int listen_tcp(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((uint16_t) port);

    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) { close(fd); return -1; }
    if (listen(fd, 16) < 0) { close(fd); return -1; }
    return fd;
}

/* 持续重试直到 bind 成功（端口被旧进程占着也行），不再 10s 后放弃 */
static int listen_tcp_forever(int port) {
    for (;;) {
        int fd = listen_tcp(port);
        if (fd >= 0) return fd;
        usleep(200000);
    }
}

static void send_byte(int fd, unsigned char v) {
    if (fd < 0) return;
    ssize_t ignored = write(fd, &v, 1);
    (void) ignored;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <tcp_port> <unix_socket_path>\n", argv[0]);
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);

    int port = atoi(argv[1]);
    const char *path = argv[2];
    g_port = port;

    /* 先建 unix socket：crosvm 只在启动那一刻 connect 一次、绝不重连，必须万无一失。
       TCP 端口被占只影响 APP 状态栏，且现在用 forever 重试，也不会连累 guest 设备。 */
    int us = listen_unix(path);
    if (us < 0) return 1;
    int ts = listen_tcp_forever(port);
    if (ts < 0) { log_line("TCP listen failed"); return 1; }

    printf("touchd[%d]: unix=%s tcp=127.0.0.1:%d\n", port, path, port);
    fflush(stdout);

    int uc = -1;   /* crosvm 侧（unix），crosvm 是 connect 方 */
    int tc = -1;   /* APP 侧（TCP） */
    int st = ST_DOWN;        /* 当前应上报给 APP 的状态 */
    int reported = -1;       /* 已上报的状态，避免无变化也刷屏 */

    for (;;) {
        struct pollfd p[4];
        nfds_t n = 0;
        int i_us, i_ts, i_uc = -1, i_tc = -1;
        int uc_snap = uc, tc_snap = tc;

        i_us = (int) n; p[n].fd = us; p[n].events = POLLIN; p[n].revents = 0; n++;
        i_ts = (int) n; p[n].fd = ts; p[n].events = POLLIN; p[n].revents = 0; n++;
        if (uc >= 0) { i_uc = (int) n; p[n].fd = uc; p[n].events = POLLIN | POLLHUP | POLLERR; p[n].revents = 0; n++; }
        if (tc >= 0) { i_tc = (int) n; p[n].fd = tc; p[n].events = POLLIN | POLLHUP | POLLERR; p[n].revents = 0; n++; }

        int pr = poll(p, n, 1000);
        if (pr < 0) {
            if (errno == EINTR) continue;
            perror("poll"); usleep(200000); continue;
        }
        if (pr == 0) {
            /* 周期保活：状态变化时（或 APP 新连时）把当前状态推给 APP */
            if (tc >= 0 && reported != st) { send_byte(tc, (unsigned char) st); reported = st; }
            continue;
        }

        /* ---- 1) APP 新连接（含重连）：只替换 tc，立即上报当前状态 ---- */
        if (i_ts >= 0 && (p[i_ts].revents & POLLIN)) {
            int c = accept(ts, NULL, NULL);
            if (c >= 0) {
                int one = 1;
                setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
                if (tc >= 0) close(tc);
                tc = c;
                reported = -1;   /* 强制让下面补推一次当前状态 */
                log_line("APP connected (crosvm link untouched)");
            }
        }

        /* ---- 2) crosvm 新连接（含复位后重连）：只替换 uc，绝不碰 APP 侧 ---- */
        if (p[i_us].revents & POLLIN) {
            int c = accept(us, NULL, NULL);
            if (c >= 0) {
                /* crosvm 不读时别把整个桥阻塞死：写超时 1 秒即返回 */
                struct timeval tv = {1, 0};
                setsockopt(c, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
                if (uc >= 0) close(uc);
                uc = c;
                st = ST_UP;
                log_line("crosvm connected (event source live, APP link untouched)");
            }
        }

        /* ---- 3) crosvm 侧挂断：只作废旧 uc，APP 侧连接原样保留 ---- */
        if (i_uc >= 0 && uc == uc_snap &&
            (p[i_uc].revents & (POLLHUP | POLLERR))) {
            log_line("crosvm disconnected (waiting for reconnect, APP link KEPT)");
            close(uc);
            uc = -1;
            st = ST_DOWN;   /* 状态下降，APP 状态栏会变 ✗，等 crosvm 重连自动恢复 ✓ */
        }

        /* ---- 4) APP 侧数据/挂断：转发给 uc；APP 断开绝不关 uc ---- */
        if (i_tc >= 0 && tc == tc_snap &&
            (p[i_tc].revents & (POLLIN | POLLHUP | POLLERR))) {
            char buf[4096];
            ssize_t r = read(tc, buf, sizeof(buf));
            if (r > 0) {
                if (uc >= 0) {
                    ssize_t off = 0;
                    while (off < r) {
                        ssize_t w = write(uc, buf + off, (size_t) (r - off));
                        if (w > 0) { off += w; continue; }
                        if (w < 0 && errno == EINTR) continue;
                        /* 只丢这一批事件，绝不 close(uc)：crosvm 只在启动时 connect
                           一次、不会重连，一旦关掉就是永久失效。真断开由 POLLHUP 处理。 */
                        log_line("write to crosvm failed, drop events (source kept)");
                        break;
                    }
                }
                /* uc 未就绪时事件直接丢弃，不阻塞、不关 APP 连接 */
            } else if (r == 0 || (r < 0 && errno != EAGAIN && errno != EINTR)) {
                log_line("APP disconnected (crosvm link KEPT)");
                close(tc);
                tc = -1;
            }
        }

        /* 状态变化时立即推给 APP（APP 新连时 reported=-1 也会触发这里补推） */
        if (tc >= 0 && reported != st) { send_byte(tc, (unsigned char) st); reported = st; }
    }
}
