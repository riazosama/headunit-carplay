/*
 * authd - root-side MFi authentication helper for the BWIC A100 head unit
 *         (Autochips AC8257, arm64, Android 9, userdebug/rooted).
 *
 * WHY
 *   Wireless CarPlay needs iAP2 MFi authentication.  The unit has a genuine
 *   Apple MFi coprocessor on i2c bus 6, address 0x10.  An ordinary Android app
 *   cannot talk to /dev/i2c-6 (and must not, policy-wise), so this helper runs
 *   as root and exposes two operations over a unix domain socket:
 *       op 1 = certificate  (DER PKCS#7 blob read out of the chip)
 *       op 2 = sign         (32-byte challenge -> 64-byte ECDSA P-256 signature)
 *
 * HOW (approach A: reuse the vendor's own MFi platform layer)
 *   /system/lib64/libCoreUtils.so exports the plain-C entry points
 *       MFiPlatform_Initialize()
 *       MFiPlatform_CopyCertificate(void **data, size_t *len)
 *       MFiPlatform_CreateSignature(const void *chal, size_t clen,
 *                                   void **sig, size_t *siglen)
 *       MFiPlatform_Finalize()
 *   These are the exact routines the vendor AirPlay receiver uses.  We dlopen
 *   the library and call them; the library resolves /dev/i2c-6 and 0x10 itself
 *   (via libMFICoprocessor + the Autochips metalogo HIDL service) and contains
 *   the vendor's own retry logic for the shared/busy chip (I2C op timeout 2 s,
 *   ~600 write attempts with 500 us backoff).  We add an outer retry with a
 *   short backoff because ERMOTEIO (121) is common while the vendor iap daemon
 *   and the AirPlay control process contend for the chip.
 *
 *   Approach B (direct CP2.0C register protocol over /dev/i2c-6) is implemented
 *   here only for diagnostics: chip id (0x00), auth version (0x02), certificate
 *   length cross-check (0x30/0x31) and the MFi serial number (0x4E).  It is not
 *   used for the certificate/signature path, because the vendor library is the
 *   authentic implementation.
 *
 * CLI
 *   authd cert                     hex certificate on stdout
 *   authd sign <hexchallenge>      hex signature on stdout (challenge 32 bytes)
 *   authd serial                   MFi serial number (raw bytes, hex)
 *   authd selftest [runs]          sizes / timings / stability report
 *   authd raw <reg> [len]          direct i2c read of one register (diagnostics)
 *   authd daemon [sockpath]        run in the foreground, socket server
 *   authd spawn [sockpath]         daemonize, then serve (used after reboot)
 *   authd stop                     terminate a daemon started with spawn/daemon
 *   authd status                   exit 0 if a daemon is running
 *
 * SOCKET PROTOCOL (all integers big-endian)
 *   request : u8 op | u16 payload_len | payload[payload_len]
 *             op 1 = cert    (payload empty)          -> cert bytes
 *             op 2 = sign    (payload = 32-byte chal) -> 64-byte signature
 *             op 3 = serial  (payload empty)          -> serial bytes
 *   response: u16 len | bytes[len]        len == 0 means "error"
 *   One connection may carry several sequential requests.  Requests are
 *   serialized internally (one chip transaction at a time) and answered with a
 *   bounded deadline, so a busy/stuck chip can never wedge the caller.
 *
 * Build: see build.sh (Android NDK, aarch64-linux-android28, pure C).
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------ config */

#define AUTH_LIB_DEFAULT   "/system/lib64/libCoreUtils.so"
#define I2C_DEV_DEFAULT    "/dev/i2c-6"
#define I2C_ADDR_DEFAULT   0x10
#define SOCK_DEFAULT       "/data/local/tmp/cpauth.sock"
#define PIDFILE_DEFAULT    "/data/local/tmp/authd.pid"
#define LOGFILE_DEFAULT    "/data/local/tmp/authd.log"
#define TIMEOUT_MS_DEFAULT 15000

#define MAX_CLIENTS   8
#define QUEUE_MAX     16
#define MAX_PAYLOAD   256
#define MAX_RESPONSE  4096

#define CERT_RETRIES  60
#define SIGN_RETRIES  30
#define RETRY_BASE_US 10000

#define OP_CERT    1
#define OP_SIGN    2
#define OP_SERIAL  3

#ifndef I2C_SLAVE
#define I2C_SLAVE 0x0703
#endif

/* MFi register map (CP v3, confirmed against libCoreUtils/libiapimpl RE) */
#define REG_CHIP_TYPE   0x00 /* 1 B : 5 or 7 -> serial length 31 or 32     */
#define REG_AUTH_VER    0x02 /* 1 B : 3 -> "Auth v3" (32-byte challenge)    */
#define REG_AUTH_CTRL   0x10 /* write 1 -> start; read status (>=0 ok)      */
#define REG_RESP_LEN    0x11 /* 2 B BE : signature/response length          */
#define REG_RESP_DATA   0x12 /* N B    : signature/response                 */
#define REG_CHAL        0x21 /* 32 B   : challenge (auth v3)                */
#define REG_CERT_LEN    0x30 /* 2 B BE : certificate length                 */
#define REG_CERT_DATA   0x31 /* N B    : certificate data (offset +)        */
#define REG_SERIAL      0x4E /* 31/32 B: MFi serial number                  */

/* --------------------------------------------------------------- utilities */

static int64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void msleep(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static int env_int(const char *name, int def)
{
    const char *s = getenv(name);
    if (!s || !*s) return def;
    char *end = NULL;
    long v = strtol(s, &end, 0);
    if (end == s) return def;
    return (int)v;
}

static const char *env_str(const char *name, const char *def)
{
    const char *s = getenv(name);
    return (s && *s) ? s : def;
}

static void logmsg(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "authd: ");
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    fflush(stderr);
    va_end(ap);
}

static void hex_encode(const uint8_t *p, size_t n, char *out)
{
    static const char d[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) {
        out[2 * i] = d[p[i] >> 4];
        out[2 * i + 1] = d[p[i] & 0xf];
    }
    out[2 * n] = 0;
}

