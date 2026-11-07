/*
 * gunydhcpd3 —— 面向 Android/Gunyah 虚拟机的极简 DHCPv4 服务（多接口版）
 *
 * 为什么要改：
 *   旧版只用 SO_BINDTODEVICE 绑一个接口（tap）。实测在 Linux 上
 *   「AF_PACKET 绑 any 收不到 veth/tap 的 RX 帧」，必须显式 bind 到具体 ifindex；
 *   而 tap 一旦被 bridge 接管，帧会从 bridge 设备重新上来，绑 tap 的 socket
 *   收不到 → guest 永远等不到 IP。
 *
 * 本版的策略：
 *   枚举 /sys/class/net 下所有接口，每个接口建一个 AF_PACKET socket 并 bind 到
 *   对应 ifindex，poll 多路复用；每 5 秒重新扫描，新出现的接口自动纳入。
 *   收到包时用「收包的那个接口」回包，所以 tap 直连、bridge、veth 都能工作。
 *
 * 用法： gunydhcpd -a 192.168.100.1/24 -r 192.168.100.100-192.168.100.199 \
 *                  -d 223.5.5.5,114.114.114.114 -l 86400 [-i iface] [-v] [-n]
 *   -i iface  只监听该接口（调试用）
 *   -n        不装 BPF（排错用）
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <signal.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <net/if.h>
#include <netinet/in.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <linux/filter.h>
#include <arpa/inet.h>

#define MAX_SOCKS 48
#define MAX_LEASES 64
#define DHCP_MAGIC 0x63825363u

static volatile sig_atomic_t g_stop = 0;
static void on_sig(int s) { (void)s; g_stop = 1; }

struct bootp {
    uint8_t  op, htype, hlen, hops;
    uint32_t xid;
    uint16_t secs, flags;
    uint32_t ciaddr, yiaddr, siaddr, giaddr;
    uint8_t  chaddr[16];
    uint8_t  sname[64];
    uint8_t  file[128];
    uint32_t magic;
    uint8_t  opts[312];
} __attribute__((packed));

static const uint8_t BCAST_MAC[6] = {0xff,0xff,0xff,0xff,0xff,0xff};

struct lease { uint8_t mac[6]; uint32_t ip; time_t exp; int used; };
static struct lease g_le[MAX_LEASES];
static int g_nle = 0;

static uint32_t g_gw = 0, g_mask = 0, g_pool_s = 0, g_pool_e = 0;
static uint32_t g_dns[2] = {0,0};
static int g_ndns = 0;
static int g_lease_secs = 86400;
static const char *g_only_if = NULL;
static int g_verbose = 0, g_nobpf = 0;

static unsigned long st_rx=0, st_disc=0, st_off=0, st_req=0, st_ack=0, st_nak=0, st_skip=0;

struct sockent { int fd; int ifindex; char name[IFNAMSIZ]; };
static struct sockent g_sk[MAX_SOCKS];
static int g_nsk = 0;

/* bridge 场景下同一个包会在 tap 和 bridge 上各出现一次，去重避免回两份 OFFER */
struct recent { uint32_t xid; uint8_t mac[6]; uint8_t mtype; time_t t; };
static struct recent g_recent[16];
static int g_nrec = 0;

static int is_dup(uint32_t xid, const uint8_t mac[6], uint8_t mtype) {
    time_t now = time(NULL);
    for (int i = 0; i < g_nrec; i++) {
        if (g_recent[i].xid == xid && g_recent[i].mtype == mtype
            && memcmp(g_recent[i].mac, mac, 6) == 0
            && now - g_recent[i].t <= 1) return 1;
    }
    int slot = g_nrec < 16 ? g_nrec++ : (g_nrec++ % 16);
    g_recent[slot].xid = xid;
    memcpy(g_recent[slot].mac, mac, 6);
    g_recent[slot].mtype = mtype;
    g_recent[slot].t = now;
    return 0;
}

static uint32_t ip_aton(const char *s) { struct in_addr a; return inet_aton(s,&a)?ntohl(a.s_addr):0; }
static const char *ip_ntoa(uint32_t h) {
    static char b[6][32]; static int i=0;
    struct in_addr a; a.s_addr=htonl(h); i=(i+1)%6;
    snprintf(b[i],32,"%s",inet_ntoa(a)); return b[i];
}

