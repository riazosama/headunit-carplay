/*
 * test_audioconv — on-device proof for audio/libAudioConverter.so
 *
 * Modes:
 *   apple        Apple 40-byte AudioStreamBasicDescription in/out (aac -> lpcm16)
 *   vendor       36-byte vendor format descriptor (what real libAirPlay uses)
 *   shim         dlopen /system/lib64/libAirPlaySupport.so, use ITS exported
 *                AudioConverter* (which dlopen("libAudioConverter.so")) and
 *                build the format descriptors with libAirPlay's own
 *                AirPlayAudioFormatToASBD/ToPCM (real ABI, real shim).
 *   passthrough  lpcm -> lpcm (vendor descriptors), must be bit-exact
 *   abi          just print detected layout information
 *
 * Input: an ADTS .aac file (with --strip-adts the ADTS headers are removed and
 * the raw AAC access units are fed, paired with a magic cookie from argv).
 *
 * Build: see audio/test/build_test.sh
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <dlfcn.h>

typedef int32_t OSStatus;
typedef uint32_t UInt32;
typedef int64_t SInt64;

typedef struct AudioConverterOpaque *AudioConverterRef;

typedef struct {
    UInt32 mNumberChannels;
    UInt32 mDataByteSize;
    void *mData;
} AudioBuffer;

typedef struct {
    UInt32 mNumberBuffers;
    AudioBuffer mBuffers[1];
} AudioBufferList;

typedef struct {
    SInt64 mStartOffset;
    UInt32 mVariableFramesInPacket;
    UInt32 mDataByteSize;
} AudioStreamPacketDescription;

typedef OSStatus (*AudioConverterComplexInputDataProc)(
        AudioConverterRef, UInt32 *, AudioBufferList *,
        AudioStreamPacketDescription **, void *);

typedef OSStatus (*FnNew)(const void *, const void *, AudioConverterRef *);
typedef OSStatus (*FnDispose)(AudioConverterRef);
typedef OSStatus (*FnReset)(AudioConverterRef);
typedef OSStatus (*FnSetProperty)(AudioConverterRef, UInt32, UInt32, const void *);
typedef OSStatus (*FnFill)(AudioConverterRef, AudioConverterComplexInputDataProc,
                           void *, UInt32 *, AudioBufferList *,
                           AudioStreamPacketDescription *);
typedef OSStatus (*FnToASBD)(uint64_t fmt, void *asbd, void *extra);

typedef struct {
    FnNew New;
    FnDispose Dispose;
    FnReset Reset;
    FnSetProperty SetProperty;
    FnFill Fill;
} Api;

/* Apple 40-byte ASBD */
typedef struct {
    double mSampleRate;
    UInt32 mFormatID;
    UInt32 mFormatFlags;
    UInt32 mBytesPerPacket;
    UInt32 mFramesPerPacket;
    UInt32 mBytesPerFrame;
    UInt32 mChannelsPerFrame;
    UInt32 mBitsPerChannel;
    UInt32 mReserved;
} AppleASBD;

/* 36-byte vendor descriptor (real libAirPlay ABI) */
typedef struct {
    UInt32 mSampleRate;
    UInt32 mFormatID;
    UInt32 mFormatFlags;
    UInt32 mBytesPerPacket;
    UInt32 mFramesPerPacket;
    UInt32 mBytesPerFrame;
    UInt32 mChannelsPerFrame;
    UInt32 mBitsPerChannel;
    UInt32 mReserved;
} VendorFmt;

#define FMT_AAC 0x61616320u
#define FMT_LPCM 0x6c70636du
#define FLAG_SIGNED_PACKED 0x0c

/* ---- packet source ---- */

static uint8_t *g_file;
static size_t g_fileLen;
static size_t g_off;
static int g_stripAdts;
static int g_eos;
static uint64_t g_packets;
static uint8_t g_cookie[64];
static size_t g_cookieLen;
static int g_liveMode;      /* 1024-frame requests like libAirPlay */
static uint64_t g_exact1024;
static uint64_t g_partialCalls;