static int hex_val(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* parse hex, ignoring whitespace / ':' / '-' and an optional 0x prefix */
static ssize_t hex_decode(const char *s, uint8_t *out, size_t cap)
{
    size_t n = 0;
    int hi = -1;
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    for (; *s; s++) {
        if (*s == ' ' || *s == '\t' || *s == '\n' || *s == '\r' ||
            *s == ':' || *s == '-' || *s == '_')
            continue;
        int v = hex_val(*s);
        if (v < 0) return -1;
        if (hi < 0) {
            hi = v;
        } else {
            if (n >= cap) return -1;
            out[n++] = (uint8_t)((hi << 4) | v);
            hi = -1;
        }
    }
    if (hi >= 0) return -1;
    return (ssize_t)n;
}

/* ------------------------------------------------------- vendor library (A) */

typedef int  (*mfi_init_fn)(void);
typedef int  (*mfi_cert_fn)(void **data, size_t *len);
typedef int  (*mfi_sign_fn)(const void *chal, size_t clen, void **sig, size_t *siglen);
typedef void (*mfi_fini_fn)(void);

static void          *g_lib;
static mfi_init_fn    g_init;
static mfi_cert_fn    g_cert;
static mfi_sign_fn    g_sign;
static mfi_fini_fn    g_fini;
static int            g_ready;
static pthread_mutex_t g_chip_mtx = PTHREAD_MUTEX_INITIALIZER;

static const char *g_lib_path = AUTH_LIB_DEFAULT;

static int diag_read(uint8_t reg, void *buf, size_t len, int attempts);

/*
 * Chip version / vendor-library work-around.
 *
 * The vendor library detects the chip generation by reading register 0x02 on
 * the first CreateSignature call and caches the result for the whole process.
 * On this unit that read is frequently disturbed by the other chip user (the
 * vendor iap daemon / AirPlay control process): we have observed it return
 * 0x17, which the library logs as "Using MFi Auth v.23 chip" and then takes
 * the legacy 20-byte-challenge path, making every 32-byte signature fail with
 * kSizeErr (-6743) for the rest of the process' life.
 *
 * Fix: determine the version ourselves with a majority vote over several raw
 * reads (cross-checked against the certificate, which is ECDSA P-256 only on
 * an Auth-v3 chip) and, if that says v3, pre-set the library's cached flag so
 * it never performs the fragile read.  The library offset is validated by
 * dladdr() against the known MFiPlatform_CreateSignature address; if the
 * loaded library does not match, the work-around is skipped and the vendor
 * behaviour is used unchanged.
 */
#define LIB_OFF_SIGN   0x63050u   /* MFiPlatform_CreateSignature vaddr    */
#define LIB_OFF_AUTH3  0x100588u  /* cached "is auth v3" int in .data      */
#define SIGN_EKSIZE    (-6743)    /* -0x1a57: response length came back 0 */

static uintptr_t g_lib_base;          /* load bias, 0 until validated     */
static int       g_auth3_state = -1;  /* -1 unsupported 0 untouched 1 v3  */

static int lib_map(void)
{
    /*
     * The link-time address of a symbol is known from the disassembly, so the
     * load bias can be derived from any exported symbol.  Validate it against
     * the other three MFiPlatform_* entries: if this is a different library
     * build the offsets will not line up and the work-around is skipped.
     */
    uintptr_t base = (uintptr_t)g_sign - LIB_OFF_SIGN;
    struct { void *fn; uintptr_t off; const char *name; } chk[] = {
        { (void *)g_init,  0x62980u, "MFiPlatform_Initialize" },
        { (void *)g_cert,  0x62bd8u, "MFiPlatform_CopyCertificate" },
        { (void *)g_fini,  0x63010u, "MFiPlatform_Finalize" },
        { (void *)g_sign,  0x63050u, "MFiPlatform_CreateSignature" },
    };
    for (size_t i = 0; i < sizeof chk / sizeof chk[0]; i++) {
        if ((uintptr_t)chk[i].fn - base != chk[i].off) {
            logmsg("lib layout mismatch at %s; auth3 work-around disabled",
                   chk[i].name);
            return -1;
        }
    }
    g_lib_base = base;
    return 0;
}

static int lib_set_auth3(int v3)
{
    if (!g_lib_base) return -1;
    volatile int *flag = (volatile int *)(g_lib_base + LIB_OFF_AUTH3);
    *flag = v3;
    return (*flag == v3) ? 0 : -1;
}

/* majority vote: returns 1 = v3, 0 = definitely not v3, -1 = inconclusive */
static int detect_auth_v3(int *out_reads)
{
    int reads = 0, v3 = 0, other = 0;
    unsigned char last = 0;
    for (int i = 0; i < 16 && reads < 6; i++) {
        unsigned char v = 0;
        if (diag_read(REG_AUTH_VER, &v, 1, 10) == 0) {
            reads++;
            if (v == 3) v3++; else { other++; last = v; }
        }
        if (reads < 6) msleep(6);
    }
    if (out_reads) *out_reads = reads;
    if (v3 >= 3 && v3 > other) return 1;
    if (v3 == 0 && reads >= 3) { (void)last; return 0; }
    return -1;
}

/* a genuine Auth-v3 chip carries an ECDSA P-256 accessory certificate */
static int cert_is_ecdsa(void)
{
    static const unsigned char oid_ecdsa_sha256[] =
        { 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02 };
    static const unsigned char oid_ec_pubkey[] =
        { 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01 };
    void *cp = NULL;
    uint8_t *cert;
    size_t n = 0;
    if (g_cert(&cp, &n) != 0 || !cp || n < 16) { free(cp); return -1; }
    cert = (uint8_t *)cp;
    int found = -1;
    for (size_t i = 0; i + sizeof oid_ecdsa_sha256 <= n; i++) {
        if (!memcmp(cert + i, oid_ecdsa_sha256, sizeof oid_ecdsa_sha256)) {
            found = 1;
            break;
        }
    }
    if (found < 0)
        for (size_t i = 0; i + sizeof oid_ec_pubkey <= n; i++)
            if (!memcmp(cert + i, oid_ec_pubkey, sizeof oid_ec_pubkey)) {
                found = 1;
                break;
            }
    free(cert);
    return found;
}

/* called after the library is loaded/initialised; safe to call repeatedly */
static void auth3_ensure_cached(void)
{
    if (g_auth3_state >= 0 || !g_ready) return;
    if (getenv("CPAUTH_NO_AUTH3_PATCH")) {
        logmsg("auth3 work-around disabled by env");
        g_auth3_state = 0;
        return;
    }
    if (lib_map() != 0) { g_auth3_state = 0; return; }

    int reads = 0;
    int v = detect_auth_v3(&reads);
    int ecdsa = cert_is_ecdsa();

    if (v == 1) {
        if (lib_set_auth3(1) == 0) {
            g_auth3_state = 1;
            logmsg("auth3: version reads=%d agree v3 (cert_ecdsa=%d); "
                   "pre-set vendor cached auth3=1", reads, ecdsa);
        } else {
            logmsg("auth3: could not write vendor cache; using vendor detection");
            g_auth3_state = 0;
        }
        return;
    }
    if (v == 0 && ecdsa != 1) {
        logmsg("auth3: chip reports non-v3 (reads=%d, cert_ecdsa=%d); "
               "leaving vendor detection alone", reads, ecdsa);
        g_auth3_state = 0;
        return;
    }
    /* inconclusive reads, or a v3-only ECDSA certificate: trust the cert */
    if (ecdsa == 1) {
        if (lib_set_auth3(1) == 0) {
            g_auth3_state = 1;
            logmsg("auth3: reads inconclusive/conflicting (read %s, cert is "
                   "ECDSA); pre-set vendor cached auth3=1",
                   v == 1 ? "v3" : v == 0 ? "non-v3" : "unknown");
        } else {
            g_auth3_state = 0;
        }
    } else {
        logmsg("auth3: inconclusive (reads=%d, cert_ecdsa=%d); leaving vendor "
               "detection alone", reads, ecdsa);
        g_auth3_state = 0;
    }
}

/* dlsym without the ISO-C function-pointer cast warning */
static int sym(void *h, const char *name, void **out)
{
    void *p = dlsym(h, name);
    if (!p) {
        logmsg("dlsym(%s) failed: %s", name, dlerror());
        return -1;
    }
    *out = p;
    return 0;
}

/* must be called single-threaded (daemon startup / CLI) */
static int auth_init(void)
{
    if (g_ready) return 0;

    g_lib = dlopen(g_lib_path, RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) {
        logmsg("dlopen(%s) failed: %s", g_lib_path, dlerror());
        return -1;
    }
    if (sym(g_lib, "MFiPlatform_Initialize", (void **)&g_init) ||
        sym(g_lib, "MFiPlatform_CopyCertificate", (void **)&g_cert) ||
        sym(g_lib, "MFiPlatform_CreateSignature", (void **)&g_sign) ||
        sym(g_lib, "MFiPlatform_Finalize", (void **)&g_fini)) {
        dlclose(g_lib);
        g_lib = NULL;
        return -1;
    }

    int64_t t0 = now_ms();
    int r = g_init();            /* resolves /dev/i2c-N + 0x10, caches the cert */
    logmsg("MFiPlatform_Initialize() -> %d (%lld ms)", r, (long long)(now_ms() - t0));
    g_ready = 1;
    auth3_ensure_cached();
    return 0;
}

/* outer retry: ERMOTEIO from the shared chip is transient */
static int vendor_cert(uint8_t **out, size_t *outlen)
{
    for (int a = 0; a < CERT_RETRIES; a++) {
        void *p = NULL;
        size_t n = 0;
        pthread_mutex_lock(&g_chip_mtx);
        int r = g_cert(&p, &n);
        pthread_mutex_unlock(&g_chip_mtx);
        if (r == 0 && p && n) {
            *out = (uint8_t *)p;
            *outlen = n;
            return 0;
        }
        free(p);
        logmsg("copy certificate attempt %d/%d failed (r=%d)", a + 1,
               CERT_RETRIES, r);
        msleep(RETRY_BASE_US / 1000 * (a + 1));
    }
    return -1;
}

static int vendor_sign(const uint8_t *chal, size_t clen, uint8_t **out,
                       size_t *outlen)
{
    for (int a = 0; a < SIGN_RETRIES; a++) {
        void *p = NULL;
        size_t n = 0;
        pthread_mutex_lock(&g_chip_mtx);
        int r = g_sign(chal, clen, &p, &n);
        pthread_mutex_unlock(&g_chip_mtx);
        if (r == 0 && p && n) {
            *out = (uint8_t *)p;
            *outlen = n;
            return 0;
        }
        free(p);
        logmsg("create signature attempt %d/%d failed (r=%d)", a + 1,
               SIGN_RETRIES, r);
        if (r == SIGN_EKSIZE && a == 0 && g_auth3_state != 1) {
            /* the library may have cached a corrupted version: recover */
            logmsg("auth3: kSizeErr on first attempt, re-running version "
                   "detection");
            auth3_ensure_cached();
            if (g_auth3_state == 1)
                logmsg("auth3: recovered, retrying signature");
        }
        msleep(RETRY_BASE_US / 1000 * (a + 1));
    }
    return -1;
}

/* raw (non-cached-path) certificate access for serial derivation */
static int cert_raw(uint8_t **out, size_t *outlen)
{
    if (auth_init() != 0) return -1;
    return vendor_cert(out, outlen);
}

/* --------------------------------------------- direct i2c (B, diagnostics) */

static int      g_diag_fd = -1;
static char     g_i2c_dev[64] = I2C_DEV_DEFAULT;
static int      g_i2c_addr = I2C_ADDR_DEFAULT;
static pthread_mutex_t g_diag_mtx = PTHREAD_MUTEX_INITIALIZER;

static int diag_open(void)
{
    if (g_diag_fd >= 0) return 0;
    int fd = open(g_i2c_dev, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        logmsg("open(%s) failed: %s", g_i2c_dev, strerror(errno));
        return -1;
    }
    if (ioctl(fd, I2C_SLAVE, g_i2c_addr) < 0) {
        logmsg("ioctl(I2C_SLAVE, 0x%02x) failed: %s", g_i2c_addr,
               strerror(errno));
        close(fd);
        return -1;
    }
    g_diag_fd = fd;
    return 0;
}

/* vendor _DoI2C semantics: write [reg] then read, each op retried */
static int diag_read(uint8_t reg, void *buf, size_t len, int attempts)
{
    if (diag_open() != 0) return -1;
    for (int a = 0; a < attempts; a++) {
        if (write(g_diag_fd, &reg, 1) == 1) {
            ssize_t n = read(g_diag_fd, buf, len);
            if (n == (ssize_t)len) return 0;
        }
        msleep(5 + a * 2);
    }
    return -1;
}

__attribute__((unused))
static int diag_write(uint8_t reg, const void *data, size_t len, int attempts)
{
    uint8_t tmp[64];
    if (len > sizeof(tmp) - 1) return -1;
    if (diag_open() != 0) return -1;
    tmp[0] = reg;
    memcpy(tmp + 1, data, len);
    for (int a = 0; a < attempts; a++) {
        if (write(g_diag_fd, tmp, len + 1) == (ssize_t)(len + 1)) return 0;
        msleep(5 + a * 2);
    }
    return -1;
}

static int diag_read_u16be(uint8_t reg, uint16_t *out)
{
    uint8_t b[2];
    if (diag_read(reg, b, sizeof b, 40) != 0) return -1;
    *out = (uint16_t)((b[0] << 8) | b[1]);
    return 0;
}

struct chip_info {
    int      have_type;
    int      have_ver;
    int      have_certlen;
    uint8_t  type;      /* reg 0x00 */
    uint8_t  version;   /* reg 0x02 */
    uint16_t certlen;   /* reg 0x30 */
};

static void chip_info_read(struct chip_info *ci)
{
    memset(ci, 0, sizeof *ci);
    pthread_mutex_lock(&g_diag_mtx);
    ci->have_type = diag_read(REG_CHIP_TYPE, &ci->type, 1, 20) == 0;
    ci->have_ver = diag_read(REG_AUTH_VER, &ci->version, 1, 20) == 0;
    ci->have_certlen = diag_read_u16be(REG_CERT_LEN, &ci->certlen) == 0;
    pthread_mutex_unlock(&g_diag_mtx);
}

/*
 * MFi serial number.
 *
 * The vendor reads it from chip register 0x4E (31 bytes for chip type 5,
 * 32 bytes for type 7).  On this unit it is the ASCII string
 *     <redacted-unit-mfi-serial>
 * which is exactly the X.509 serial number of the accessory certificate and
 * the suffix of its subject CN (IPA_<serial>).  Raw register reads are the
 * least reliable operation on the shared chip, and a slow read here would
 * stall the single chip worker, so the serial is derived from the (cached,
 * genuine) certificate and the chip register is only cross-checked with a
 * short, bounded budget.
 */
static int cert_raw(uint8_t **out, size_t *outlen);   /* forward */

static int serial_from_cert(uint8_t **out, size_t *outlen)
{
    uint8_t *cert = NULL;
    size_t n = 0;
    if (cert_raw(&cert, &n) != 0) return -1;

    /* expected serial length: chip type 5 -> 31, type 7 -> 32; default 32 */
    size_t want = 32;
    {
        uint8_t t = 0;
        if (diag_read(REG_CHIP_TYPE, &t, 1, 3) == 0 && t == 5) want = 31;
    }

    int rc = -1;
    /* 1) CN is "IPA_<serial>"; the string is also what reg 0x4E returns */
    for (size_t i = 0; i + 4 <= n; i++) {
        if (memcmp(cert + i, "IPA_", 4) != 0) continue;
        size_t avail = n - i - 4;
        size_t len = avail < want ? avail : want;
        /* trim to the last contiguous hex character */
        while (len > 0 && hex_val(cert[i + 4 + len - 1]) < 0) len--;
        if (len < 8) continue;
        int allhex = 1;
        for (size_t k = 0; k < len; k++)
            if (hex_val(cert[i + 4 + k]) < 0) allhex = 0;
        if (!allhex) continue;
        uint8_t *buf = malloc(len);
        if (!buf) break;
        memcpy(buf, cert + i + 4, len);
        *out = buf;
        *outlen = len;
        rc = 0;
        break;
    }

    /* 2) fallback: first INTEGER of >=8 bytes is the X.509 serial */
    if (rc != 0) {
        for (size_t i = 0; i + 2 <= n; i++) {
            if (cert[i] != 0x02) continue;
            size_t l = cert[i + 1] & 0x7f;
            size_t hl = 2;
            if (cert[i + 1] & 0x80) {
                if (l != 1 || i + 3 > n) continue;
                l = cert[i + 2];
                hl = 3;
            }
            if (l >= 8 && l <= 32 && i + hl + l <= n) {
                size_t off = i + hl;
                while (l > 0 && cert[off] == 0x00) { off++; l--; }
                uint8_t *buf = malloc(l * 2 + 1);
                if (!buf) break;
                char *hx = (char *)buf;
                for (size_t k = 0; k < l; k++) {
                    static const char d[] = "0123456789ABCDEF";
                    hx[2 * k] = d[cert[off + k] >> 4];
                    hx[2 * k + 1] = d[cert[off + k] & 0xf];
                }
                if (l * 2 < want) {   /* left-pad short serials with '0' */
                    size_t pad = want - l * 2;
                    memmove(hx + pad, hx, l * 2);
                    memset(hx, '0', pad);
                    l = want;
                }
                *out = buf;
                *outlen = l * 2;
                rc = 0;
                break;
            }
        }
    }
    free(cert);
    return rc;
}

/* bounded chip cross-check of the 0x4E serial (diagnostics only) */
static int serial_from_chip(uint8_t *buf, size_t *len)
{
    size_t want = 32;
    uint8_t t = 0;
    if (diag_read(REG_CHIP_TYPE, &t, 1, 3) == 0 && t == 5) want = 31;
    if (want > *len) want = *len;
    if (diag_read(REG_SERIAL, buf, want, 8) != 0) return -1;
    *len = want;
    return 0;
}

/*
 * Serial for the socket proto: cert-derived (fast, cannot stall the worker).
 * Returns ASCII bytes, exactly the format the vendor reads from reg 0x4E.
 */
static int read_serial(uint8_t **out, size_t *outlen)
{
    return serial_from_cert(out, outlen);
}

/* ----------------------------------------------------------- chip ops (ops) */

static int op_cert(uint8_t **out, size_t *outlen)
{
    if (auth_init() != 0) return -1;
    return vendor_cert(out, outlen);
}

static int op_sign(const uint8_t *chal, size_t clen, uint8_t **out,
                   size_t *outlen)
{
    if (auth_init() != 0) return -1;
    return vendor_sign(chal, clen, out, outlen);
}

/* ---------------------------------------------------------------- daemon */

static volatile sig_atomic_t g_stop;
static int g_listen_fd = -1;
static char g_sock_path[108] = SOCK_DEFAULT;
static char g_pid_path[128] = PIDFILE_DEFAULT;
static int g_timeout_ms = TIMEOUT_MS_DEFAULT;
static volatile int g_clients;
static pthread_mutex_t g_clients_mtx = PTHREAD_MUTEX_INITIALIZER;

struct job {
    struct job *next;
    int         op;
    uint8_t    *payload;
    size_t      plen;
    uint8_t    *result;
    size_t      rlen;
    int         status;
    int         done;
    int         abandoned;      /* accessed under job->mtx */
    pthread_mutex_t mtx;
    pthread_cond_t  cv;
};

static struct job *g_qhead, *g_qtail;
static int g_qlen;
static pthread_mutex_t g_qmtx = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_qcv  = PTHREAD_COND_INITIALIZER;
static pthread_t       g_worker;

static void job_free(struct job *j)
{
    if (!j) return;
    free(j->payload);
    free(j->result);
    pthread_mutex_destroy(&j->mtx);
    pthread_cond_destroy(&j->cv);
    free(j);
}

static void execute_job(struct job *j)
{
    uint8_t *res = NULL;
    size_t n = 0;
    int r = -1;
    switch (j->op) {
    case OP_CERT:   r = op_cert(&res, &n); break;
    case OP_SIGN:   r = op_sign(j->payload, j->plen, &res, &n); break;
    case OP_SERIAL: r = read_serial(&res, &n); break;
    default:        r = -1; break;
    }
    j->result = res;
    j->rlen = n;
    j->status = (r == 0 && res && n) ? 0 : -1;
    if (j->status != 0) {
        free(j->result);
        j->result = NULL;
        j->rlen = 0;
    }
}

static void *worker_main(void *arg)
{
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&g_qmtx);
        while (!g_qhead && !g_stop)
            pthread_cond_wait(&g_qcv, &g_qmtx);
        if (!g_qhead) {          /* g_stop and empty */
            pthread_mutex_unlock(&g_qmtx);
            break;
        }
        struct job *j = g_qhead;
        g_qhead = j->next;
        if (!g_qhead) g_qtail = NULL;
        g_qlen--;
        pthread_mutex_unlock(&g_qmtx);

        pthread_mutex_lock(&j->mtx);
        int skip = j->abandoned;
        pthread_mutex_unlock(&j->mtx);
        if (skip) {
            job_free(j);          /* caller already timed out */
            continue;
        }

        execute_job(j);

        pthread_mutex_lock(&j->mtx);
        if (j->abandoned) {
            pthread_mutex_unlock(&j->mtx);
            job_free(j);          /* caller timed out; we own it now */
        } else {
            j->done = 1;
            pthread_cond_signal(&j->cv);
            pthread_mutex_unlock(&j->mtx);
        }
    }
    return NULL;
}