static int lease_for(const uint8_t mac[6], int *is_new) {
    time_t now = time(NULL);
    for (int i=0;i<g_nle;i++)
        if (g_le[i].used && memcmp(g_le[i].mac,mac,6)==0 && g_le[i].exp>now) { *is_new=0; return g_le[i].ip; }
    for (int i=0;i<MAX_LEASES;i++) {
        if (i<g_nle && g_le[i].used && g_le[i].exp>now) continue;
        uint32_t ip = g_pool_s + (uint32_t)(i % (int)(g_pool_e - g_pool_s + 1));
        memcpy(g_le[i].mac, mac, 6);
        g_le[i].ip = ip; g_le[i].used = 1; g_le[i].exp = now + g_lease_secs;
        if (i >= g_nle) g_nle = i + 1;
        *is_new = 1; return ip;
    }
    *is_new = 0; return 0;
}

static void lease_commit(const uint8_t mac[6], uint32_t ip) {
    time_t now = time(NULL);
    for (int i=0;i<g_nle;i++)
        if (g_le[i].used && memcmp(g_le[i].mac,mac,6)==0) { g_le[i].ip=ip; g_le[i].exp=now+g_lease_secs; return; }
    if (g_nle < MAX_LEASES) {
        int i=g_nle++; memcpy(g_le[i].mac,mac,6);
        g_le[i].ip=ip; g_le[i].exp=now+g_lease_secs; g_le[i].used=1;
    }
}

static uint8_t *opt_find(struct bootp *p, int *plen, uint8_t code) {
    int i=0, n=*plen;
    while (i < n) {
        uint8_t c = p->opts[i];
        if (c == 0) { i++; continue; }
        if (c == 255) break;
        if (i+1 >= n) break;
        uint8_t l = p->opts[i+1];
        if (i+2+l > n) break;
        if (c == code) { *plen = l; return &p->opts[i+2]; }
        i += 2 + l;
    }
    *plen = 0; return NULL;
}

static int opt_put(uint8_t *buf, int off, uint8_t code, const void *val, int len) {
    buf[off++] = code; buf[off++] = (uint8_t)len;
    if (val) memcpy(buf+off, val, (size_t)len);
    return off + len;
}

/* 计算 IPv4 头校验和 */
static uint16_t ip_csum(const uint8_t *h, int len) {
    uint32_t s = 0;
    for (int i = 0; i + 1 < len; i += 2) s += ((uint32_t)h[i] << 8) | h[i+1];
    if (len & 1) s += (uint32_t)h[len-1] << 8;
    while (s >> 16) s = (s & 0xffff) + (s >> 16);
    return (uint16_t)~s;
}