static int adts_header(const uint8_t *p, size_t n, int *hdrLen, int *frameLen,
                       int *rate, int *channels) {
    if (n < 7 || p[0] != 0xFF || (p[1] & 0xF6) != 0xF0)
        return 0;
    int protAbsent = p[1] & 1;
    int hl = 7 + (protAbsent ? 0 : 2);
    int profile = (p[2] >> 6) & 3;
    int fi = (p[2] >> 2) & 0xf;
    int cc = ((p[2] & 1) << 2) | ((p[3] >> 6) & 3);
    int fl = ((p[3] & 3) << 11) | (p[4] << 3) | ((p[5] >> 5) & 7);
    static const int rates[16] = {96000, 88200, 64000, 48000, 44100, 32000,
                                  24000, 22050, 16000, 12000, 11025, 8000};
    *hdrLen = hl;
    *frameLen = fl;
    *rate = fi < 13 ? rates[fi] : 44100;
    *channels = cc ? cc : 2;
    (void)profile;
    return 1;
}

static OSStatus input_proc(AudioConverterRef conv, UInt32 *ioNumberDataPackets,
                           AudioBufferList *ioData,
                           AudioStreamPacketDescription **outPd, void *ud) {
    (void)conv;
    (void)ud;
    if (g_eos || g_off >= g_fileLen) {
        g_eos = 1;
        *ioNumberDataPackets = 0;
        return 0;
    }
    int hl = 0, fl = 0, rate = 0, ch = 0;
    if (!adts_header(g_file + g_off, g_fileLen - g_off, &hl, &fl, &rate, &ch)) {
        fprintf(stderr, "packetizer: no ADTS sync at %zu\n", g_off);
        g_eos = 1;
        *ioNumberDataPackets = 0;
        return 0;
    }
    if (fl <= 0 || (size_t)fl > g_fileLen - g_off) {
        fprintf(stderr, "packetizer: bad frame length %d at %zu\n", fl, g_off);
        g_eos = 1;
        *ioNumberDataPackets = 0;
        return 0;
    }
    const uint8_t *payload = g_file + g_off + (g_stripAdts ? hl : 0);
    size_t payloadLen = (size_t)fl - (g_stripAdts ? hl : 0);
    g_off += (size_t)fl;
    g_packets++;

    *ioNumberDataPackets = 1;
    ioData->mNumberBuffers = 1;
    ioData->mBuffers[0].mNumberChannels = (UInt32)ch;
    ioData->mBuffers[0].mDataByteSize = (UInt32)payloadLen;
    ioData->mBuffers[0].mData = (void *)payload;
    if (outPd) {
        static AudioStreamPacketDescription pd;
        pd.mStartOffset = 0;
        pd.mVariableFramesInPacket = 0;
        pd.mDataByteSize = (UInt32)payloadLen;
        *outPd = &pd;
    }
    return 0;
}

static int read_file(const char *path, uint8_t **out, size_t *outLen);
static int load_lib(Api *api);
static void print_stats(const char *mode, const char *inPath, const char *outPath,
                        uint64_t framesOut, uint64_t bytesOut, uint64_t calls,
                        int16_t first, int16_t last, int peak, uint32_t rate,
                        uint32_t channels, int rc);

/* ---- raw PCM source (passthrough mode) ---- */

static size_t g_pcmOff;
static uint32_t g_pcmChannels = 2;
static int g_pcmEos;

static OSStatus pcm_input_proc(AudioConverterRef conv, UInt32 *ioNumberDataPackets,
                               AudioBufferList *ioData,
                               AudioStreamPacketDescription **outPd, void *ud) {
    (void)conv;
    (void)ud;
    (void)outPd;
    uint32_t bpf = 2 * g_pcmChannels;
    if (g_pcmEos || g_pcmOff >= g_fileLen) {
        g_pcmEos = 1;
        *ioNumberDataPackets = 0;
        return 0;
    }
    uint32_t want = *ioNumberDataPackets;
    size_t availFrames = (g_fileLen - g_pcmOff) / bpf;
    if (availFrames < want)
        want = (uint32_t)availFrames;
    if (want == 0) {
        g_pcmEos = 1;
        *ioNumberDataPackets = 0;
        return 0;
    }
    ioData->mNumberBuffers = 1;
    ioData->mBuffers[0].mNumberChannels = g_pcmChannels;
    ioData->mBuffers[0].mDataByteSize = want * bpf;
    ioData->mBuffers[0].mData = g_file + g_pcmOff;
    g_pcmOff += (size_t)want * bpf;
    *ioNumberDataPackets = want;
    return 0;
}

