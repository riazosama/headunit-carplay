/*
 * cpauth_client - reference client for the authd unix socket.
 *
 * Used to verify the daemon from the device shell; the Android app implements
 * the same 5-byte framing in Java.
 *
 *   cpauth_client [sockpath] cert
 *   cpauth_client [sockpath] sign <hexchallenge>
 *   cpauth_client [sockpath] serial
 *   cpauth_client [sockpath] roundtrip        # cert + sign + serial, one conn
 *
 * Protocol (big endian): request = u8 op | u16 len | payload
 *                        reply   = u16 len | bytes   (len 0 == error)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define DEFAULT_SOCK "/data/local/tmp/cpauth.sock"
#define OP_CERT 1
#define OP_SIGN 2
#define OP_SERIAL 3

static int connect_to(const char *path)
{
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return -1; }
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", path);
    if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        fprintf(stderr, "connect(%s): %s\n", path, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int xfer(int fd, int op, const uint8_t *payload, size_t plen,
                uint8_t *out, size_t *outlen)
{
    uint8_t req[3 + 256];
    req[0] = (uint8_t)op;
    req[1] = (uint8_t)(plen >> 8);
    req[2] = (uint8_t)(plen & 0xff);
    if (plen) memcpy(req + 3, payload, plen);
    if (write(fd, req, 3 + plen) != (ssize_t)(3 + plen)) { perror("write"); return -1; }
    uint8_t hdr[2];
    if (read(fd, hdr, 2) != 2) { perror("read hdr"); return -1; }
    size_t n = ((size_t)hdr[0] << 8) | hdr[1];
    if (n > *outlen) { fprintf(stderr, "reply %zu > buffer %zu\n", n, *outlen); return -1; }
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, out + got, n - got);
        if (r <= 0) { perror("read body"); return -1; }
        got += (size_t)r;
    }
    *outlen = n;
    return 0;
}

static int hexval(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void print_hex(const uint8_t *p, size_t n)
{
    for (size_t i = 0; i < n; i++) printf("%02x", p[i]);
    printf("\n");
}

static int one(int fd, int op, const uint8_t *payload, size_t plen,
               const char *what)
{
    uint8_t out[4096];
    size_t n = sizeof out;
    if (xfer(fd, op, payload, plen, out, &n) != 0) return 1;
    if (n == 0) {
        fprintf(stderr, "%s: ERROR (0-length reply)\n", what);
        return 1;
    }
    printf("%s len=%zu ", what, n);
    print_hex(out, n);
    return 0;
}

int main(int argc, char **argv)
{
    const char *sock = DEFAULT_SOCK;
    const char *cmd;
    const char *arg = NULL;
    if (argc < 2) {
        fprintf(stderr, "usage: %s [sockpath] cert|sign <hex>|serial|roundtrip\n",
                argv[0]);
        return 2;
    }
    if (argv[1][0] == '/') {
        sock = argv[1];
        if (argc < 3) return 2;
        cmd = argv[2];
        if (argc > 3) arg = argv[3];
    } else {
        cmd = argv[1];
        if (argc > 2) arg = argv[2];
    }

    int fd = connect_to(sock);
    if (fd < 0) return 1;
    int rc = 0;

    if (!strcmp(cmd, "cert")) {
        rc = one(fd, OP_CERT, NULL, 0, "cert");
    } else if (!strcmp(cmd, "serial")) {
        rc = one(fd, OP_SERIAL, NULL, 0, "serial");
    } else if (!strcmp(cmd, "sign")) {
        uint8_t chal[256];
        size_t n = 0;
        int hi = -1;
        if (!arg) { fprintf(stderr, "sign needs hex challenge\n"); close(fd); return 2; }
        for (const char *s = arg; *s; s++) {
            if (*s == ' ' || *s == ':' || *s == '-') continue;
            int v = hexval(*s);
            if (v < 0) { fprintf(stderr, "bad hex\n"); close(fd); return 2; }
            if (hi < 0) { hi = v; }
            else {
                if (n >= sizeof chal) { fprintf(stderr, "challenge too long\n"); close(fd); return 2; }
                chal[n++] = (uint8_t)((hi << 4) | v);
                hi = -1;
            }
        }
        if (hi >= 0) { fprintf(stderr, "odd hex digits\n"); close(fd); return 2; }
        rc = one(fd, OP_SIGN, chal, n, "sign");
    } else if (!strcmp(cmd, "roundtrip")) {
        uint8_t chal[32];
        for (int i = 0; i < 32; i++) chal[i] = (uint8_t)i;
        rc |= one(fd, OP_CERT, NULL, 0, "cert");
        rc |= one(fd, OP_SIGN, chal, sizeof chal, "sign");
        rc |= one(fd, OP_SERIAL, NULL, 0, "serial");
    } else {
        fprintf(stderr, "unknown command %s\n", cmd);
        rc = 2;
    }
    close(fd);
    return rc;
}