/* SOCK_RAW 发送必须自带完整的以太头 + IP 头 + UDP 头 */
static void send_reply(int fd, struct bootp *req, int rx_ifidx,
                       const uint8_t dst_mac[6], uint8_t msgtype,
                       uint32_t yiaddr, uint32_t server_id) {
    static uint8_t frame[700];
    memset(frame, 0, sizeof(frame));

    uint8_t *eth = frame;
    uint8_t *ip  = eth + 14;
    uint8_t *udp = ip + 20;
    struct bootp *r = (struct bootp *)(udp + 8);

    r->op=2; r->htype=req->htype; r->hlen=req->hlen; r->hops=0;
    r->xid=req->xid; r->secs=0; r->flags=req->flags;
    r->ciaddr=0; r->yiaddr=htonl(yiaddr); r->siaddr=0; r->giaddr=req->giaddr;
    memcpy(r->chaddr, req->chaddr, 16);
    r->magic = htonl(DHCP_MAGIC);

    int o=0;
    o = opt_put(r->opts,o,53,&msgtype,1);
    uint32_t sid=htonl(server_id);      o = opt_put(r->opts,o,54,&sid,4);
    uint32_t lt=htonl((uint32_t)g_lease_secs); o = opt_put(r->opts,o,51,&lt,4);
    uint32_t t1=htonl((uint32_t)(g_lease_secs/2));   o = opt_put(r->opts,o,58,&t1,4);
    uint32_t t2=htonl((uint32_t)(g_lease_secs*7/8)); o = opt_put(r->opts,o,59,&t2,4);
    uint32_t m=htonl(g_mask);           o = opt_put(r->opts,o,1,&m,4);
    uint32_t gw=htonl(g_gw);            o = opt_put(r->opts,o,3,&gw,4);
    if (g_ndns>0) {
        uint8_t d[8]; int dl=0;
        for (int i=0;i<g_ndns && i<2;i++){ uint32_t x=htonl(g_dns[i]); memcpy(d+dl,&x,4); dl+=4; }
        o = opt_put(r->opts,o,6,d,dl);
    }
    r->opts[o++] = 255;

    int bolen = (int)offsetof(struct bootp, opts) + o;
    if (bolen < 300) bolen = 300;            /* 太短有些客户端会丢 */
    int udplen = 8 + bolen;
    int iplen  = 20 + udplen;
    int total  = 14 + iplen;

    /* Ethernet */
    memcpy(eth, dst_mac, 6);
    /* 源 MAC 用固定的网关侧 MAC：用 guest 自己的 MAC 会让部分驱动把它当本地回环帧 */
    {
        static const uint8_t srv_mac[6] = {0x52,0x54,0x00,0x12,0x34,0x58};
        memcpy(eth + 6, srv_mac, 6);
    }
    eth[12] = 0x08; eth[13] = 0x00;

    /* IPv4 */
    ip[0] = 0x45; ip[1] = 0x00;
    ip[2] = (uint8_t)(iplen >> 8); ip[3] = (uint8_t)iplen;
    ip[4] = 0; ip[5] = 0;
    ip[6] = 0x40; ip[7] = 0x00;              /* DF */
    ip[8] = 64; ip[9] = 17;                  /* TTL, UDP */
    ip[10] = 0; ip[11] = 0;
    uint32_t s = htonl(g_gw), d = htonl(0xFFFFFFFFu);
    memcpy(ip + 12, &s, 4);
    memcpy(ip + 16, &d, 4);
    uint16_t c = ip_csum(ip, 20);
    ip[10] = (uint8_t)(c >> 8); ip[11] = (uint8_t)c;

    /* UDP (checksum=0 表示不校验，IPv4 允许) */
    udp[0] = 0; udp[1] = 67;
    udp[2] = 0; udp[3] = 68;
    udp[4] = (uint8_t)(udplen >> 8); udp[5] = (uint8_t)udplen;
    udp[6] = 0; udp[7] = 0;

    struct sockaddr_ll sll; memset(&sll,0,sizeof(sll));
    sll.sll_family=AF_PACKET; sll.sll_protocol=htons(ETH_P_IP);
    sll.sll_ifindex=rx_ifidx; sll.sll_halen=6;
    memcpy(sll.sll_addr, dst_mac, 6);

    ssize_t n = sendto(fd,frame,(size_t)total,0,(struct sockaddr*)&sll,sizeof(sll));
    if (n<0 && g_verbose)
        fprintf(stderr,"[dd] sendto if=%d failed: %s\n",rx_ifidx,strerror(errno));
}

/* 只放行 UDP sport=68 或 dport=67 的 IPv4 */
static int install_bpf(int fd) {
    struct sock_filter code[] = {
        { 0x28, 0, 0, 0x0000000c },
        { 0x15, 0, 10, 0x00000800 },
        { 0x30, 0, 0, 0x00000017 },
        { 0x15, 0, 8, 0x00000011 },
        { 0x28, 0, 0, 0x00000014 },
        { 0x45, 6, 0, 0x00001fff },
        { 0xb1, 0, 0, 0x0000000e },
        { 0x48, 0, 0, 0x0000000e },
        { 0x15, 2, 0, 0x00000044 },
        { 0x48, 0, 0, 0x00000010 },
        { 0x15, 0, 1, 0x00000043 },
        { 0x06, 0, 0, 0x00040000 },
        { 0x06, 0, 0, 0x00000000 },
    };
    struct sock_fprog prog = { .len=(unsigned short)(sizeof(code)/sizeof(code[0])), .filter=code };
    return setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &prog, sizeof(prog));
}