static int run_passthrough(const char *inPath, const char *outPath, Api *api,
                           uint32_t rate, uint32_t channels) {
    if (read_file(inPath, &g_file, &g_fileLen) != 0)
        return 1;
    g_pcmOff = 0;
    g_pcmEos = 0;
    g_pcmChannels = channels;
    VendorFmt vin = {rate, FMT_LPCM, FLAG_SIGNED_PACKED,
                     (UInt32)(2 * channels), 1, (UInt32)(2 * channels),
                     channels, 16, 0};
    VendorFmt vout = vin;
    AudioConverterRef conv = NULL;
    OSStatus st = api->New(&vin, &vout, &conv);
    printf("AudioConverterNew(lpcm->lpcm) -> %d conv=%p\n", (int)st, (void *)conv);
    if (st != 0 || !conv) {
        printf("RESULT status=FAIL reason=new_failed rc=%d\n", (int)st);
        return 1;
    }
    const uint32_t FRAME_CAP = 4096;
    uint8_t *pcm = (uint8_t *)malloc(FRAME_CAP * 2 * channels);
    FILE *fo = fopen(outPath, "wb");
    if (!pcm || !fo) {
        fprintf(stderr, "OOM/open fail\n");
        return 1;
    }
    uint64_t framesOut = 0, bytesOut = 0, calls = 0;
    int16_t first = 0, last = 0;
    int peak = 0;
    OSStatus lastRc = 0;
    for (int iter = 0; iter < 200000; iter++) {
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = channels;
        abl.mBuffers[0].mDataByteSize = FRAME_CAP * 2 * channels;
        abl.mBuffers[0].mData = pcm;
        UInt32 ioNum = FRAME_CAP;
        lastRc = api->Fill(conv, pcm_input_proc, NULL, &ioNum, &abl, NULL);
        calls++;
        if (ioNum > 0) {
            size_t n = abl.mBuffers[0].mDataByteSize;
            fwrite(pcm, 1, n, fo);
            bytesOut += n;
            int16_t *s = (int16_t *)pcm;
            uint64_t samples = n / 2;
            if (framesOut == 0 && samples)
                first = s[0];
            if (samples)
                last = s[samples - 1];
            for (uint64_t k = 0; k < samples; k++) {
                int v = s[k];
                if (v < 0) v = -v;
                if (v > peak) peak = v;
            }
            framesOut += ioNum;
        }
        if (g_pcmEos && ioNum == 0)
            break;
    }
    fclose(fo);
    free(pcm);
    print_stats("passthrough", inPath, outPath, framesOut, bytesOut, calls, first,
                last, peak, rate, channels, (int)lastRc);
    api->Dispose(conv);
    free(g_file);
    g_file = NULL;
    return (framesOut > 0) ? 0 : 1;
}

static int read_file(const char *path, uint8_t **out, size_t *outLen) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        return -1;
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *p = (uint8_t *)malloc((size_t)n ? (size_t)n : 1);
    if (!p || fread(p, 1, (size_t)n, f) != (size_t)n) {
        fprintf(stderr, "read failed %s\n", path);
        fclose(f);
        free(p);
        return -1;
    }
    fclose(f);
    *out = p;
    *outLen = (size_t)n;
    return 0;
}

static void print_stats(const char *mode, const char *inPath, const char *outPath,
                        uint64_t framesOut, uint64_t bytesOut, uint64_t calls,
                        int16_t first, int16_t last, int peak, uint32_t rate,
                        uint32_t channels, int rc) {
    printf("MODE=%s IN=%s OUT=%s\n", mode, inPath, outPath);
    printf("RESULT frames=%llu bytes=%llu calls=%llu packets=%llu rate=%u ch=%u "
           "first=%d last=%d peak=%d rc=%d status=%s\n",
           (unsigned long long)framesOut, (unsigned long long)bytesOut,
           (unsigned long long)calls, (unsigned long long)g_packets, rate, channels,
           first, last, peak, rc, (framesOut > 0 && peak > 0) ? "PASS" : "FAIL");
    fflush(stdout);
}

