/* EchoController 客户端 —— C11,零第三方依赖,直接用 BSD socket 手写 RFC6455
 *
 * 编译(Windows MinGW-w64):gcc -std=c11 -O2 -o echo_client_c.exe echo_client.c -lws2_32
 * 编译(Linux/macOS):gcc -std=c11 -O2 -o echo_client_c echo_client.c
 * 运行:./echo_client_c [ws://localhost:8080/ws/echo]
 *
 * 文本帧格式:<路由键>\n<载荷 JSON>      二进制帧:<首字节 frameType><业务字节>
 *
 * 服务端只会发未掩码的帧;客户端发往服务端的帧必须掩码(RFC6455 §5.3)。
 */

/* POSIX 特性宏:须在任何头文件之前,否则 -std=c11 下 addrinfo 等声明不可见 */
#define _DEFAULT_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
typedef SOCKET sock_t;
#define INVALID_SOCK INVALID_SOCKET
#define close_socket closesocket
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
typedef int sock_t;
#define INVALID_SOCK (-1)
#define close_socket close
#endif

#define DEFAULT_URL "ws://localhost:8080/ws/echo"
#define RECV_BUF 65536

/* ---------- 握手要用的 SHA-1 / Base64 ---------- */

typedef unsigned int u32;
typedef unsigned long long u64;

static u32 rol(u32 v, int b) { return (v << b) | (v >> (32 - b)); }

static void sha1(const unsigned char* in, size_t len, unsigned char out[20]) {
    unsigned char msg[128];
    u32 h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
    u64 bits = (u64)len * 8;
    size_t i = 0;

    memcpy(msg, in, len);
    msg[len++] = 0x80;
    while (len % 64 != 56) msg[len++] = 0;
    {
        int j;
        for (j = 7; j >= 0; --j) msg[len++] = (unsigned char)((bits >> (j * 8)) & 0xFF);
    }

    for (i = 0; i < len; i += 64) {
        u32 w[80];
        int k;
        for (k = 0; k < 16; ++k)
            w[k] = ((u32)msg[i + k * 4] << 24) | ((u32)msg[i + k * 4 + 1] << 16) |
                   ((u32)msg[i + k * 4 + 2] << 8) | (u32)msg[i + k * 4 + 3];
        for (k = 16; k < 80; ++k) w[k] = rol(w[k - 3] ^ w[k - 8] ^ w[k - 14] ^ w[k - 16], 1);

        u32 a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (k = 0; k < 80; ++k) {
            u32 f, key;
            if (k < 20) {
                f = (b & c) | (~b & d);
                key = 0x5A827999u;
            } else if (k < 40) {
                f = b ^ c ^ d;
                key = 0x6ED9EBA1u;
            } else if (k < 60) {
                f = (b & c) | (b & d) | (c & d);
                key = 0x8F1BBCDCu;
            } else {
                f = b ^ c ^ d;
                key = 0xCA62C1D6u;
            }
            u32 tmp = rol(a, 5) + f + e + key + w[k];
            e = d;
            d = c;
            c = rol(b, 30);
            b = a;
            a = tmp;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }
    for (i = 0; i < 5; ++i)
        for (int j = 0; j < 4; ++j) out[i * 4 + j] = (unsigned char)((h[i] >> (24 - j * 8)) & 0xFF);
}

static void base64(const unsigned char* data, size_t len, char* out) {
    static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t i = 0, o = 0;
    for (; i + 2 < len; i += 3) {
        unsigned v = ((unsigned)data[i] << 16) | ((unsigned)data[i + 1] << 8) | data[i + 2];
        out[o++] = tbl[(v >> 18) & 63];
        out[o++] = tbl[(v >> 12) & 63];
        out[o++] = tbl[(v >> 6) & 63];
        out[o++] = tbl[v & 63];
    }
    if (i < len) {
        unsigned v = (unsigned)data[i] << 16;
        int rem = (int)(len - i);
        if (rem == 2) v |= (unsigned)data[i + 1] << 8;
        out[o++] = tbl[(v >> 18) & 63];
        out[o++] = tbl[(v >> 12) & 63];
        out[o++] = (rem == 2) ? tbl[(v >> 6) & 63] : '=';
        out[o++] = '=';
    }
    out[o] = '\0';
}

/* ---------- URL 解析(仅 ws://host:port/path) ---------- */

typedef struct {
    char host[256];
    char path[512];
    int port;
} target_t;

static void parse_url(const char* url, target_t* t) {
    const char* rest = url;
    const char* p;
    size_t hostLen;

    t->port = 80;
    strcpy(t->path, "/");
    if (strncmp(rest, "ws://", 5) == 0) rest += 5;

    p = strchr(rest, '/');
    hostLen = p ? (size_t)(p - rest) : strlen(rest);
    if (p) strncpy(t->path, p, sizeof(t->path) - 1);

    /* host[:port] */
    const char* colon = memchr(rest, ':', hostLen);
    if (colon) {
        size_t hl = (size_t)(colon - rest);
        memcpy(t->host, rest, hl);
        t->host[hl] = '\0';
        t->port = atoi(colon + 1);
    } else {
        memcpy(t->host, rest, hostLen);
        t->host[hostLen] = '\0';
    }
}

/* ---------- TCP 连接 + WebSocket 握手 ---------- */

static sock_t dial(const target_t* t) {
    struct addrinfo hints, *res = NULL;
    char port[16];
    sock_t fd = INVALID_SOCK;

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    sprintf(port, "%d", t->port);

    if (getaddrinfo(t->host, port, &hints, &res) != 0 || !res) {
        fprintf(stderr, "解析主机失败: %s\n", t->host);
        return INVALID_SOCK;
    }
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd == INVALID_SOCK) continue;
        if (connect(fd, p->ai_addr, (int)p->ai_addrlen) == 0) break;
        close_socket(fd);
        fd = INVALID_SOCK;
    }
    freeaddrinfo(res);
    if (fd == INVALID_SOCK) fprintf(stderr, "连接失败: %s:%d\n", t->host, t->port);
    else {
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, (const char*)&one, sizeof(one));
    }
    return fd;
}

