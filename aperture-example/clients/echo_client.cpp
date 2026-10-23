// EchoController 客户端 —— C++17,零第三方依赖,直接用 POSIX socket 手写 RFC6455
//
// 编译:g++ -std=c++17 -O2 -o echo_client_cpp echo_client.cpp
// 运行:./echo_client_cpp [ws://localhost:8080/ws/echo]
//
// 文本帧格式:<路由键>\n<载荷 JSON>      二进制帧:<首字节 frameType><业务字节>
//
// 服务端只会发未掩码的帧;客户端发往服务端的帧必须掩码(RFC6455 §5.3)。

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

namespace {

constexpr char kDefaultUrl[] = "ws://localhost:8080/ws/echo";

// ---------- 握手要用的 SHA-1 / Base64 ----------

uint32_t rol(uint32_t v, int b) { return (v << b) | (v >> (32 - b)); }

std::string sha1(const std::string& in) {
    std::vector<unsigned char> msg(in.begin(), in.end());
    uint64_t bits = static_cast<uint64_t>(msg.size()) * 8;
    msg.push_back(0x80);
    while (msg.size() % 64 != 56) msg.push_back(0);
    for (int i = 7; i >= 0; --i) msg.push_back(static_cast<unsigned char>(bits >> (i * 8)));

    uint32_t h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
    for (size_t off = 0; off < msg.size(); off += 64) {
        uint32_t w[80];
        for (int i = 0; i < 16; ++i) {
            w[i] = (uint32_t(msg[off + i * 4]) << 24) | (uint32_t(msg[off + i * 4 + 1]) << 16) |
                   (uint32_t(msg[off + i * 4 + 2]) << 8) | uint32_t(msg[off + i * 4 + 3]);
        }
        for (int i = 16; i < 80; ++i) w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);

        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; ++i) {
            uint32_t f, k;
            if (i < 20) {
                f = (b & c) | (~b & d);
                k = 0x5A827999u;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1u;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8F1BBCDCu;
            } else {
                f = b ^ c ^ d;
                k = 0xCA62C1D6u;
            }
            uint32_t tmp = rol(a, 5) + f + e + k + w[i];
            e = d;
            d = c;
            c = rol(b, 30);
            b = a;
            a = tmp;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }

    std::string out(20, '\0');
    for (int i = 0; i < 5; ++i)
        for (int j = 0; j < 4; ++j) out[i * 4 + j] = static_cast<char>((h[i] >> (24 - j * 8)) & 0xFF);
    return out;
}

std::string base64(const unsigned char* data, size_t len) {
    static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    for (size_t i = 0; i < len; i += 3) {
        unsigned v = static_cast<unsigned>(data[i]) << 16;
        if (i + 1 < len) v |= static_cast<unsigned>(data[i + 1]) << 8;
        if (i + 2 < len) v |= static_cast<unsigned>(data[i + 2]);
        out += tbl[(v >> 18) & 63];
        out += tbl[(v >> 12) & 63];
        out += (i + 1 < len) ? tbl[(v >> 6) & 63] : '=';
        out += (i + 2 < len) ? tbl[v & 63] : '=';
    }
    return out;
}

std::string base64(const std::string& data) {
    return base64(reinterpret_cast<const unsigned char*>(data.data()), data.size());
}

// ---------- URL 解析(仅 ws://host:port/path) ----------

struct Target {
    std::string host, path = "/";
    int port = 80;
};

Target parseUrl(const std::string& url) {
    Target t;
    std::string rest = url;
    const std::string prefix = "ws://";
    if (rest.rfind(prefix, 0) == 0) rest = rest.substr(prefix.size());

    const size_t slash = rest.find('/');
    const std::string hostPort = (slash == std::string::npos) ? rest : rest.substr(0, slash);
    if (slash != std::string::npos) t.path = rest.substr(slash);

    const size_t colon = hostPort.rfind(':');
    if (colon == std::string::npos) {
        t.host = hostPort;
    } else {
        t.host = hostPort.substr(0, colon);
        t.port = std::atoi(hostPort.c_str() + colon + 1);
    }
    return t;
}

// ---------- TCP 连接 + WebSocket 握手 ----------

int dial(const Target& t) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    const std::string port = std::to_string(t.port);
    if (getaddrinfo(t.host.c_str(), port.c_str(), &hints, &res) != 0 || !res) {
        std::fprintf(stderr, "解析主机失败: %s\n", t.host.c_str());
        return -1;
    }

    int fd = -1;
    for (addrinfo* p = res; p; p = p->ai_next) {
        fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) continue;
        if (connect(fd, p->ai_addr, p->ai_addrlen) == 0) break;
        ::close(fd);
        fd = -1;
    }
    freeaddrinfo(res);
    if (fd >= 0) {
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    } else {
        std::fprintf(stderr, "连接失败: %s:%d\n", t.host.c_str(), t.port);
    }
    return fd;
}