/*
 * Enqueue one chip transaction and wait for it, bounded by g_timeout_ms.
 * Returns 0 and a malloc'd buffer in out/outlen, or -1 on error/timeout.
 */
static int chip_run(int op, const uint8_t *payload, size_t plen, uint8_t **out,
                    size_t *outlen)
{
    struct job *j = calloc(1, sizeof *j);
    if (!j) return -1;
    j->op = op;
    if (plen) {
        j->payload = malloc(plen);
        if (!j->payload) { free(j); return -1; }
        memcpy(j->payload, payload, plen);
        j->plen = plen;
    }
    pthread_mutex_init(&j->mtx, NULL);
    pthread_cond_init(&j->cv, NULL);

    pthread_mutex_lock(&g_qmtx);
    if (g_qlen >= QUEUE_MAX) {
        pthread_mutex_unlock(&g_qmtx);
        logmsg("chip queue full, rejecting op %d", op);
        job_free(j);
        return -1;
    }
    if (g_qtail) g_qtail->next = j; else g_qhead = j;
    g_qtail = j;
    g_qlen++;
    pthread_cond_signal(&g_qcv);
    pthread_mutex_unlock(&g_qmtx);

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += g_timeout_ms / 1000;
    deadline.tv_nsec += (long)(g_timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }

    pthread_mutex_lock(&j->mtx);
    while (!j->done) {
        int rc = pthread_cond_timedwait(&j->cv, &j->mtx, &deadline);
        if (rc != 0) break;
    }
    if (j->done) {
        int status = j->status;
        *out = j->result;
        *outlen = j->rlen;
        j->result = NULL;
        pthread_mutex_unlock(&j->mtx);
        job_free(j);
        return status;
    }
    /* timed out: mark abandoned so the worker frees the job */
    j->abandoned = 1;
    pthread_mutex_unlock(&j->mtx);
    logmsg("op %d timed out after %d ms", op, g_timeout_ms);
    return -1;
}