static int recv_all(sock_t fd, char* buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        int r = (int)recv(fd, buf + got, (int)(n - got), 0);
        if (r <= 0) return 0;
        got += (size_t)r;
    }
    return 1;
}

static int read_http_headers(sock_t fd, char* out, size_t cap) {
    size_t len = 0;
    char c;
    out[0] = '\0';
    while (len + 1 < cap) {
        int r = (int)recv(fd, &c, 1, 0);
        if (r <= 0) return 0;
        out[len++] = c;
        out[len] = '\0';
        if (len >= 4 && strcmp(out + len - 4, "\r\n\r\n") == 0) return 1;
    }
    return 0;
}

/* 发起握手,成功返回 socket */
static sock_t handshake(const target_t* t) {
    sock_t fd = dial(t);
    char req[1024], key[32], headers[4096], expect[64];
    unsigned char raw[16], accept[20];
    int n;

    if (fd == INVALID_SOCK) return INVALID_SOCK;

    /* 16 字节随机 nonce:取当前时间位 + 索引扰动,示例客户端不需要密码学强度 */
    {
        u64 nonce = (u64)time(NULL);
        for (n = 0; n < 16; ++n) raw[n] = (unsigned char)((nonce >> ((n % 8) * 8)) ^ (n * 31));
    }
    base64(raw, 16, key);

    n = sprintf(req, "GET %s HTTP/1.1\r\n"
                     "Host: %s:%d\r\n"
                     "Upgrade: websocket\r\n"
                     "Connection: Upgrade\r\n"
                     "Sec-WebSocket-Key: %s\r\n"
                     "Sec-WebSocket-Version: 13\r\n\r\n",
                 t->path, t->host, t->port, key);
    if (send(fd, req, n, 0) < 0) {
        close_socket(fd);
        return INVALID_SOCK;
    }

    if (!read_http_headers(fd, headers, sizeof(headers)) || strncmp(headers, "HTTP/1.1 101", 12) != 0) {
        fprintf(stderr, "握手失败:\n%s\n", headers);
        close_socket(fd);
        return INVALID_SOCK;
    }

    /* 校验 Sec-WebSocket-Accept = base64(sha1(key + GUID)) */
    {
        char material[64];
        int m = snprintf(material, sizeof(material), "%s258EAFA5-E914-47DA-95CA-C5AB0DC85B11", key);
        sha1((const unsigned char*)material, (size_t)m, accept);
        base64(accept, 20, expect);
    }
    if (strstr(headers, expect) == NULL) {
        fprintf(stderr, "Sec-WebSocket-Accept 校验失败\n");
        close_socket(fd);
        return INVALID_SOCK;
    }
    return fd;
}

