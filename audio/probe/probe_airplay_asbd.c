#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <dlfcn.h>

typedef int32_t OSStatus;
typedef OSStatus (*ToASBDFn)(uint32_t fmt, void *asbd, void *extra);
typedef OSStatus (*ToPCMFn)(uint32_t fmt, void *asbd, void *extra);

static void dump(const char *tag, const uint8_t *b, int n) {
    printf("%s: ", tag);
    for (int i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n  words:");
    for (int i = 0; i + 4 <= n; i += 4) {
        uint32_t w; memcpy(&w, b + i, 4);
        char cc[5] = { (char)(w>>24), (char)(w>>16), (char)(w>>8), (char)w, 0 };
        printf(" [%02x]=%08x('%s')", i, w, cc);
    }
    printf("\n  f64@0=%g\n", *(const double*)b);
}

int main(void) {
    void *h = dlopen("/system/lib64/libAirPlay.so", RTLD_NOW);
    if (!h) { printf("dlopen libAirPlay failed: %s\n", dlerror()); return 1; }
    ToASBDFn toAsbd = (ToASBDFn)dlsym(h, "AirPlayAudioFormatToASBD");
    ToPCMFn toPcm = (ToPCMFn)dlsym(h, "AirPlayAudioFormatToPCM");
    printf("toAsbd=%p toPcm=%p\n", (void*)toAsbd, (void*)toPcm);
    uint32_t fmts[] = {1,4,8,16,20,32,33,0x21,0x20};
    for (unsigned i = 0; i < sizeof(fmts)/sizeof(fmts[0]); i++) {
        uint8_t asbd[64], extra[16];
        memset(asbd, 0xAA, sizeof asbd); memset(extra, 0xBB, sizeof extra);
        OSStatus st = toAsbd ? toAsbd(fmts[i], asbd, extra) : -999;
        printf("=== fmt %u (0x%x) ToASBD st=%d  extra:", fmts[i], fmts[i], st);
        for (int k = 0; k < 16; k++) printf("%02x", extra[k]);
        printf("\n");
        dump("asbd", asbd, 40);
        if (toPcm) {
            uint8_t pcm[64]; memset(pcm, 0xCC, sizeof pcm);
            OSStatus st2 = toPcm(fmts[i], pcm, extra);
            printf("--- ToPCM st=%d\n", st2);
            dump("pcm", pcm, 40);
        }
    }
    return 0;
}