static int run_decode(const char *mode, const char *inPath, const char *outPath,
                      Api *api, const void *inFmt, const void *outFmt,
                      uint32_t rate, uint32_t channels) {
    int strip = g_stripAdts;
    if (read_file(inPath, &g_file, &g_fileLen) != 0)
        return 1;
    g_off = 0;
    g_eos = 0;
    g_packets = 0;

    AudioConverterRef conv = NULL;
    OSStatus st = api->New(inFmt, outFmt, &conv);
    printf("AudioConverterNew -> %d conv=%p\n", (int)st, (void *)conv);
    if (st != 0 || !conv) {
        printf("RESULT status=FAIL reason=new_failed rc=%d\n", (int)st);
        return 1;
    }
    if (g_cookieLen > 0) {
        OSStatus sp = api->SetProperty(conv, 0x646d6763 /* 'dmgc' */,
                                       (UInt32)g_cookieLen, g_cookie);
        printf("SetProperty(dmgc,%zu) -> %d\n", g_cookieLen, (int)sp);
    } else if (strip) {
        /* derive a cookie from the first ADTS header (AAC-LC 2 bytes) */
        int hl, fl, r, c;
        if (adts_header(g_file, g_fileLen, &hl, &fl, &r, &c)) {
            int profile = (g_file[2] >> 6) & 3;
            int fi = (g_file[2] >> 2) & 0xf;
            uint32_t v = ((uint32_t)(profile + 1) << 11) | ((uint32_t)fi << 7) |
                         ((uint32_t)c << 3);
            uint8_t asc[2] = {(uint8_t)(v >> 8), (uint8_t)(v & 0xff)};
            OSStatus sp = api->SetProperty(conv, 0x646d6763, 2, asc);
            printf("SetProperty(dmgc,synth %02x%02x) -> %d\n", asc[0], asc[1], (int)sp);
        }
    }

    const uint32_t FRAME_CAP = g_liveMode ? 1024 : 4096;
    g_exact1024 = 0;
    g_partialCalls = 0;
    uint8_t *pcm = (uint8_t *)malloc(FRAME_CAP * 4 + 4096);
    if (!pcm) {
        fprintf(stderr, "OOM\n");
        return 1;
    }
    FILE *fo = fopen(outPath, "wb");
    if (!fo) {
        perror(outPath);
        return 1;
    }

    uint64_t framesOut = 0, bytesOut = 0, calls = 0, retries = 0;
    int16_t first = 0, last = 0;
    int peak = 0;
    OSStatus lastRc = 0;
    for (int iter = 0; iter < 200000; iter++) {
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = channels;
        abl.mBuffers[0].mDataByteSize = FRAME_CAP * 4;
        abl.mBuffers[0].mData = pcm;
        UInt32 ioNum = FRAME_CAP;
        lastRc = api->Fill(conv, input_proc, NULL, &ioNum, &abl, NULL);
        calls++;
        if (g_liveMode) {
            if (ioNum == 1024)
                g_exact1024++;
            else if (ioNum > 0)
                g_partialCalls++;
        }
        if (ioNum > 0 && abl.mBuffers[0].mDataByteSize > 0) {
            size_t n = abl.mBuffers[0].mDataByteSize;
            fwrite(pcm, 1, n, fo);
            bytesOut += n;
            framesOut += ioNum;
            int16_t *s = (int16_t *)pcm;
            uint64_t samples = n / 2;
            if (samples > 0) {
                if (framesOut == ioNum)
                    first = s[0];
                last = s[samples - 1];
                for (uint64_t k = 0; k < samples; k++) {
                    int v = s[k];
                    if (v < 0)
                        v = -v;
                    if (v > peak)
                        peak = v;
                }
            }
        }
        if (g_eos) {
            if (ioNum == 0) {
                retries++;
                if (retries > 3)
                    break;
            } else {
                retries = 0;
            }
        }
        if (lastRc != 0 && lastRc != -6750 && lastRc != 0x6f703f3f) {
            printf("Fill returned %d (0x%x)\n", (int)lastRc, (unsigned)lastRc);
            break;
        }
    }
    fclose(fo);
    free(pcm);
    print_stats(mode, inPath, outPath, framesOut, bytesOut, calls, first, last, peak,
                rate, channels, (int)lastRc);
    if (g_liveMode)
        printf("LIVE requests: exact1024=%llu partial=%llu\n",
               (unsigned long long)g_exact1024, (unsigned long long)g_partialCalls);
    api->Dispose(conv);
    free(g_file);
    g_file = NULL;
    if (g_liveMode && framesOut > 0 && g_exact1024 * 2 < calls)
        return 1;
    return (framesOut > 0 && peak > 0) ? 0 : 1;
}

static int hex_decode(const char *hex, uint8_t *out, size_t cap) {
    size_t n = strlen(hex);
    if (n % 2)
        return -1;
    n /= 2;
    if (n > cap)
        return -1;
    for (size_t i = 0; i < n; i++) {
        unsigned v;
        if (sscanf(hex + 2 * i, "%2x", &v) != 1)
            return -1;
        out[i] = (uint8_t)v;
    }
    return (int)n;
}