/* ---------- 帧收发 ---------- */

#define OP_TEXT   0x1
#define OP_BINARY 0x2
#define OP_CLOSE  0x8
#define OP_PING   0x9
#define OP_PONG   0xA

typedef struct {
    unsigned char opcode;
    size_t len;
    unsigned char payload[RECV_BUF];
} frame_t;

/* 读一帧(客户端收到的帧未掩码) */
static int read_frame(sock_t fd, frame_t* f) {
    unsigned char head[2], mask[4];
    unsigned long long len;
    size_t i;

    if (!recv_all(fd, (char*)head, 2)) return 0;
    f->opcode = head[0] & 0x0F;
    len = head[1] & 0x7F;

    if (len == 126) {
        unsigned char ext[2];
        if (!recv_all(fd, (char*)ext, 2)) return 0;
        len = ((unsigned long long)ext[0] << 8) | ext[1];
    } else if (len == 127) {
        unsigned char ext[8];
        int k;
        if (!recv_all(fd, (char*)ext, 8)) return 0;
        len = 0;
        for (k = 0; k < 8; ++k) len = (len << 8) | ext[k];
    }

    if (len > RECV_BUF - 1) {
        fprintf(stderr, "帧超长(%llu),已放弃\n", len);
        return 0;
    }

    if (head[1] & 0x80) { /* 服务端理论上不掩码,仍按协议处理 */
        if (!recv_all(fd, (char*)mask, 4)) return 0;
    }

    f->len = (size_t)len;
    if (len && !recv_all(fd, (char*)f->payload, (size_t)len)) return 0;
    if (head[1] & 0x80)
        for (i = 0; i < f->len; ++i) f->payload[i] = (unsigned char)(f->payload[i] ^ mask[i % 4]);
    return 1;
}

/* 发一帧(客户端必须掩码) */
static int write_frame(sock_t fd, unsigned char opcode, const void* payload, size_t n) {
    static unsigned char buf[RECV_BUF + 16];
    unsigned char mask[4];
    size_t head = 2, i, total;
    int w;

    buf[0] = (unsigned char)(0x80 | opcode);
    if (n < 126) {
        buf[1] = (unsigned char)(0x80 | n);
    } else if (n <= 0xFFFF) {
        buf[1] = (unsigned char)(0x80 | 126);
        buf[2] = (unsigned char)((n >> 8) & 0xFF);
        buf[3] = (unsigned char)(n & 0xFF);
        head = 4;
    } else {
        buf[1] = (unsigned char)(0x80 | 127);
        for (i = 0; i < 8; ++i) buf[2 + i] = (unsigned char)((n >> ((7 - i) * 8)) & 0xFF);
        head = 10;
    }

    for (i = 0; i < 4; ++i) mask[i] = (unsigned char)(rand() & 0xFF);
    memcpy(buf + head, mask, 4);
    head += 4;

    {
        const unsigned char* src = (const unsigned char*)payload;
        for (i = 0; i < n; ++i) buf[head + i] = (unsigned char)(src[i] ^ mask[i % 4]);
    }
    total = head + n;

    {
        size_t sent = 0;
        while (sent < total) {
            w = (int)send(fd, (const char*)buf + sent, (int)(total - sent), 0);
            if (w <= 0) return 0;
            sent += (size_t)w;
        }
    }
    return 1;
}

/* ---------- 报文助手 ---------- */