static int have_if(int idx) {
    for (int i=0;i<g_nsk;i++) if (g_sk[i].ifindex==idx) return 1;
    return 0;
}

static int add_if(const char *name) {
    if (g_only_if && strcmp(name, g_only_if) != 0) return 0;
    int idx = (int)if_nametoindex(name);
    if (idx <= 0 || have_if(idx)) return 0;
    if (g_nsk >= MAX_SOCKS) return -1;

    int fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (fd < 0) return -1;
    struct sockaddr_ll sll; memset(&sll,0,sizeof(sll));
    sll.sll_family=AF_PACKET; sll.sll_protocol=htons(ETH_P_ALL); sll.sll_ifindex=idx;
    if (bind(fd,(struct sockaddr*)&sll,sizeof(sll)) < 0) {
        if (g_verbose) fprintf(stderr,"[dd] bind %s: %s\n",name,strerror(errno));
        close(fd); return -1;
    }
#ifndef PACKET_IGNORE_OUTGOING
#define PACKET_IGNORE_OUTGOING 23
#endif
    int one=1;
    setsockopt(fd, SOL_PACKET, PACKET_IGNORE_OUTGOING, &one, sizeof(one));
    if (!g_nobpf) install_bpf(fd);
    fcntl(fd, F_SETFL, fcntl(fd,F_GETFL,0) | O_NONBLOCK);

    g_sk[g_nsk].fd = fd; g_sk[g_nsk].ifindex = idx;
    snprintf(g_sk[g_nsk].name, IFNAMSIZ, "%s", name);
    g_nsk++;
    return 1;
}

static void rescan(void) {
    DIR *d = opendir("/sys/class/net");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        if (strcmp(e->d_name,"lo") == 0) continue;
        if (add_if(e->d_name) > 0)
            printf("[dd] watching iface=%s idx=%d\n", e->d_name, if_nametoindex(e->d_name));
    }
    closedir(d);
    fflush(stdout);
}