static void print_vendor(const char *tag, const VendorFmt *f) {
    char cc[5] = {(char)((f->mFormatID >> 24) & 0xff),
                  (char)((f->mFormatID >> 16) & 0xff),
                  (char)((f->mFormatID >> 8) & 0xff), (char)(f->mFormatID & 0xff), 0};
    printf("%s: rate=%u fmt='%s' flags=0x%x bpp=%u fpp=%u bpf=%u ch=%u bits=%u\n",
           tag, f->mSampleRate, cc, f->mFormatFlags, f->mBytesPerPacket,
           f->mFramesPerPacket, f->mBytesPerFrame, f->mChannelsPerFrame,
           f->mBitsPerChannel);
}

static int load_lib(Api *api) {
    void *h = dlopen("./libAudioConverter.so", RTLD_NOW | RTLD_LOCAL);
    if (!h)
        h = dlopen("libAudioConverter.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        printf("RESULT status=FAIL reason=dlopen_lib %s\n", dlerror());
        return -1;
    }
    api->New = (FnNew)dlsym(h, "AudioConverterNew");
    api->Dispose = (FnDispose)dlsym(h, "AudioConverterDispose");
    api->Reset = (FnReset)dlsym(h, "AudioConverterReset");
    api->SetProperty = (FnSetProperty)dlsym(h, "AudioConverterSetProperty");
    api->Fill = (FnFill)dlsym(h, "AudioConverterFillComplexBuffer");
    if (!api->New || !api->Dispose || !api->Reset || !api->SetProperty || !api->Fill) {
        printf("RESULT status=FAIL reason=missing_symbols\n");
        return -1;
    }
    return 0;
}

/* Session-setup compatibility: PCM -> AAC encode converter must be creatable
 * and accept 'pakl' (libAirPlay tears the session down otherwise). */
static int test_encode_stub(uint32_t rate) {
    Api api;
    if (load_lib(&api) != 0)
        return 1;
    VendorFmt vin = {rate, FMT_LPCM, FLAG_SIGNED_PACKED, 4, 1, 4, 2, 16, 0};
    VendorFmt vout_aac = {rate, FMT_AAC, 0, 0, 1024, 0, 2, 0, 0};
    VendorFmt vout_eld = {rate, 0x61616365u /* 'aace' */, 0, 0, 480, 0, 2, 0, 0};
    int fail = 0;
    for (int which = 0; which < 2; which++) {
        AudioConverterRef conv = NULL;
        OSStatus st = api.New(&vin, which ? &vout_eld : &vout_aac, &conv);
        printf("encode-stub New(%s) -> %d\n", which ? "aace" : "aac ", (int)st);
        if (st != 0 || !conv) {
            fail = 1;
            continue;
        }
        int32_t pakl = 1114;
        OSStatus sp = api.SetProperty(conv, 0x70616b6c, 4, &pakl);
        printf("encode-stub SetProperty(pakl,4,%d) -> %d\n", pakl, (int)sp);
        if (sp != 0)
            fail = 1;
        uint8_t fake[64];
        memset(fake, 0, sizeof(fake));
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = 2;
        abl.mBuffers[0].mDataByteSize = 64;
        abl.mBuffers[0].mData = fake;
        UInt32 ioNum = 16;
        OSStatus fr = api.Fill(conv, input_proc, NULL, &ioNum, &abl, NULL);
        printf("encode-stub Fill -> %d ioNum=%u (expected non-zero op-not-supported)\n",
               (int)fr, ioNum);
        if (fr == 0 && ioNum != 0)
            fail = 1;
        api.Dispose(conv);
    }
    printf("RESULT test=encode-stub status=%s\n", fail ? "FAIL" : "PASS");
    return fail;
}

static OSStatus nodata_proc(AudioConverterRef conv, UInt32 *ioNumberDataPackets,
                            AudioBufferList *ioData,
                            AudioStreamPacketDescription **outPd, void *ud) {
    (void)conv; (void)ioData; (void)outPd; (void)ud;
    *ioNumberDataPackets = 0;
    return -6750;
}

static OSStatus eos_proc(AudioConverterRef conv, UInt32 *ioNumberDataPackets,
                         AudioBufferList *ioData,
                         AudioStreamPacketDescription **outPd, void *ud) {
    (void)conv; (void)ioData; (void)outPd; (void)ud;
    *ioNumberDataPackets = 0;
    return 0;
}