bool recvAll(int fd, char* buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        const ssize_t r = recv(fd, buf + got, n - got, 0);
        if (r <= 0) return false;
        got += static_cast<size_t>(r);
    }
    return true;
}

bool readHttpHeaders(int fd, std::string* out) {
    out->clear();
    char c;
    while (out->find("\r\n\r\n") == std::string::npos) {
        const ssize_t r = recv(fd, &c, 1, 0);
        if (r <= 0) return false;
        out->push_back(c);
        if (out->size() > 16384) return false;
    }
    return true;
}

/// 发起握手,成功返回 socket
int handshake(const Target& t) {
    const int fd = dial(t);
    if (fd < 0) return -1;

    // 16 字节随机 nonce:取当前时间的纳秒位 + pid,示例客户端不需要密码学强度
    uint64_t nonce = static_cast<uint64_t>(::getpid());
    nonce ^= static_cast<uint64_t>(time(nullptr)) * 2654435761u;
    unsigned char raw[16];
    for (int i = 0; i < 16; ++i) raw[i] = static_cast<unsigned char>((nonce >> (i % 8 * 8)) ^ (i * 31));

    const std::string key = base64(std::string(reinterpret_cast<char*>(raw), sizeof(raw)));
    std::string req = "GET " + t.path + " HTTP/1.1\r\n";
    req += "Host: " + t.host + ":" + std::to_string(t.port) + "\r\n";
    req += "Upgrade: websocket\r\n";
    req += "Connection: Upgrade\r\n";
    req += "Sec-WebSocket-Key: " + key + "\r\n";
    req += "Sec-WebSocket-Version: 13\r\n\r\n";
    if (send(fd, req.data(), req.size(), 0) < 0) {
        ::close(fd);
        return -1;
    }

    std::string headers;
    if (!readHttpHeaders(fd, &headers) || headers.rfind("HTTP/1.1 101", 0) != 0) {
        std::fprintf(stderr, "握手失败:\n%s\n", headers.c_str());
        ::close(fd);
        return -1;
    }

    // 校验 Sec-WebSocket-Accept = base64(sha1(key + GUID))
    const std::string expect = base64(sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"));
    if (headers.find("Sec-WebSocket-Accept: " + expect) == std::string::npos) {
        std::fprintf(stderr, "Sec-WebSocket-Accept 校验失败\n");
        ::close(fd);
        return -1;
    }
    return fd;
}

// ---------- 帧收发 ----------

enum Opcode : unsigned char { kText = 0x1, kBinary = 0x2, kClose = 0x8, kPing = 0x9, kPong = 0xA };

struct Frame {
    unsigned char opcode = 0;
    std::string payload;
};

/// 读一帧(客户端收到的帧未掩码)
bool readFrame(int fd, Frame* out) {
    unsigned char head[2];
    if (!recvAll(fd, reinterpret_cast<char*>(head), 2)) return false;

    out->opcode = head[0] & 0x0F;
    const bool masked = (head[1] & 0x80) != 0;
    uint64_t len = head[1] & 0x7F;

    if (len == 126) {
        unsigned char ext[2];
        if (!recvAll(fd, reinterpret_cast<char*>(ext), 2)) return false;
        len = (static_cast<uint64_t>(ext[0]) << 8) | ext[1];
    } else if (len == 127) {
        unsigned char ext[8];
        if (!recvAll(fd, reinterpret_cast<char*>(ext), 8)) return false;
        len = 0;
        for (int i = 0; i < 8; ++i) len = (len << 8) | ext[i];
    }

    unsigned char mask[4] = {0, 0, 0, 0};
    if (masked && !recvAll(fd, reinterpret_cast<char*>(mask), 4)) return false;

    out->payload.assign(static_cast<size_t>(len), '\0');
    if (len && !recvAll(fd, &out->payload[0], static_cast<size_t>(len))) return false;
    for (size_t i = 0; i < out->payload.size(); ++i)
        out->payload[i] = static_cast<char>(static_cast<unsigned char>(out->payload[i]) ^ mask[i % 4]);
    return true;
}

/// 发一帧(客户端必须掩码)
bool writeFrame(int fd, unsigned char opcode, const std::string& payload) {
    std::string frame;
    frame.push_back(static_cast<char>(0x80 | opcode));

    const size_t n = payload.size();
    if (n < 126) {
        frame.push_back(static_cast<char>(0x80 | n));
    } else if (n <= 0xFFFF) {
        frame.push_back(static_cast<char>(0x80 | 126));
        frame.push_back(static_cast<char>((n >> 8) & 0xFF));
        frame.push_back(static_cast<char>(n & 0xFF));
    } else {
        frame.push_back(static_cast<char>(0x80 | 127));
        for (int i = 7; i >= 0; --i) frame.push_back(static_cast<char>((n >> (i * 8)) & 0xFF));
    }

    unsigned char mask[4];
    for (int i = 0; i < 4; ++i) mask[i] = static_cast<unsigned char>(rand() & 0xFF);
    frame.append(reinterpret_cast<char*>(mask), 4);

    const size_t body = frame.size();
    frame.reserve(body + n);
    for (size_t i = 0; i < n; ++i)
        frame.push_back(static_cast<char>(static_cast<unsigned char>(payload[i]) ^ mask[i % 4]));

    size_t sent = 0;
    while (sent < frame.size()) {
        const ssize_t w = send(fd, frame.data() + sent, frame.size() - sent, 0);
        if (w <= 0) return false;
        sent += static_cast<size_t>(w);
    }
    return true;
}

// ---------- 报文助手 ----------

/// 文本帧:首行路由键,第二行载荷 JSON
std::string frame(const char* type, const char* json) { return std::string(type) + "\n" + json; }

/// 打印下行报文(按第一个换行拆成 类型 / 载荷)
void dump(const char* dir, const Frame& f) {
    if (f.opcode == kBinary) {
        std::printf("%s <binary %zu bytes>", dir, f.payload.size());
        for (unsigned char c : f.payload) std::printf(" %02X", c);
        std::printf("\n");
        return;
    }
    const size_t nl = f.payload.find('\n');
    if (nl == std::string::npos) {
        std::printf("%s (无分隔符) %s\n", dir, f.payload.c_str());
        return;
    }
    std::printf("%s %s | %s\n", dir, f.payload.substr(0, nl).c_str(), f.payload.substr(nl + 1).c_str());
}

}  // namespace