static void handle(int fd, int ifidx, uint8_t *buf, ssize_t n) {
    /* SOCK_RAW 收到的是完整以太网帧，需要逐层剥到 BOOTP */
    if (n < 14 + 20 + 8 + (ssize_t)offsetof(struct bootp, opts)) return;

    uint8_t *eth = buf;
    uint16_t et = (uint16_t)((eth[12] << 8) | eth[13]);
    int off = 14;
    if (et == 0x8100 && n >= 18) { et = (uint16_t)((eth[16] << 8) | eth[17]); off = 18; }
    if (et != 0x0800) return;

    uint8_t *ip = buf + off;
    int ihl = (ip[0] & 0x0f) * 4;
    if (ihl < 20 || ip[9] != 17) return;
    if ((ip[6] & 0x1f) || ip[7]) return;         /* 分片包不处理 */

    uint8_t *udp = ip + ihl;
    if (udp + 8 > buf + n) return;
    uint16_t sport = (uint16_t)((udp[0] << 8) | udp[1]);
    uint16_t dport = (uint16_t)((udp[2] << 8) | udp[3]);

    uint8_t *bo;
    ssize_t bolen;
    if (dport == 67) {          /* 客户端 -> 服务端 */
        bo = udp + 8;
        bolen = n - (bo - buf);
    } else if (sport == 67 && dport == 68) {
        return;                 /* 别把自己发的 OFFER/ACK 当请求 */
    } else {
        return;
    }
    if (bolen < (ssize_t)offsetof(struct bootp, opts)) return;

    struct bootp *p = (struct bootp *)bo;
    if (p->op != 1 || p->htype != 1 || p->hlen != 6) return;
    if (ntohl(p->magic) != DHCP_MAGIC) return;

    int optn = (int)(bolen - (ssize_t)offsetof(struct bootp, opts));
    if (optn <= 0) return;

    struct bootp tmp; memset(&tmp,0,sizeof(tmp));
    memcpy(&tmp, p, offsetof(struct bootp, opts));
    memcpy(tmp.opts, bo + offsetof(struct bootp, opts),
           (size_t)(optn > (int)sizeof(tmp.opts) ? (int)sizeof(tmp.opts) : optn));

    int l53 = (int)sizeof(tmp.opts);
    uint8_t *m53 = opt_find(&tmp,&l53,53);
    if (!m53 || l53 < 1) return;
    uint8_t mtype = m53[0];

    int l50 = (int)sizeof(tmp.opts);
    uint8_t *m50 = opt_find(&tmp,&l50,50);
    uint32_t req_ip = 0;
    if (m50 && l50 >= 4) { memcpy(&req_ip,m50,4); req_ip = ntohl(req_ip); }
    if (!req_ip && p->ciaddr) req_ip = ntohl(p->ciaddr);

    st_rx++;
    char ifn[IFNAMSIZ] = {0};
    if (if_indextoname((unsigned)ifidx, ifn) == NULL) snprintf(ifn,sizeof(ifn),"if%d",ifidx);
    const char *tn = (mtype==1)?"DISCOVER":(mtype==3)?"REQUEST":(mtype==4)?"DECLINE":
                     (mtype==7)?"RELEASE":"OTHER";

    /* 同一 xid 在 1 秒内重复出现 = 同一个包从 tap 和 bridge 各来了一次 */
    if ((mtype == 1 || mtype == 3) && is_dup(p->xid, p->chaddr, mtype)) {
        st_skip++;
        return;
    }

    if (g_verbose) {
        printf("[dd] rx if=%s(%d) mac=%02x:%02x:%02x:%02x:%02x:%02x xid=%08x type=%s\n",
               ifn, ifidx, p->chaddr[0],p->chaddr[1],p->chaddr[2],
               p->chaddr[3],p->chaddr[4],p->chaddr[5], ntohl(p->xid), tn);
        fflush(stdout);
    }

    if (mtype == 1) {
        st_disc++;
        int isnew=0; uint32_t ip = lease_for(p->chaddr,&isnew);
        if (!ip) { st_skip++; return; }
        send_reply(fd,p,ifidx,BCAST_MAC,2,ip,g_gw); st_off++;
        printf("[dd] OFFER mac=%02x:%02x:%02x:%02x:%02x:%02x xid=%08x -> %s (if=%s%s)\n",
               p->chaddr[0],p->chaddr[1],p->chaddr[2],p->chaddr[3],p->chaddr[4],p->chaddr[5],
               ntohl(p->xid), ip_ntoa(ip), ifn, isnew?",new":"");
        fflush(stdout);
    } else if (mtype == 3) {
        st_req++;
        int isnew=0;
        uint32_t ip = req_ip ? req_ip : lease_for(p->chaddr,&isnew);
        if (ip < g_pool_s || ip > g_pool_e) {
            send_reply(fd,p,ifidx,BCAST_MAC,6,0,g_gw); st_nak++;
            printf("[dd] NAK mac=%02x:%02x:%02x:%02x:%02x:%02x req=%s (pool %s-%s)\n",
                   p->chaddr[0],p->chaddr[1],p->chaddr[2],p->chaddr[3],p->chaddr[4],p->chaddr[5],
                   ip_ntoa(req_ip), ip_ntoa(g_pool_s), ip_ntoa(g_pool_e));
            fflush(stdout);
        } else {
            lease_commit(p->chaddr, ip);
            send_reply(fd,p,ifidx,BCAST_MAC,5,ip,g_gw); st_ack++;
            printf("[dd] ACK mac=%02x:%02x:%02x:%02x:%02x:%02x xid=%08x -> %s (if=%s)\n",
                   p->chaddr[0],p->chaddr[1],p->chaddr[2],p->chaddr[3],p->chaddr[4],p->chaddr[5],
                   ntohl(p->xid), ip_ntoa(ip), ifn);
            fflush(stdout);
        }
    } else {
        st_skip++;
    }
}