static int test_nodata(void) {
    Api api;
    if (load_lib(&api) != 0)
        return 1;
    VendorFmt vin = {44100, FMT_AAC, 0, 0, 1024, 0, 2, 0, 0};
    VendorFmt vout = {44100, FMT_LPCM, FLAG_SIGNED_PACKED, 4, 1, 4, 2, 16, 0};
    AudioConverterRef conv = NULL;
    OSStatus st = api.New(&vin, &vout, &conv);
    if (st != 0 || !conv) {
        printf("RESULT test=nodata status=FAIL reason=new\n");
        return 1;
    }
    /* create the decoder first with an EOS? no: decoder is created on fill */
    uint8_t buf[4096];
    int fail = 0;
    for (int i = 0; i < 3; i++) {
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = 2;
        abl.mBuffers[0].mDataByteSize = sizeof(buf);
        abl.mBuffers[0].mData = buf;
        UInt32 ioNum = 1024;
        OSStatus rc = api.Fill(conv, nodata_proc, NULL, &ioNum, &abl, NULL);
        printf("nodata Fill#%d rc=%d ioNum=%u\n", i, (int)rc, ioNum);
        if (rc != -6750 || ioNum != 0)
            fail = 1;
    }
    api.Dispose(conv);
    printf("RESULT test=nodata status=%s\n", fail ? "FAIL" : "PASS");
    return fail;
}

static int test_eos(void) {
    Api api;
    if (load_lib(&api) != 0)
        return 1;
    VendorFmt vin = {44100, FMT_AAC, 0, 0, 1024, 0, 2, 0, 0};
    VendorFmt vout = {44100, FMT_LPCM, FLAG_SIGNED_PACKED, 4, 1, 4, 2, 16, 0};
    AudioConverterRef conv = NULL;
    if (api.New(&vin, &vout, &conv) != 0 || !conv) {
        printf("RESULT test=eos status=FAIL reason=new\n");
        return 1;
    }
    uint8_t buf[4096];
    int fail = 0;
    for (int i = 0; i < 4; i++) {
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = 2;
        abl.mBuffers[0].mDataByteSize = sizeof(buf);
        abl.mBuffers[0].mData = buf;
        UInt32 ioNum = 1024;
        OSStatus rc = api.Fill(conv, eos_proc, NULL, &ioNum, &abl, NULL);
        printf("eos Fill#%d rc=%d ioNum=%u\n", i, (int)rc, ioNum);
        if (rc != 0)
            fail = 1;
    }
    api.Dispose(conv);
    printf("RESULT test=eos status=%s\n", fail ? "FAIL" : "PASS");
    return fail;
}