/* 打印下行报文(按第一个换行拆成 类型 / 载荷) */
static void dump(const char* dir, const frame_t* f) {
    const char* p;
    size_t i, typeLen;

    if (f->opcode == OP_BINARY) {
        printf("%s <binary %zu bytes>", dir, f->len);
        for (i = 0; i < f->len && i < 16; ++i) printf(" %02X", f->payload[i]);
        printf("\n");
        return;
    }
    /* 载荷不补结尾 NUL,统一用 %.*s 打印 */
    p = (const char*)memchr(f->payload, '\n', f->len);
    if (!p) {
        printf("%s (无分隔符) %.*s\n", dir, (int)f->len, (const char*)f->payload);
        return;
    }
    typeLen = (size_t)(p - (const char*)f->payload);
    printf("%s %.*s | %.*s\n", dir, (int)typeLen, (const char*)f->payload,
           (int)(f->len - typeLen - 1), p + 1);
}

typedef struct {
    unsigned char opcode;
    const char* payload;
    unsigned char bytes[8];
    size_t len;
} step_t;

int main(int argc, char** argv) {
    target_t t;
    sock_t fd;
    frame_t f;
    step_t steps[6];
    size_t sent = 0, i;

#if defined(_WIN32)
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        fprintf(stderr, "WSAStartup 失败\n");
        return 1;
    }
#endif
    srand((unsigned)time(NULL));

    parse_url(argc > 1 ? argv[1] : DEFAULT_URL, &t);
    fd = handshake(&t);
    if (fd == INVALID_SOCK) {
        fprintf(stderr, "确认服务端已启动: mvn -pl aperture-example -am spring-boot:run\n");
        return 1;
    }
    printf("已连接 %s:%d%s\n", t.host, t.port, t.path);

    /* 上行报文序列:① 原始载荷回显② DTO 载荷 ③ 二进制帧 ④ 心跳 ⑤ 校验失败 ⑥ 未注册路由 */
    steps[0].opcode = OP_TEXT; steps[0].payload = "echo.raw\n{\"text\":\"hi\"}";
    steps[1].opcode = OP_TEXT; steps[1].payload = "echo.dto\n{\"text\":\"ab\",\"repeat\":3}";
    steps[2].opcode = OP_BINARY; steps[2].bytes[0] = 0x01; steps[2].bytes[1] = 0x7F;
    steps[2].bytes[2] = 0x00; steps[2].bytes[3] = 0x80; steps[2].len = 4;
    steps[3].opcode = OP_TEXT; steps[3].payload = "heartbeat.ping\n{}";
    steps[4].opcode = OP_TEXT; steps[4].payload = "echo.dto\n{\"text\":\"\",\"repeat\":3}";
    steps[5].opcode = OP_TEXT; steps[5].payload = "echo.not-exist\n{}";

    /* 建连后服务端主动下发 echo.hello + session.ready,先把这两条下行报文收完 */
    for (i = 0; i < 2; ++i) {
        if (!read_frame(fd, &f)) return 1;
        dump("<--", &f);
    }

    while (1) {
        if (sent < 6) {
            step_t* s = &steps[sent++];
            printf("--> [%zu/6]\n", sent);
            if (s->opcode == OP_BINARY) {
                if (!write_frame(fd, s->opcode, s->bytes, s->len)) break;
            } else {
                if (!write_frame(fd, s->opcode, s->payload, strlen(s->payload))) break;
            }
        }

        if (!read_frame(fd, &f)) break;

        if (f.opcode == OP_PING) { /* 服务端心跳:回 Pong 维持会话 */
            write_frame(fd, OP_PONG, f.payload, f.len);
            printf("<-- (ping) 已回pong\n");
            continue;
        }
        if (f.opcode == OP_CLOSE) {
            write_frame(fd, OP_CLOSE, f.payload, 0);
            printf("<-- (close) 服务端关闭\n");
            break;
        }
        dump("<--", &f);

        if (sent >= 6) {
            /* 收完最后一条回执后主动关闭 */
            write_frame(fd, OP_CLOSE, "", 0);
            printf("--> (close) 全部步骤完成,主动关闭\n");
            break;
        }
    }

    close_socket(fd);
#if defined(_WIN32)
    WSACleanup();
#endif
    return 0;
}