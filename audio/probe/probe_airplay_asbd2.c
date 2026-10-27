#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <dlfcn.h>
typedef int32_t OSStatus;
typedef OSStatus (*Fn)(uint64_t fmt, void *asbd, void *extra);
static void dump(const char *tag, const uint8_t *b, int n) {
    printf("  %s:", tag);
    for (int i = 0; i < n; i += 4) { uint32_t w; memcpy(&w, b+i, 4);
        char cc[5] = { (char)(w>>24), (char)(w>>16), (char)(w>>8), (char)w, 0 };
        printf(" %02x=%08x", i, w);
        if (i>=4 && (cc[0] >= 0x20 && cc[0] < 0x7f)) printf("('%s')", cc);
    }
    printf("\n");
}
int main(int argc, char **argv) {
    void *h = dlopen("/system/lib64/libAirPlay.so", RTLD_NOW);
    if (!h) { printf("dlopen fail %s\n", dlerror()); return 1; }
    Fn toAsbd = (Fn)dlsym(h, "AirPlayAudioFormatToASBD");
    Fn toPcm  = (Fn)dlsym(h, "AirPlayAudioFormatToPCM");
    if (!argc || !argv[1]) { printf("usage: probe fmt...\n"); return 2; }
    for (int i = 1; i < argc; i++) {
        uint64_t fmt = strtoull(argv[i], NULL, 0);
        uint8_t a[40], b[40], ex[8] = {0};
        memset(a, 0xAA, 40); memset(b, 0xCC, 40);
        OSStatus s1 = toAsbd(fmt, a, ex);
        uint32_t exw; memcpy(&exw, ex, 4);
        printf("fmt=0x%llx ToASBD st=%d extra=%08x\n", (unsigned long long)fmt, s1, exw);
        if (!s1) dump("asbd", a, 36);
        OSStatus s2 = toPcm(fmt, b, ex);
        printf("fmt=0x%llx ToPCM  st=%d\n", (unsigned long long)fmt, s2);
        if (!s2) dump("pcm ", b, 36);
    }
    return 0;
}