/* Output-buffer bounds: tiny buffer + huge request must never overrun. */
static int test_bounds(const char *inPath, uint32_t rate, uint32_t channels) {
    Api api;
    if (load_lib(&api) != 0)
        return 1;
    if (read_file(inPath, &g_file, &g_fileLen) != 0)
        return 1;
    g_off = 0;
    g_eos = 0;
    g_packets = 0;
    VendorFmt vin = {rate, FMT_AAC, 0, 0, 1024, 0, channels, 0, 0};
    VendorFmt vout = {rate, FMT_LPCM, FLAG_SIGNED_PACKED, 4 * channels, 1,
                      4 * channels, channels, 16, 0};
    AudioConverterRef conv = NULL;
    if (api.New(&vin, &vout, &conv) != 0 || !conv) {
        free(g_file);
        g_file = NULL;
        printf("RESULT test=bounds status=FAIL reason=new\n");
        return 1;
    }
    /* 128 data bytes + 256 canary bytes, odd size to provoke overruns */
    enum { BUF = 384, DATA = 128, CANARY = 0xA5 };
    uint8_t *mem = (uint8_t *)malloc(BUF);
    memset(mem, CANARY, BUF);
    int fail = 0;
    uint64_t total = 0;
    for (int i = 0; i < 40; i++) {
        memset(mem + DATA, CANARY, BUF - DATA);
        AudioBufferList abl;
        abl.mNumberBuffers = 1;
        abl.mBuffers[0].mNumberChannels = channels;
        abl.mBuffers[0].mDataByteSize = DATA;
        abl.mBuffers[0].mData = mem;
        UInt32 ioNum = 0x7fffffff; /* absurd request: library must clamp */
        OSStatus rc = api.Fill(conv, input_proc, NULL, &ioNum, &abl, NULL);
        if (abl.mBuffers[0].mDataByteSize > DATA) {
            printf("bounds: mDataByteSize %u > cap %u\n",
                   abl.mBuffers[0].mDataByteSize, DATA);
            fail = 1;
            break;
        }
        if (ioNum * 4u * channels > DATA) {
            printf("bounds: ioNum %u -> %u bytes > cap %u\n", ioNum,
                   ioNum * 4u * channels, DATA);
            fail = 1;
            break;
        }
        for (int k = DATA; k < BUF; k++) {
            if (mem[k] != CANARY) {
                printf("bounds: canary clobbered at offset %d (0x%02x)\n", k, mem[k]);
                fail = 1;
                break;
            }
        }
        if (fail)
            break;
        total += abl.mBuffers[0].mDataByteSize;
        if (rc != 0 && rc != -6750)
            break;
    }
    printf("bounds: checked %llu bytes out through 128-byte buffers\n",
           (unsigned long long)total);
    api.Dispose(conv);
    free(mem);
    free(g_file);
    g_file = NULL;
    printf("RESULT test=bounds status=%s\n", fail ? "FAIL" : "PASS");
    return fail;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr,
                "usage: %s apple|vendor|shim|passthrough|abi <in> <out> "
                "[cookie_hex] [strip_adts]\n", argv[0]);
        return 2;
    }
    const char *mode = argv[1];
    const char *inPath = argv[2];
    const char *outPath = argv[3];
    if (argc > 4 && strcmp(argv[4], "-") != 0) {
        int n = hex_decode(argv[4], g_cookie, sizeof(g_cookie));
        if (n < 0) {
            fprintf(stderr, "bad cookie hex\n");
            return 2;
        }
        g_cookieLen = (size_t)n;
    }
    if (argc > 5 && strcmp(argv[5], "strip") == 0)
        g_stripAdts = 1;

    uint32_t rate = 44100, channels = 2;

    if (strcmp(mode, "encode-stub") == 0)
        return test_encode_stub(rate);
    if (strcmp(mode, "nodata") == 0)
        return test_nodata();
    if (strcmp(mode, "eos") == 0)
        return test_eos();
    if (strcmp(mode, "bounds") == 0)
        return test_bounds(inPath, rate, channels);

    if (strcmp(mode, "passthrough") == 0) {
        Api api;
        void *h = dlopen("./libAudioConverter.so", RTLD_NOW | RTLD_LOCAL);
        if (!h)
            h = dlopen("libAudioConverter.so", RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            printf("RESULT status=FAIL reason=dlopen_lib %s\n", dlerror());
            return 1;
        }
        api.New = (FnNew)dlsym(h, "AudioConverterNew");
        api.Dispose = (FnDispose)dlsym(h, "AudioConverterDispose");
        api.Reset = (FnReset)dlsym(h, "AudioConverterReset");
        api.SetProperty = (FnSetProperty)dlsym(h, "AudioConverterSetProperty");
        api.Fill = (FnFill)dlsym(h, "AudioConverterFillComplexBuffer");
        return run_passthrough(inPath, outPath, &api, rate, channels);
    }

    /* Determine stream rate/channels from the first ADTS header. */
    uint8_t *f = NULL;
    size_t fl = 0;
    if (read_file(inPath, &f, &fl) != 0)
        return 1;
    int hl, frameLen;
    if (!adts_header(f, fl, &hl, &frameLen, (int *)&rate, (int *)&channels)) {
        fprintf(stderr, "input is not ADTS AAC\n");
        return 1;
    }
    free(f);
    printf("input: rate=%u ch=%u\n", rate, channels);

    if (strcmp(mode, "abi") == 0) {
        VendorFmt vin = {rate, FMT_AAC, 0, 0, 1024, 0, (UInt32)channels, 0, 0};
        VendorFmt vout = {rate, FMT_LPCM, FLAG_SIGNED_PACKED, 4, 1, 4,
                          (UInt32)channels, 16, 0};
        print_vendor("vendor-in", &vin);
        print_vendor("vendor-out", &vout);
        return 0;
    }

    Api api;
    void *h = NULL;
    if (strcmp(mode, "shim") == 0) {
        /* Real shim: it dlopens libAudioConverter.so and forwards. */
        h = dlopen("/system/lib64/libAirPlaySupport.so", RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            printf("RESULT status=FAIL reason=dlopen_shim %s\n", dlerror());
            return 1;
        }
        void *hAir = dlopen("/system/lib64/libAirPlay.so", RTLD_NOW | RTLD_LOCAL);
        if (!hAir) {
            printf("RESULT status=FAIL reason=dlopen_airplay %s\n", dlerror());
            return 1;
        }
        FnToASBD toAsbd = (FnToASBD)dlsym(hAir, "AirPlayAudioFormatToASBD");
        if (!toAsbd) {
            printf("RESULT status=FAIL reason=no_ToASBD\n");
            return 1;
        }
        uint64_t fmt = (rate == 48000) ? 0x800000ULL : 0x400000ULL;
        VendorFmt vin, vout;
        memset(&vin, 0, sizeof(vin));
        memset(&vout, 0, sizeof(vout));
        uint32_t extra = 0;
        int st1 = (int)toAsbd(fmt, &vin, &extra);
        int st2 = (int)toAsbd(fmt, &vout, &extra);
        printf("libAirPlay ToASBD(0x%llx) in=%d out=%d\n",
               (unsigned long long)fmt, st1, st2);
        print_vendor("shim-in ", &vin);
        print_vendor("shim-out", &vout);
        /* convert src ASBD to PCM via ToPCM (same local function) */
        FnToASBD toPcm = (FnToASBD)dlsym(hAir, "AirPlayAudioFormatToPCM");
        if (toPcm)
            toPcm(fmt, &vout, &extra);
        print_vendor("shim-pcm", &vout);
    } else {
        h = dlopen("./libAudioConverter.so", RTLD_NOW | RTLD_LOCAL);
        if (!h)
            h = dlopen("libAudioConverter.so", RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            printf("RESULT status=FAIL reason=dlopen_lib %s\n", dlerror());
            return 1;
        }
    }
    api.New = (FnNew)dlsym(h, "AudioConverterNew");
    api.Dispose = (FnDispose)dlsym(h, "AudioConverterDispose");
    api.Reset = (FnReset)dlsym(h, "AudioConverterReset");
    api.SetProperty = (FnSetProperty)dlsym(h, "AudioConverterSetProperty");
    api.Fill = (FnFill)dlsym(h, "AudioConverterFillComplexBuffer");
    if (!api.New || !api.Dispose || !api.Reset || !api.SetProperty || !api.Fill) {
        printf("RESULT status=FAIL reason=missing_symbols\n");
        return 1;
    }

    if (strcmp(mode, "live") == 0) {
        g_liveMode = 1;
        mode = "vendor";
    }

    if (strcmp(mode, "apple") == 0) {
        AppleASBD ain, aout;
        memset(&ain, 0, sizeof(ain));
        memset(&aout, 0, sizeof(aout));
        ain.mSampleRate = rate;
        ain.mFormatID = FMT_AAC;
        ain.mFramesPerPacket = 1024;
        ain.mChannelsPerFrame = (UInt32)channels;
        aout.mSampleRate = rate;
        aout.mFormatID = FMT_LPCM;
        aout.mFormatFlags = FLAG_SIGNED_PACKED;
        aout.mBytesPerPacket = 4;
        aout.mFramesPerPacket = 1;
        aout.mBytesPerFrame = 4;
        aout.mChannelsPerFrame = (UInt32)channels;
        aout.mBitsPerChannel = 16;
        return run_decode(mode, inPath, outPath, &api, &ain, &aout, rate, channels);
    }

    if (strcmp(mode, "vendor") == 0) {
        VendorFmt vin = {rate, FMT_AAC, 0, 0, 1024, 0, (UInt32)channels, 0, 0};
        VendorFmt vout = {rate, FMT_LPCM, FLAG_SIGNED_PACKED, 4, 1, 4,
                          (UInt32)channels, 16, 0};
        print_vendor("vendor-in ", &vin);
        print_vendor("vendor-out", &vout);
        return run_decode(mode, inPath, outPath, &api, &vin, &vout, rate, channels);
    }

    if (strcmp(mode, "shim") == 0) {
        /* vin/vout were filled inside the shim branch above. */
        static VendorFmt vin, vout;
        uint64_t fmt = (rate == 48000) ? 0x800000ULL : 0x400000ULL;
        void *hAir = dlopen("/system/lib64/libAirPlay.so", RTLD_NOW | RTLD_LOCAL);
        FnToASBD toAsbd = hAir ? (FnToASBD)dlsym(hAir, "AirPlayAudioFormatToASBD") : NULL;
        void *hShim = dlopen("/system/lib64/libAirPlaySupport.so", RTLD_NOW | RTLD_LOCAL);
        if (!toAsbd || !hShim) {
            printf("RESULT status=FAIL reason=shim_reload\n");
            return 1;
        }
        uint32_t extra = 0;
        toAsbd(fmt, &vin, &extra);
        toAsbd(fmt, &vout, &extra);
        FnToASBD toPcm = (FnToASBD)dlsym(hAir, "AirPlayAudioFormatToPCM");
        if (toPcm)
            toPcm(fmt, &vout, &extra);
        print_vendor("shim-in ", &vin);
        print_vendor("shim-pcm", &vout);
        return run_decode(mode, inPath, outPath, &api, &vin, &vout, rate, channels);
    }

    fprintf(stderr, "unknown mode %s\n", mode);
    return 2;
}