int main(int argc, char **argv) {
    uint32_t gw=0, mask=0, ps=0, pe=0;
    const char *pool=NULL, *dnss=NULL;
    int opt;
    while ((opt = getopt(argc,argv,"a:r:d:l:i:vnh")) != -1) {
        switch (opt) {
        case 'a': { char b[64]; snprintf(b,sizeof(b),"%s",optarg);
            char *sl=strchr(b,'/');
            if (sl) { *sl=0; int p2=atoi(sl+1);
                mask=(p2<=0||p2>=32)?0xFFFFFF00u:(uint32_t)(0xFFFFFFFFu<<(32-p2)); }
            gw=ip_aton(b); break; }
        case 'r': pool=optarg; break;
        case 'd': dnss=optarg; break;
        case 'l': g_lease_secs=atoi(optarg); if (g_lease_secs<60) g_lease_secs=60; break;
        case 'i': g_only_if=optarg; break;
        case 'v': g_verbose=1; break;
        case 'n': g_nobpf=1; break;
        case 'h': default:
            fprintf(stderr,"usage: %s -a gw/prefix -r start-end -d dns1[,dns2] [-l secs] [-i iface] [-v] [-n]\n",argv[0]);
            return 2;
        }
    }
    if (!gw) gw = ip_aton("192.168.100.1");
    if (!mask) mask = 0xFFFFFF00u;
    if (pool) {
        char b[64]; snprintf(b,sizeof(b),"%s",pool);
        char *dash=strchr(b,'-');
        if (dash) { *dash=0; pe=ip_aton(dash+1); }
        ps=ip_aton(b);
    }
    if (!ps) { ps=(gw&mask)+100; pe=ps+99; }
    if (!pe || pe<ps) pe=ps;
    g_gw=gw; g_mask=mask; g_pool_s=ps; g_pool_e=pe;

    if (dnss) {
        char b[96]; snprintf(b,sizeof(b),"%s",dnss);
        char *save=NULL;
        for (char *t=strtok_r(b,",",&save); t && g_ndns<2; t=strtok_r(NULL,",",&save)) {
            uint32_t x=ip_aton(t); if (x) g_dns[g_ndns++]=x;
        }
    }

    signal(SIGTERM,on_sig); signal(SIGINT,on_sig); signal(SIGPIPE,SIG_IGN);

    printf("gunydhcpd: iface=%s mode=multi-raw gw=%s/%d pool=%s-%s dns=%d lease=%ds\n",
           g_only_if?g_only_if:"all", ip_ntoa(g_gw), __builtin_popcount(mask),
           ip_ntoa(ps), ip_ntoa(pe), g_ndns, g_lease_secs);
    fflush(stdout);

    rescan();
    if (g_nsk == 0) {
        fprintf(stderr,"gunydhcpd: 没有任何可用接口，无法工作\n");
        return 1;
    }
    printf("gunydhcpd: ready, %d iface(s) bound\n", g_nsk);
    fflush(stdout);

    static uint8_t buf[4096];
    time_t last_stat = time(NULL), last_scan = time(NULL);

    while (!g_stop) {
        struct pollfd pf[MAX_SOCKS];
        for (int i=0;i<g_nsk;i++) { pf[i].fd=g_sk[i].fd; pf[i].events=POLLIN; pf[i].revents=0; }
        int r = poll(pf, (nfds_t)g_nsk, 1000);
        if (r < 0) { if (errno==EINTR) continue; break; }

        for (int i=0;i<g_nsk;i++) {
            if (!(pf[i].revents & POLLIN)) continue;
            for (;;) {
                struct sockaddr_ll from; socklen_t fl=sizeof(from);
                ssize_t n = recvfrom(g_sk[i].fd, buf, sizeof(buf), 0, (struct sockaddr*)&from, &fl);
                if (n < 0) break;
                if (from.sll_pkttype == PACKET_OUTGOING) continue;
                handle(g_sk[i].fd, from.sll_ifindex ? from.sll_ifindex : g_sk[i].ifindex, buf, n);
            }
        }

        time_t now = time(NULL);
        if (now - last_scan >= 5) { last_scan = now; rescan(); }
        if (now - last_stat >= 30) {
            last_stat = now;
            printf("[dd] stats rx=%lu discover=%lu offer=%lu request=%lu ack=%lu nak=%lu skip=%lu\n",
                   st_rx,st_disc,st_off,st_req,st_ack,st_nak,st_skip);
            fflush(stdout);
        }
    }

    printf("[dd] exit stats rx=%lu discover=%lu offer=%lu request=%lu ack=%lu nak=%lu skip=%lu\n",
           st_rx,st_disc,st_off,st_req,st_ack,st_nak,st_skip);
    for (int i=0;i<g_nsk;i++) close(g_sk[i].fd);
    return 0;
}