static int write_all(int fd, const void *buf, size_t len)
{
    const uint8_t *p = buf;
    while (len) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

/* SO_RCVTIMEO gives up after the socket timeout; distinguish EOF from error */
static int read_full(int fd, void *buf, size_t len)
{
    uint8_t *p = buf;
    while (len) {
        ssize_t n = recv(fd, p, len, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;   /* peer closed */
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int send_response(int fd, const uint8_t *data, size_t len)
{
    uint8_t hdr[2];
    if (len > 0xffff) len = 0;
    hdr[0] = (uint8_t)(len >> 8);
    hdr[1] = (uint8_t)(len & 0xff);
    if (write_all(fd, hdr, 2) != 0) return -1;
    if (len && write_all(fd, data, len) != 0) return -1;
    return 0;
}

static void *client_main(void *arg)
{
    int fd = (int)(intptr_t)arg;
    struct timeval tv = { 60, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);

    for (;;) {
        uint8_t hdr[3];
        if (read_full(fd, hdr, sizeof hdr) != 0) break;
        int op = hdr[0];
        size_t plen = ((size_t)hdr[1] << 8) | hdr[2];
        if (plen > MAX_PAYLOAD) {
            logmsg("bad payload length %zu, closing", plen);
            break;
        }
        uint8_t payload[MAX_PAYLOAD];
        if (plen && read_full(fd, payload, plen) != 0) break;

        uint8_t *resp = NULL;
        size_t rlen = 0;
        int64_t t0 = now_ms();
        int r = -1;
        if (op == OP_CERT || op == OP_SIGN || op == OP_SERIAL) {
            r = chip_run(op, payload, plen, &resp, &rlen);
        } else {
            logmsg("unknown op %d", op);
        }
        if (r == 0 && resp && rlen) {
            logmsg("op %d -> %zu bytes (%lld ms)", op, rlen,
                   (long long)(now_ms() - t0));
            if (send_response(fd, resp, rlen) != 0) {
                free(resp);
                break;
            }
        } else {
            logmsg("op %d -> error (%lld ms)", op, (long long)(now_ms() - t0));
            if (send_response(fd, NULL, 0) != 0) break;
        }
        free(resp);
    }
    close(fd);
    pthread_mutex_lock(&g_clients_mtx);
    g_clients--;
    pthread_mutex_unlock(&g_clients_mtx);
    return NULL;
}

static void on_signal(int sig)
{
    (void)sig;
    g_stop = 1;
    if (g_listen_fd >= 0) {
        int fd = g_listen_fd;
        g_listen_fd = -1;
        close(fd);               /* accept() returns EBADF */
    }
}

static int pidfile_write(void)
{
    char tmp[32];
    int n = snprintf(tmp, sizeof tmp, "%d\n", (int)getpid());
    int fd = open(g_pid_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        logmsg("cannot write pidfile %s: %s", g_pid_path, strerror(errno));
        return -1;
    }
    if (write(fd, tmp, (size_t)n) != n) {
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}

static void pidfile_remove(void)
{
    unlink(g_pid_path);
}

/* The live daemon's pid from the pidfile, or -1. The pidfile survives a power
 * loss and its pid may since belong to another process, so only a process
 * whose argv[0] is authd counts: never report or signal anything else. */
static int read_pid(void)
{
    int fd = open(g_pid_path, O_RDONLY);
    if (fd < 0) return -1;
    char buf[32];
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    int pid = atoi(buf);
    if (pid <= 0) return -1;

    char path[48], cmd[256];
    snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
    fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    n = read(fd, cmd, sizeof cmd - 1);
    close(fd);
    if (n <= 0) return -1;
    cmd[n] = 0;                      /* argv[0] ends at the first NUL */
    const char *base = strrchr(cmd, '/');
    return strcmp(base ? base + 1 : cmd, "authd") == 0 ? pid : -1;
}

static int daemon_serve(void)
{
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    if (auth_init() != 0)
        logmsg("WARNING: MFi vendor library init failed; requests will fail "
               "until the chip becomes available");

    if (pthread_create(&g_worker, NULL, worker_main, NULL) != 0) {
        logmsg("cannot start worker thread");
        return 1;
    }

    unlink(g_sock_path);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        logmsg("socket() failed: %s", strerror(errno));
        return 1;
    }
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    if (strlen(g_sock_path) >= sizeof sa.sun_path) {
        logmsg("socket path too long: %s", g_sock_path);
        close(fd);
        return 1;
    }
    strcpy(sa.sun_path, g_sock_path);

    mode_t old = umask(0);
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        logmsg("bind(%s) failed: %s", g_sock_path, strerror(errno));
        umask(old);
        close(fd);
        return 1;
    }
    umask(old);
    chmod(g_sock_path, 0666);    /* app uids must be able to connect */

    if (listen(fd, 8) < 0) {
        logmsg("listen() failed: %s", strerror(errno));
        close(fd);
        unlink(g_sock_path);
        return 1;
    }
    g_listen_fd = fd;
    pidfile_write();
    logmsg("listening on %s (timeout %d ms, lib %s)", g_sock_path, g_timeout_ms,
           g_lib_path);

    for (;;) {
        int c = accept(fd, NULL, NULL);
        if (c < 0) {
            if (g_stop) break;
            if (errno == EINTR || errno == ECONNABORTED) continue;
            logmsg("accept() failed: %s", strerror(errno));
            break;
        }
        pthread_mutex_lock(&g_clients_mtx);
        if (g_clients >= MAX_CLIENTS) {
            pthread_mutex_unlock(&g_clients_mtx);
            close(c);
            continue;
        }
        g_clients++;
        pthread_mutex_unlock(&g_clients_mtx);

        pthread_t t;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&t, &attr, client_main, (void *)(intptr_t)c) != 0) {
            pthread_attr_destroy(&attr);
            close(c);
            pthread_mutex_lock(&g_clients_mtx);
            g_clients--;
            pthread_mutex_unlock(&g_clients_mtx);
            continue;
        }
        pthread_attr_destroy(&attr);
    }

    logmsg("shutting down");
    pthread_mutex_lock(&g_qmtx);
    g_stop = 1;
    pthread_cond_signal(&g_qcv);
    pthread_mutex_unlock(&g_qmtx);
    pthread_join(g_worker, NULL);
    unlink(g_sock_path);
    pidfile_remove();
    if (g_ready && g_fini) g_fini();
    return 0;
}

/* --------------------------------------------------------------- CLI modes */

static int cli_cert(void)
{
    uint8_t *p = NULL;
    size_t n = 0;
    int64_t t0 = now_ms();
    if (op_cert(&p, &n) != 0) {
        logmsg("certificate read failed");
        return 1;
    }
    char *hex = malloc(n * 2 + 1);
    if (!hex) { free(p); return 1; }
    hex_encode(p, n, hex);
    printf("%s\n", hex);
    fprintf(stderr, "authd: cert len=%zu time=%lldms\n", n,
            (long long)(now_ms() - t0));
    free(hex);
    free(p);
    return 0;
}

static int cli_sign(const char *hex)
{
    uint8_t chal[64];
    ssize_t n = hex_decode(hex, chal, sizeof chal);
    if (n < 0) {
        logmsg("invalid hex challenge");
        return 2;
    }
    if (n == 0) {
        logmsg("empty challenge");
        return 2;
    }
    uint8_t *p = NULL;
    size_t rn = 0;
    int64_t t0 = now_ms();
    if (op_sign(chal, (size_t)n, &p, &rn) != 0) {
        logmsg("sign failed (challenge %zd bytes)", n);
        return 1;
    }
    char *hx = malloc(rn * 2 + 1);
    if (!hx) { free(p); return 1; }
    hex_encode(p, rn, hx);
    printf("%s\n", hx);
    fprintf(stderr, "authd: challenge=%zd sig_len=%zu time=%lldms\n", n, rn,
            (long long)(now_ms() - t0));
    free(hx);
    free(p);
    return 0;
}

static int cli_serial(void)
{
    uint8_t *p = NULL;
    size_t n = 0;
    if (read_serial(&p, &n) != 0) {
        logmsg("serial read failed");
        return 1;
    }
    char *hx = malloc(n * 2 + 1);
    if (!hx) { free(p); return 1; }
    hex_encode(p, n, hx);
    printf("%s\n", hx);
    fprintf(stderr, "authd: serial len=%zu text=\"%.*s\"\n", n, (int)n, p);
    free(hx);
    free(p);
    return 0;
}

static int cli_raw(int reg, int len)
{
    if (reg < 0 || reg > 0xff || len <= 0 || len > 64) {
        logmsg("usage: authd raw <reg> [len<=64]");
        return 2;
    }
    uint8_t buf[64];
    pthread_mutex_lock(&g_diag_mtx);
    int r = diag_read((uint8_t)reg, buf, (size_t)len, 40);
    pthread_mutex_unlock(&g_diag_mtx);
    if (r != 0) {
        logmsg("raw read reg 0x%02x len %d failed", reg, len);
        return 1;
    }
    for (int i = 0; i < len; i++) printf("%02x", buf[i]);
    printf("\n");
    return 0;
}

static int cli_selftest(int runs)
{
    if (runs <= 0) runs = 1;
    int64_t all0 = now_ms();
    printf("=== authd selftest ===\n");
    printf("lib            : %s\n", g_lib_path);
    printf("i2c            : %s addr 0x%02x\n", g_i2c_dev, g_i2c_addr);
    printf("socket         : %s\n", g_sock_path);
    printf("timeout        : %d ms\n", g_timeout_ms);

    struct chip_info ci;
    chip_info_read(&ci);
    if (ci.have_ver)
        printf("chip version   : 0x%02x (%s)\n", ci.version,
               ci.version == 3 ? "Auth v3" : "non-v3");
    else
        printf("chip version   : unavailable (ERMOTEIO contention)\n");
    if (ci.have_type)
        printf("chip type reg0 : 0x%02x (serial len %d)\n", ci.type,
               ci.type == 5 ? 31 : 32);
    else
        printf("chip type reg0 : unavailable\n");
    if (ci.have_certlen)
        printf("certlen reg0x30: 0x%04x (%u)\n", ci.certlen, ci.certlen);
    else
        printf("certlen reg0x30: unavailable\n");

    int64_t t0 = now_ms();
    uint8_t *cert = NULL;
    size_t certlen = 0;
    if (op_cert(&cert, &certlen) != 0) {
        printf("certificate    : FAILED\n[selftest] RESULT: FAIL\n");
        return 1;
    }
    int64_t cert_ms = now_ms() - t0;
    char *chex = malloc(certlen * 2 + 1);
    hex_encode(cert, certlen, chex);
    printf("certificate    : len=%zu time=%lld ms der=%02x%02x%02x%02x head=%.16s "
           "tail=%.16s\n",
           certlen, (long long)cert_ms, cert[0], cert[1], cert[2], cert[3], chex,
           chex + (certlen >= 8 ? certlen * 2 - 16 : 0));

    /* serial: certificate-derived, cross-checked against chip reg 0x4E */
    {
        uint8_t *ser = NULL;
        size_t sl = 0;
        if (serial_from_cert(&ser, &sl) == 0) {
            char tmp[41];
            size_t cl = 0;
            uint8_t chip[40];
            int cr = serial_from_chip(chip, &cl);
            if (cr == 0 && cl < sizeof tmp) {
                memcpy(tmp, chip, cl);
                tmp[cl] = 0;
                printf("serial         : %.*s (cert-derived, %zu chars)\n",
                       (int)sl, (const char *)ser, sl);
                printf("serial reg0x4E : %s (chip, %zu bytes) match=%s\n", tmp, cl,
                       (cl == sl && memcmp(chip, ser, sl) == 0) ? "yes" : "NO");
            } else {
                printf("serial         : %.*s (cert-derived, %zu chars; "
                       "chip read unavailable)\n", (int)sl, (const char *)ser, sl);
            }
            free(ser);
        } else {
            printf("serial         : FAILED\n");
        }
    }

    /* deterministic challenge 00 01 .. 1f (auth v3 requires exactly 32 B) */
    uint8_t chal[32];
    for (size_t i = 0; i < sizeof chal; i++) chal[i] = (uint8_t)i;

    int ok = 0;
    size_t siglen0 = 0;
    int len_stable = 1;
    int64_t tmin = 0, tmax = 0, tsum = 0;
    int distinct = 0;
    uint8_t prev[64];
    size_t prevlen = 0;

    for (int i = 0; i < runs; i++) {
        uint8_t *sig = NULL;
        size_t siglen = 0;
        int64_t s0 = now_ms();
        int r = op_sign(chal, sizeof chal, &sig, &siglen);
        int64_t dt = now_ms() - s0;
        char head[17] = "----------------";
        if (r == 0 && sig && siglen) {
            char *sh = malloc(siglen * 2 + 1);
            hex_encode(sig, siglen, sh);
            memcpy(head, sh, 16);
            head[16] = 0;
            if (prevlen == siglen && memcmp(prev, sig, siglen) != 0) distinct++;
            else if (prevlen != siglen) distinct++;
            if (siglen <= sizeof prev) { memcpy(prev, sig, siglen); prevlen = siglen; }
            free(sh);
            if (i == 0) siglen0 = siglen;
            if (siglen != siglen0) len_stable = 0;
            ok++;
        }
        if (i == 0 || dt < tmin) tmin = dt;
        if (i == 0 || dt > tmax) tmax = dt;
        tsum += dt;
        printf("sign %2d/%-2d    : ret=%d len=%zu time=%lld ms sig=%s\n",
               i + 1, runs, r, siglen, (long long)dt, head);
        free(sig);
    }

    /* certificate determinism: re-read and compare */
    int cert_stable = 0;
    {
        uint8_t *c2 = NULL;
        size_t n2 = 0;
        if (op_cert(&c2, &n2) == 0 && n2 == certlen &&
            memcmp(cert, c2, certlen) == 0)
            cert_stable = 1;
        free(c2);
    }

    printf("summary        : runs=%d ok=%d cert_len=%zu sig_len=%zu "
           "cert_stable=%s sig_len_stable=%s distinct_sigs=%d "
           "time(min/avg/max)=%lld/%lld/%lld ms total=%lld ms\n",
           runs, ok, certlen, siglen0, cert_stable ? "yes" : "NO",
           len_stable ? "yes" : "NO", distinct, (long long)tmin,
           (long long)(tsum / (runs ? runs : 1)), (long long)tmax,
           (long long)(now_ms() - all0));

    int pass = (ok == runs) && cert_stable && len_stable && siglen0 > 0;
    printf("[selftest] RESULT: %s\n", pass ? "PASS" : "FAIL");
    free(chex);
    free(cert);
    return pass ? 0 : 1;
}

static void usage(void)
{
    fprintf(stderr,
        "authd - root MFi auth helper (BWIC A100 / AC8257)\n"
        "usage:\n"
        "  authd cert                    print certificate hex\n"
        "  authd sign <hexchallenge>     print signature hex (32-byte challenge)\n"
        "  authd serial                  print MFi serial number hex\n"
        "  authd selftest [runs]         sizes/timings/stability (default 1)\n"
        "  authd raw <reg> [len]         read one i2c register (diagnostics)\n"
        "  authd daemon [sockpath]       foreground socket server\n"
        "  authd spawn [sockpath]        daemonize + serve (after reboot)\n"
        "  authd stop                    stop a spawned daemon\n"
        "  authd status                  exit 0 if daemon running\n"
        "env: CPAUTH_SOCK CPAUTH_TIMEOUT_MS CPAUTH_LIB CPAUTH_I2C_DEV "
        "CPAUTH_I2C_ADDR\n");
}

static int cmd_stop(void)
{
    int pid = read_pid();
    if (pid <= 0) {
        fprintf(stderr, "authd: no running daemon (pidfile %s)\n", g_pid_path);
        return 1;
    }
    if (kill(pid, SIGTERM) != 0) {
        fprintf(stderr, "authd: kill(%d) failed: %s\n", pid, strerror(errno));
        unlink(g_pid_path);
        return 1;
    }
    for (int i = 0; i < 40; i++) {
        if (kill(pid, 0) != 0) { pidfile_remove(); return 0; }
        msleep(50);
    }
    fprintf(stderr, "authd: daemon %d did not exit, sending SIGKILL\n", pid);
    kill(pid, SIGKILL);
    pidfile_remove();
    return 0;
}

static int cmd_status(void)
{
    int pid = read_pid();
    if (pid <= 0) return 1;
    if (kill(pid, 0) != 0) return 1;
    printf("running pid=%d sock=%s\n", pid, g_sock_path);
    return 0;
}

static int spawn_daemon(void)
{
    const char *logpath = env_str("CPAUTH_LOGFILE", LOGFILE_DEFAULT);
    /* refuse to start a second instance: the old one keeps the socket */
    int running = read_pid();
    if (running > 0 && kill(running, 0) == 0) {
        fprintf(stderr, "authd: daemon already running pid=%d\n", running);
        printf("%d\n", running);
        return 0;
    }
    pid_t pid = fork();
    if (pid < 0) {
        logmsg("fork failed: %s", strerror(errno));
        return 1;
    }
    if (pid > 0) {                   /* parent */
        printf("%d\n", (int)pid);
        return 0;
    }
    setsid();
    pid_t pid2 = fork();             /* fully detach from the adb shell */
    if (pid2 < 0) _exit(1);
    if (pid2 > 0) _exit(0);

    chdir("/");
    int fd = open("/dev/null", O_RDONLY);
    if (fd >= 0) { dup2(fd, 0); if (fd > 2) close(fd); }
    int lf = open(logpath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (lf >= 0) {
        dup2(lf, 1);
        dup2(lf, 2);
        if (lf > 2) close(lf);
    }
    logmsg("=== authd spawn pid=%d ===", (int)getpid());
    int rc = daemon_serve();
    _exit(rc);
}

int main(int argc, char **argv)
{
    const char *mode = (argc > 1) ? argv[1] : "";

    if (argc > 2 && mode[0] == 'd') {
        if (strlen(argv[2]) < sizeof(g_sock_path))
            strcpy(g_sock_path, argv[2]);
    } else if (argc > 2 && mode[0] == 's' && strcmp(mode, "spawn") == 0) {
        if (strlen(argv[2]) < sizeof(g_sock_path))
            strcpy(g_sock_path, argv[2]);
    }
    const char *sock_env = getenv("CPAUTH_SOCK");
    if (sock_env && *sock_env && strlen(sock_env) < sizeof(g_sock_path))
        strcpy(g_sock_path, sock_env);
    const char *i2c_env = getenv("CPAUTH_I2C_DEV");
    if (i2c_env && *i2c_env && strlen(i2c_env) < sizeof(g_i2c_dev))
        strcpy(g_i2c_dev, i2c_env);
    g_i2c_addr = env_int("CPAUTH_I2C_ADDR", I2C_ADDR_DEFAULT);
    g_timeout_ms = env_int("CPAUTH_TIMEOUT_MS", TIMEOUT_MS_DEFAULT);
    if (g_timeout_ms < 1000) g_timeout_ms = 1000;
    const char *lib_env = getenv("CPAUTH_LIB");
    if (lib_env && *lib_env) g_lib_path = lib_env;
    const char *pid_env = getenv("CPAUTH_PIDFILE");
    if (pid_env && *pid_env && strlen(pid_env) < sizeof(g_pid_path))
        strcpy(g_pid_path, pid_env);

    setsid(); /* make sure a daemon is not killed when the adb shell exits */

    if (!strcmp(mode, "cert"))   return cli_cert();
    if (!strcmp(mode, "sign"))   return (argc > 2) ? cli_sign(argv[2]) : (usage(), 2);
    if (!strcmp(mode, "serial")) return cli_serial();
    if (!strcmp(mode, "raw")) {
        int reg = (argc > 2) ? (int)strtol(argv[2], NULL, 0) : -1;
        int len = (argc > 3) ? (int)strtol(argv[3], NULL, 0) : 1;
        return cli_raw(reg, len);
    }
    if (!strcmp(mode, "selftest"))
        return cli_selftest((argc > 2) ? atoi(argv[2]) : 1);
    if (!strcmp(mode, "daemon")) return daemon_serve();
    if (!strcmp(mode, "spawn"))  return spawn_daemon();
    if (!strcmp(mode, "stop"))   return cmd_stop();
    if (!strcmp(mode, "status")) return cmd_status();
    usage();
    return 2;
}