int main(int argc, char** argv) {
    srand(static_cast<unsigned>(time(nullptr)));
    const Target target = parseUrl(argc > 1 ? argv[1] : kDefaultUrl);

    const int fd = handshake(target);
    if (fd < 0) {
        std::fprintf(stderr, "确认服务端已启动: mvn -pl aperture-example -am spring-boot:run\n");
        return 1;
    }
    std::printf("已连接 %s:%d%s\n", target.host.c_str(), target.port, target.path.c_str());

    // 上行报文序列:① 原始载荷回显 ② DTO 载荷 ③ 二进制帧 ④ 心跳 ⑤ 校验失败 ⑥ 未注册路由
    struct Step {
        unsigned char opcode;
        std::string payload;
    };
    std::vector<Step> steps = {
        {kText, frame("echo.raw", R"({"text":"hi"})")},
        {kText, frame("echo.dto", R"({"text":"ab","repeat":3})")},
        {kBinary, std::string("\x01\x7F\x00\x80", 4)},
        {kText, frame("heartbeat.ping", "{}")},
        {kText, frame("echo.dto", R"({"text":"","repeat":3})")},
        {kText, frame("echo.not-exist", "{}")},
    };
    size_t sent = 0;

    // 建连后服务端主动下发 echo.hello + session.ready,先把这两条下行报文收完
    for (int i = 0; i < 2; ++i) {
        Frame f;
        if (!readFrame(fd, &f)) break;
        dump("<--", f);
    }

    while (true) {
        if (sent < steps.size()) {
            const Step& s = steps[sent++];
            std::printf("--> [%zu/%zu]\n", sent, steps.size());
            if (!writeFrame(fd, s.opcode, s.payload)) break;
        }

        Frame f;
        if (!readFrame(fd, &f)) break;

        if (f.opcode == kPing) {  // 服务端心跳:回 Pong 维持会话
            writeFrame(fd, kPong, f.payload);
            std::printf("<-- (ping) 已回 pong\n");
            continue;
        }
        if (f.opcode == kClose) {
            writeFrame(fd, kClose, "");
            std::printf("<-- (close) 服务端关闭\n");
            break;
        }
        dump("<--", f);

        // 最后一条回执收完 —— 主动关闭
        if (sent >= steps.size()) {
            writeFrame(fd, kClose, "");
            std::printf("--> (close) 全部步骤完成,主动关闭\n");
            break;
        }
    }

    ::close(fd);
    return 0;
}