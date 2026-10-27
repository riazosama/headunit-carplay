/*
 * libAudioConverter.so — replacement for the missing vendor CoreAudio
 * AudioConverter API used by libAirPlay.so (wireless CarPlay receiver).
 *
 * Exports exactly the five symbols libAirPlaySupport.so dlsyms:
 *   AudioConverterNew, AudioConverterDispose, AudioConverterReset,
 *   AudioConverterSetProperty, AudioConverterFillComplexBuffer
 *
 * AAC-LC decode is implemented on top of Android's NDK MediaCodec
 * (libmediandk).  LPCM->LPCM is a native conversion/passthrough.
 *
 * ABI note (verified against the real consumer, libAirPlay.so on the device):
 * libAirPlay passes a *36-byte* format descriptor (UInt32 sample rate at +0,
 * format ID at +4, ...), while the public Apple AudioStreamBasicDescription is
 * 40 bytes with a Float64 sample rate at +0 and the format ID at +8.  This
 * library auto-detects both layouts, so it also works with a standard Apple
 * ASBD (used by our host/unit tests and potentially other consumers).
 *
 * "No data right now" from the complex input proc is signalled with -6750
 * (kAudioConverterErr_NoMoreData); that is exactly what libAirPlay does and
 * what it expects to come back out of FillComplexBuffer.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>

#include <android/log.h>
#include <unistd.h>
#include <sys/types.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#define TAG "LibAudioConverter"

/*
 * IMPORTANT: this ROM's logd drops everything below ERROR for both shell/su
 * and untrusted_app processes (verified on the unit: only 'log -p e' lines are
 * readable via logcat).  The bridge app on this unit works around it the same
 * way.  All our diagnostics therefore go out at ERROR priority so the Lead can
 * collect them as in-session evidence; the message text carries the severity.
 * Build with -DLIBAC_LOG_PRIO=ANDROID_LOG_INFO to restore normal levels.
 */
#ifndef LIBAC_LOG_PRIO
#define LIBAC_LOG_PRIO ANDROID_LOG_ERROR
#endif
#define LOGV(...) __android_log_print(LIBAC_LOG_PRIO, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(LIBAC_LOG_PRIO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(LIBAC_LOG_PRIO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(LIBAC_LOG_PRIO, TAG, __VA_ARGS__)

/*
 * Unambiguous proof that dlopen("libAudioConverter.so") happened in the
 * process that hosts the AirPlay receiver.  ERROR priority because this ROM's
 * logd drops I/W from both untrusted_app and shell/su contexts.
 */
__attribute__((constructor)) static void libac_ctor(void) {
    __android_log_print(LIBAC_LOG_PRIO, TAG,
                        "SO LOADED pid=%d uid=%d tid=%d (libAudioConverter.so constructor)",
                        (int)getpid(), (int)getuid(), (int)gettid());
}

#if !defined(AMEDIAFORMAT_KEY_PCM_ENCODING)
#define AMEDIAFORMAT_KEY_PCM_ENCODING "pcm-encoding"
#endif
#if !defined(AMEDIAFORMAT_KEY_AAC_PROFILE)
#define AMEDIAFORMAT_KEY_AAC_PROFILE "aac-profile"
#endif

/* ------------------------------------------------------------------ */
/* CoreAudio types / constants                                         */
/* ------------------------------------------------------------------ */

typedef int32_t OSStatus;
typedef uint32_t UInt32;
typedef int64_t SInt64;
typedef double Float64;

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
        AudioConverterRef inAudioConverter,
        UInt32 *ioNumberDataPackets,
        AudioBufferList *ioData,
        AudioStreamPacketDescription **outDataPacketDescription,
        void *inUserData);

#define AC_NoErr 0
#define AC_ParamErr (-50)
#define AC_Err_FormatNotSupported 0x666d743f   /* 'fmt?' */
#define AC_Err_OperationNotSupported 0x6f703f3f /* 'op??' */
#define AC_Err_PropertyNotSupported 0x70726f70  /* 'prop' */
#define AC_Err_BadPropertySizeError 0x2173697a  /* '!siz' */
#define AC_Err_NoMoreData (-6750)               /* vendor/librAirPlay convention */

#define AC_Prop_DecompressionMagicCookie 0x646d6763 /* 'dmgc' */
#define AC_Prop_CompressionMagicCookie 0x636d6763   /* 'cmgc' */
#define AC_Prop_CurrentOutputStreamDesc 0x61636f64  /* 'acod' */
#define AC_Prop_CurrentInputStreamDesc 0x61636964   /* 'acid' */
#define AC_Prop_Pakl 0x70616b6c                     /* 'pakl' (vendor custom) */

#define FOURCC(a, b, c, d) \
    ((uint32_t)(uint8_t)(a) | ((uint32_t)(uint8_t)(b) << 8) | \
     ((uint32_t)(uint8_t)(c) << 16) | ((uint32_t)(uint8_t)(d) << 24))

/* FourCCs are stored as integer values ('aac ' -> 0x61616320). */
#define FMT_AAC 0x61616320u   /* 'aac ' */
#define FMT_AACE 0x61616365u  /* 'aace' (AAC-ELD) */
#define FMT_AACL 0x6161636cu  /* 'aacl' (AAC-LD) */
#define FMT_ADTS 0x61647473u  /* 'adts' */
#define FMT_LPCM 0x6c70636du /* 'lpcm' */
#define FMT_ALAC 0x616c6163u  /* 'alac' */
#define FMT_OPUS 0x6f707573u  /* 'opus' */
#define FMT_USOP 0x75736f70u  /* 'usop' (byte-reversed opus seen in vendor code) */
#define FMT_ELDA 0x656c6461u  /* 'elda' (defensive) */

#define FLAG_IS_FLOAT 0x1
#define FLAG_IS_BIG_ENDIAN 0x2
#define FLAG_IS_SIGNED_INT 0x4
#define FLAG_IS_PACKED 0x8
#define FLAG_IS_NON_INTERLEAVED 0x20

/* ------------------------------------------------------------------ */
/* Normalised format description (layout agnostic)                     */
/* ------------------------------------------------------------------ */

typedef struct {
    int valid;
    int vendorLayout; /* 1 = 36-byte vendor descriptor, 0 = Apple 40-byte */
    uint32_t sampleRate;
    uint32_t formatID;
    uint32_t formatFlags;
    uint32_t bytesPerPacket;
    uint32_t framesPerPacket;
    uint32_t bytesPerFrame;
    uint32_t channels;
    uint32_t bitsPerChannel;
} AcFormat;

/* ------------------------------------------------------------------ */
/* Converter instance                                                  */
/* ------------------------------------------------------------------ */

enum {
    MODE_NONE = 0,
    MODE_AAC_DECODE,
    MODE_PCM_CONVERT,
    MODE_ENCODE_UNSUPPORTED
};

typedef struct AudioConverterOpaque {
    pthread_mutex_t lock;
    AudioConverterRef self;
    AcFormat inFmt;
    AcFormat outFmt;
    int mode;

    /* output format (PCM) */
    uint32_t outChannels;
    uint32_t outBytesPerFrame; /* per frame, interleaved */
    uint32_t outBytesPerSample;
    int outIsFloat;
    int outBigEndian;
    int outIsInterleaved;

    /* ---- MediaCodec (decode) ---- */
    AMediaCodec *codec;
    AMediaFormat *codecFormat;
    int codecStarted;
    int codecError;
    int codecEosQueued;
    int codecEosSeen;
    uint32_t codecRate;
    uint32_t codecChannels;
    int codecPcmEncoding; /* 2 = s16, 4 = f32 */
    int loggedFmtChange;

    /* ---- compressed input ---- */
    AudioConverterComplexInputDataProc inputProc;
    void *inputUserData;
    uint8_t *pending;
    size_t pendingSize;
    size_t pendingCap;
    int havePending;
    int inputFinal;   /* pure EOS (proc returned 0 packets / noErr) */
    int inputError;   /* sticky callback error (not -6750) */
    int sawNoData;    /* callback returned -6750 while we had no PCM */
    int lastInputStatus;
    int64_t nextPtsUs;
    uint64_t packetsFed;
    uint64_t packetsDropped;

    /* magic cookie (AudioSpecificConfig) */
    uint8_t cookie[80];
    size_t cookieSize;

    /* ---- decoded PCM FIFO (already in the requested output format) ---- */
    uint8_t *fifo;
    size_t fifoLen;
    size_t fifoCap;

    /* scratch for format conversion / resampling */
    float *scratch;      /* frames * max(srcCh, outCh) */
    size_t scratchCap;   /* in floats */

    /* linear resampler state */
    int rsActive;
    double rsStep;       /* src frames per dst frame */
    double rsBase;       /* absolute src frame index of current chunk start */
    double rsNextT;      /* absolute src frame time of next dst frame */
    float rsPrev[16];    /* last src frame, per source channel */
    int rsHavePrev;

    /* logging */
    int loggedUnknownProp;
    int calls;
} Converter;

/* ------------------------------------------------------------------ */
/* Small helpers                                                       */
/* ------------------------------------------------------------------ */

static int is_known_fourcc(uint32_t v) {
    switch (v) {
        case FMT_AAC: case FMT_AACE: case FMT_AACL: case FMT_ADTS:
        case FMT_LPCM: case FMT_ALAC: case FMT_OPUS: case FMT_USOP:
        case FMT_ELDA:
            return 1;
        default:
            return 0;
    }
}

static int sane_rate(uint32_t r) {
    return r >= 1000 && r <= 768000;
}

/* Apple 40-byte AudioStreamBasicDescription. */
typedef struct {
    Float64 mSampleRate;
    UInt32 mFormatID;
    UInt32 mFormatFlags;
    UInt32 mBytesPerPacket;
    UInt32 mFramesPerPacket;
    UInt32 mBytesPerFrame;
    UInt32 mChannelsPerFrame;
    UInt32 mBitsPerChannel;
    UInt32 mReserved;
} AppleASBD;

/* Vendor 36-byte descriptor (what the real libAirPlay.so passes). */
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
} VendorFormat;

#include <sys/system_properties.h>

/* Force AAC-LC decode even if the input format ID is odd (e2e escape hatch). */
static int force_aac_enabled(void) {
    static int cached = -1;
    if (cached >= 0)
        return cached;
    cached = 0;
    const char *e = getenv("LIBAC_FORCE_AAC");
    if (e && e[0] && strcmp(e, "0") != 0)
        cached = 1;
    if (!cached) {
        char v[92];
        v[0] = 0;
        if (__system_property_get("debug.libac.force_aac", v) > 0 && v[0] &&
            strcmp(v, "0") != 0)
            cached = 1;
    }
    return cached;
}

static void log_raw_format(const char *what, const void *ptr) {
    if (!ptr)
        return;
    const uint8_t *b = (const uint8_t *)ptr;
    LOGI("%s raw[0..35]: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x "
         "%02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x "
         "%02x%02x%02x%02x %02x%02x%02x%02x",
         what, b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9],
         b[10], b[11], b[12], b[13], b[14], b[15], b[16], b[17], b[18], b[19],
         b[20], b[21], b[22], b[23], b[24], b[25], b[26], b[27], b[28], b[29],
         b[30], b[31], b[32], b[33], b[34], b[35]);
}

/*
 * Parse a format descriptor.  Reads at most 36 bytes from the pointer, which
 * is safe for both layouts and never overruns a 36-byte vendor descriptor.
 */
static int parse_format(const void *ptr, AcFormat *out) {
    if (!ptr || !out)
        return 0;
    uint8_t raw[36];
    memcpy(raw, ptr, sizeof(raw));
    uint32_t w0, w4, w8, wc, w10, w14, w18, w1c, w20;
    memcpy(&w0, raw + 0x00, 4);
    memcpy(&w4, raw + 0x04, 4);
    memcpy(&w8, raw + 0x08, 4);
    memcpy(&wc, raw + 0x0c, 4);
    memcpy(&w10, raw + 0x10, 4);
    memcpy(&w14, raw + 0x14, 4);
    memcpy(&w18, raw + 0x18, 4);
    memcpy(&w1c, raw + 0x1c, 4);
    memcpy(&w20, raw + 0x20, 4);

    memset(out, 0, sizeof(*out));
    if (is_known_fourcc(w4) && sane_rate(w0)) {
        /* Vendor 36-byte layout. */
        out->valid = 1;
        out->vendorLayout = 1;
        out->sampleRate = w0;
        out->formatID = w4;
        out->formatFlags = w8;
        out->bytesPerPacket = wc;
        out->framesPerPacket = w10;
        out->bytesPerFrame = w14;
        out->channels = w18;
        out->bitsPerChannel = w1c;
    } else if (is_known_fourcc(w8)) {
        /* Standard Apple layout: Float64 rate at +0, format ID at +8. */
        AppleASBD a;
        memcpy(&a, ptr, sizeof(a));
        out->valid = 1;
        out->vendorLayout = 0;
        out->sampleRate = (uint32_t)(a.mSampleRate + 0.5);
        out->formatID = a.mFormatID;
        out->formatFlags = a.mFormatFlags;
        out->bytesPerPacket = a.mBytesPerPacket;
        out->framesPerPacket = a.mFramesPerPacket;
        out->bytesPerFrame = a.mBytesPerFrame;
        out->channels = a.mChannelsPerFrame;
        out->bitsPerChannel = a.mBitsPerChannel;
    } else if ((w8 == 0 || w8 <= 0x100) && (w0 > 0x10000)) {
        /* Unknown fourcc but looks like the vendor layout (rate + flags). */
        out->valid = 1;
        out->vendorLayout = 1;
        out->sampleRate = sane_rate(w0) ? w0 : 44100;
        out->formatID = w4;
        out->formatFlags = w8;
        out->bytesPerPacket = wc;
        out->framesPerPacket = w10;
        out->bytesPerFrame = w14;
        out->channels = w18 ? w18 : 2;
        out->bitsPerChannel = w1c;
    } else {
        return 0;
    }

    if (out->channels == 0 || out->channels > 8) {
        LOGW("format: implausible channel count %u, defaulting to 2", out->channels);
        out->channels = 2;
    }
    if (!sane_rate(out->sampleRate)) {
        LOGW("format: implausible sample rate %u, defaulting to 44100", out->sampleRate);
        out->sampleRate = 44100;
    }
    if (out->bitsPerChannel == 0 && (out->formatID == FMT_LPCM)) {
        out->bitsPerChannel = 16;
    }
    return 1;
}

static void format_to_string(const AcFormat *f, char *buf, size_t n) {
    uint32_t id = f->formatID;
    char cc[5] = {(char)((id >> 24) & 0xff), (char)((id >> 16) & 0xff),
                  (char)((id >> 8) & 0xff), (char)(id & 0xff), 0};
    snprintf(buf, n, "%s rate=%u ch=%u bits=%u flags=0x%x bpp=%u fpp=%u bpf=%u",
             cc, f->sampleRate, f->channels, f->bitsPerChannel, f->formatFlags,
             f->bytesPerPacket, f->framesPerPacket, f->bytesPerFrame);
}

/* ------------------------------------------------------------------ */
/* FIFO (bytes, already in output format)                              */
/* ------------------------------------------------------------------ */

static int fifo_reserve(Converter *c, size_t extra) {
    size_t need = c->fifoLen + extra;
    if (need <= c->fifoCap)
        return 0;
    size_t cap = c->fifoCap ? c->fifoCap : 16384;
    while (cap < need)
        cap *= 2;
    uint8_t *p = (uint8_t *)realloc(c->fifo, cap);
    if (!p) {
        LOGE("OOM growing PCM FIFO to %zu bytes", cap);
        return -1;
    }
    c->fifo = p;
    c->fifoCap = cap;
    return 0;
}

static int fifo_append(Converter *c, const void *data, size_t len) {
    if (len == 0)
        return 0;
    if (fifo_reserve(c, len) != 0)
        return -1;
    memcpy(c->fifo + c->fifoLen, data, len);
    c->fifoLen += len;
    return 0;
}

static void fifo_consume(Converter *c, size_t len) {
    if (len >= c->fifoLen) {
        c->fifoLen = 0;
        return;
    }
    memmove(c->fifo, c->fifo + len, c->fifoLen - len);
    c->fifoLen -= len;
}

/* ------------------------------------------------------------------ */
/* Output sample writing                                               */
/* ------------------------------------------------------------------ */

/* Portable rounding without libm surprises. */
static inline long ac_lrintf(float v) {
    return (long)(v >= 0.0f ? (v + 0.5f) : (v - 0.5f));
}

static inline int16_t float_to_s16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return (int16_t)ac_lrintf(v * 32767.0f);
}

/* Write one frame (outChannels samples in float) to dst in output format. */
static void write_frame(Converter *c, float *frame, uint8_t *dst) {
    for (uint32_t ch = 0; ch < c->outChannels; ch++) {
        float v = frame[ch];
        if (c->outIsFloat) {
            uint32_t bits;
            memcpy(&bits, &v, 4);
            if (c->outBigEndian)
                bits = __builtin_bswap32(bits);
            memcpy(dst + ch * 4, &bits, 4);
        } else if (c->outBytesPerSample == 2) {
            int16_t s = float_to_s16(v);
            if (c->outBigEndian) {
                uint8_t b[2] = {(uint8_t)((uint16_t)s >> 8), (uint8_t)(s & 0xff)};
                memcpy(dst + ch * 2, b, 2);
            } else {
                memcpy(dst + ch * 2, &s, 2);
            }
        } else if (c->outBytesPerSample == 4) {
            int32_t s = (int32_t)ac_lrintf(v * 2147483647.0f);
            if (c->outBigEndian)
                s = (int32_t)__builtin_bswap32((uint32_t)s);
            memcpy(dst + ch * 4, &s, 4);
        }
    }
}

/* ------------------------------------------------------------------ */
/* Raw -> float decoding of a source PCM chunk                         */
/* ------------------------------------------------------------------ */

/* Convert bytesPerSample interleaved samples of src into float scratch. */
static int parse_src_pcm(Converter *c, const uint8_t *src, size_t bytes,
                         uint32_t srcCh, uint32_t srcBytesPerSample, int srcFloat,
                         int srcBigEndian, float **outFrames, uint32_t *outFramesCount) {
    size_t frameBytes = (size_t)srcCh * srcBytesPerSample;
    if (frameBytes == 0)
        return -1;
    uint32_t frames = (uint32_t)(bytes / frameBytes);
    size_t need = (size_t)frames * srcCh;
    if (need > c->scratchCap) {
        float *p = (float *)realloc(c->scratch, need * sizeof(float));
        if (!p) {
            LOGE("OOM scratch %zu", need * sizeof(float));
            return -1;
        }
        c->scratch = p;
        c->scratchCap = need;
    }
    float *dst = c->scratch;
    for (uint32_t i = 0; i < frames; i++) {
        const uint8_t *p = src + (size_t)i * frameBytes;
        for (uint32_t ch = 0; ch < srcCh; ch++) {
            const uint8_t *q = p + (size_t)ch * srcBytesPerSample;
            float v = 0.0f;
            if (srcFloat) {
                uint32_t bits;
                memcpy(&bits, q, 4);
                if (srcBigEndian)
                    bits = __builtin_bswap32(bits);
                memcpy(&v, &bits, 4);
            } else if (srcBytesPerSample == 2) {
                int16_t s;
                if (srcBigEndian) {
                    uint16_t u = (uint16_t)q[0] << 8 | q[1];
                    s = (int16_t)u;
                } else {
                    memcpy(&s, q, 2);
                }
                v = (float)s / 32768.0f;
            } else if (srcBytesPerSample == 4) {
                int32_t s;
                memcpy(&s, q, 4);
                if (srcBigEndian)
                    s = (int32_t)__builtin_bswap32((uint32_t)s);
                v = (float)(s / 2147483648.0);
            } else if (srcBytesPerSample == 1) {
                v = ((float)q[0] - 128.0f) / 128.0f;
            }
            dst[(size_t)i * srcCh + ch] = v;
        }
    }
    *outFrames = dst;
    *outFramesCount = frames;
    return 0;
}

/* Channel-map one frame from srcCh floats to outCh floats. */
static void map_channels(Converter *c, const float *src, uint32_t srcCh, float *dst) {
    uint32_t och = c->outChannels;
    if (srcCh == och) {
        for (uint32_t ch = 0; ch < och; ch++)
            dst[ch] = src[ch];
        return;
    }
    if (srcCh == 1) {
        for (uint32_t ch = 0; ch < och; ch++)
            dst[ch] = src[0];
        return;
    }
    if (och == 1) {
        float acc = 0.0f;
        for (uint32_t ch = 0; ch < srcCh; ch++)
            acc += src[ch];
        dst[0] = acc / (float)srcCh;
        return;
    }
    for (uint32_t ch = 0; ch < och; ch++)
        dst[ch] = src[ch < srcCh ? ch : (srcCh - 1)];
}

/*
 * Append a chunk of decoded source PCM (any rate/channel/format we support)
 * to the FIFO, converted to the converter's output format.
 */
static int append_pcm_chunk(Converter *c, const uint8_t *src, size_t bytes,
                            uint32_t srcRate, uint32_t srcCh,
                            uint32_t srcBytesPerSample, int srcFloat, int srcBigEndian) {
    int resampleNeeded = (srcRate != c->outFmt.sampleRate);
    /* Fast path: identical PCM layout -> bit-exact copy (keeps passthrough and
     * the common MediaCodec s16LE->s16LE path lossless). */
    if (!resampleNeeded && srcCh == c->outChannels && !c->outIsFloat && !srcFloat &&
        srcBytesPerSample == c->outBytesPerSample &&
        srcBigEndian == c->outBigEndian &&
        c->outBytesPerFrame == (uint32_t)(srcCh * srcBytesPerSample)) {
        return fifo_append(c, src, bytes);
    }
    float *frames = NULL;
    uint32_t nFrames = 0;
    if (parse_src_pcm(c, src, bytes, srcCh, srcBytesPerSample, srcFloat, srcBigEndian,
                      &frames, &nFrames) != 0)
        return -1;
    if (nFrames == 0)
        return 0;

    /* Number of output frames produced by this chunk. */
    uint32_t outFrames;
    int resample = resampleNeeded;
    if (!resample) {
        outFrames = nFrames;
    } else {
        double step = (double)srcRate / (double)c->outFmt.sampleRate;
        if (!c->rsActive) {
            c->rsActive = 1;
            c->rsStep = step;
            c->rsBase = 0.0;
            c->rsNextT = 0.0;
            c->rsHavePrev = 0;
        }
        double t = c->rsNextT;
        double base = c->rsBase;
        /* last output time that can be interpolated inside this chunk */
        double tmax = base + (double)nFrames - 1.0;
        outFrames = 0;
        while (t <= tmax)
            outFrames++;
        if (outFrames == 0)
            outFrames = 1; /* keep continuity even for very small chunks */
    }

    size_t outBytes = (size_t)outFrames * c->outBytesPerFrame;
    if (fifo_reserve(c, outBytes) != 0)
        return -1;

    float *dst = (float *)c->scratch; /* reuse? scratch holds source! */
    (void)dst;
    /* Use a dedicated stack-ish scratch: allocate per chunk (chunks are small). */
    float *mapped = (float *)malloc((size_t)c->outChannels * sizeof(float));
    if (!mapped)
        return -1;

    uint8_t *fifoBase = c->fifo + c->fifoLen;
    if (!resample) {
        for (uint32_t i = 0; i < nFrames; i++) {
            map_channels(c, frames + (size_t)i * srcCh, srcCh, mapped);
            write_frame(c, mapped, fifoBase + (size_t)i * c->outBytesPerFrame);
        }
    } else {
        double step = c->rsStep;
        double t = c->rsNextT;
        double base = c->rsBase;
        uint32_t oi = 0;
        while (oi < outFrames) {
            long fi = (long)t - (long)base; /* index into current chunk */
            double f = t - (double)((long)t);
            float s0[16], s1[16];
            for (uint32_t ch = 0; ch < srcCh && ch < 16; ch++) {
                if (fi < 0) {
                    s0[ch] = (c->rsHavePrev && (int)ch < 16) ? c->rsPrev[ch]
                                                             : frames[ch];
                } else if ((uint32_t)fi < nFrames) {
                    s0[ch] = frames[(size_t)fi * srcCh + ch];
                } else {
                    s0[ch] = frames[(size_t)(nFrames - 1) * srcCh + ch];
                }
                long fj = fi + 1;
                if (fj < 0)
                    s1[ch] = s0[ch];
                else if ((uint32_t)fj < nFrames)
                    s1[ch] = frames[(size_t)fj * srcCh + ch];
                else
                    s1[ch] = frames[(size_t)(nFrames - 1) * srcCh + ch];
            }
            float interp[16];
            for (uint32_t ch = 0; ch < srcCh && ch < 16; ch++)
                interp[ch] = (float)(s0[ch] + (s1[ch] - s0[ch]) * f);
            map_channels(c, interp, srcCh, mapped);
            write_frame(c, mapped, fifoBase + (size_t)oi * c->outBytesPerFrame);
            oi++;
            t += step;
        }
        /* remember last input frame + position for next chunk */
        for (uint32_t ch = 0; ch < srcCh && ch < 16; ch++)
            c->rsPrev[ch] = frames[(size_t)(nFrames - 1) * srcCh + ch];
        c->rsHavePrev = 1;
        c->rsBase = base + (double)nFrames;
        c->rsNextT = t;
    }
    free(mapped);
    c->fifoLen += outBytes;
    return 0;
}

/* ------------------------------------------------------------------ */
/* AAC helpers                                                         */
/* ------------------------------------------------------------------ */

static const int kAacFreqTable[16] = {
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
    16000, 12000, 11025, 8000, 7350, 0, 0, 0};

static int aac_freq_index(uint32_t rate) {
    for (int i = 0; i < 13; i++)
        if ((uint32_t)kAacFreqTable[i] == rate)
            return i;
    /* closest */
    int best = 4, bestd = 1 << 30;
    for (int i = 0; i < 13; i++) {
        int d = abs(kAacFreqTable[i] - (int)rate);
        if (d < bestd) {
            bestd = d;
            best = i;
        }
    }
    return best;
}

/* Synthesize an AudioSpecificConfig for AAC-LC / ELD from the input format. */
static size_t synth_cookie(const AcFormat *in, uint8_t *out, size_t cap) {
    if (cap < 2)
        return 0;
    int aot = 2; /* AAC-LC */
    if (in->formatID == FMT_AACE || in->formatID == FMT_ELDA)
        aot = 39; /* ELD */
    else if (in->formatID == FMT_AACL)
        aot = 23; /* LD */
    int fi = aac_freq_index(in->sampleRate);
    int ch = (int)in->channels;
    if (ch < 1)
        ch = 2;
    if (ch > 7)
        ch = 7;
    if (aot < 32) {
        uint32_t v = ((uint32_t)aot << 11) | ((uint32_t)fi << 7) | ((uint32_t)ch << 3);
        out[0] = (uint8_t)(v >> 8);
        out[1] = (uint8_t)(v & 0xff);
        return 2;
    }
    /* Escape-coded object type (5 bits of 31 + 6 bits AOT-32) -> 3 bytes. */
    if (cap < 3)
        return 0;
    uint32_t a = (uint32_t)aot - 32u;
    uint32_t v = (31u << 19) | (a << 13) | ((uint32_t)fi << 9) | ((uint32_t)ch << 5);
    out[0] = (uint8_t)(v >> 16);
    out[1] = (uint8_t)(v >> 8);
    out[2] = (uint8_t)v;
    return 3;
}

/* If the packet is ADTS, return header length and fill info (0 = not ADTS). */
static int adts_parse(const uint8_t *p, size_t n, int *hdrLen, int *aot,
                      int *freqIdx, int *chanCfg, int *frameLen) {
    if (n < 7 || p[0] != 0xFF || (p[1] & 0xF6) != 0xF0)
        return 0;
    int protAbsent = p[1] & 0x01;
    int hl = 7 + (protAbsent ? 0 : 2);
    if (n < (size_t)hl)
        return 0;
    int profile = (p[2] >> 6) & 3;
    *aot = profile + 1;
    *freqIdx = (p[2] >> 2) & 0x0F;
    *chanCfg = ((p[2] & 0x01) << 2) | ((p[3] >> 6) & 0x03);
    *frameLen = ((p[3] & 0x03) << 11) | ((size_t)p[4] << 3) | ((p[5] >> 5) & 0x07);
    *hdrLen = hl;
    return 1;
}

static int codec_create_decoder(Converter *c) {
    AMediaFormat *fmt = AMediaFormat_new();
    if (!fmt)
        return -1;
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, (int32_t)c->codecRate);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, (int32_t)c->codecChannels);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 65536);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_PCM_ENCODING, 2 /* 16-bit */);

    uint8_t asc[8];
    size_t ascLen = 0;
    if (c->cookieSize > 0) {
        ascLen = c->cookieSize;
        if (ascLen > sizeof(asc))
            ascLen = sizeof(asc);
        memcpy(asc, c->cookie, ascLen);
    } else {
        ascLen = synth_cookie(&c->inFmt, asc, sizeof(asc));
    }
    if (ascLen > 0)
        AMediaFormat_setBuffer(fmt, AMEDIAFORMAT_KEY_CSD_0, asc, ascLen);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_AAC_PROFILE, 2 /* AACObjectLC */);

    AMediaCodec *codec = AMediaCodec_createDecoderByType("audio/mp4a-latm");
    if (!codec) {
        LOGE("AMediaCodec_createDecoderByType(aac) failed");
        AMediaFormat_delete(fmt);
        return -1;
    }
    media_status_t st = AMediaCodec_configure(codec, fmt, NULL, NULL, 0);
    if (st != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", (int)st);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(fmt);
        return -1;
    }
    st = AMediaCodec_start(codec);
    if (st != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", (int)st);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(fmt);
        return -1;
    }
    c->codec = codec;
    c->codecFormat = fmt;
    c->codecStarted = 1;
    c->codecEosQueued = 0;
    c->codecEosSeen = 0;
    LOGI("AAC decoder started: %u Hz %u ch, cookie=%zu bytes", c->codecRate,
         c->codecChannels, ascLen);
    return 0;
}

/* ------------------------------------------------------------------ */
/* Complex input pulling / codec feeding / draining                    */
/* ------------------------------------------------------------------ */

static void log_once_input_error(Converter *c, OSStatus st) {
    if (c->inputError == st)
        return;
    LOGW("input proc error %d", (int)st);
}

/* Ask the caller's complex input proc for one compressed packet. */
static int pull_packet(Converter *c) {
    if (c->havePending)
        return 1;
    if (c->inputFinal || c->inputError)
        return 0;

    UInt32 np = 1;
    AudioBufferList abl;
    memset(&abl, 0, sizeof(abl));
    abl.mNumberBuffers = 1;
    AudioStreamPacketDescription *pd = NULL;
    OSStatus st = c->inputProc(c->self, &np, &abl, &pd, c->inputUserData);
    c->lastInputStatus = st;

    if (st != AC_NoErr) {
        c->sawNoData = 1;
        if (st == AC_Err_NoMoreData)
            return 0; /* normal "no data right now"; caller may come back */
        c->inputError = st;
        log_once_input_error(c, st);
        return -1;
    }
    if (np == 0 || abl.mNumberBuffers == 0 || abl.mBuffers[0].mData == NULL ||
        abl.mBuffers[0].mDataByteSize == 0) {
        c->inputFinal = 1;
        LOGI("input proc: end of stream");
        return 0;
    }

    const uint8_t *data = (const uint8_t *)abl.mBuffers[0].mData;
    size_t len = abl.mBuffers[0].mDataByteSize;
    if (pd && pd->mDataByteSize > 0 && pd->mDataByteSize < len)
        len = pd->mDataByteSize;

    int hdrLen = 0, aot = 0, fi = 0, ch = 0, fl = 0;
    if (adts_parse(data, len, &hdrLen, &aot, &fi, &ch, &fl)) {
        if (c->cookieSize == 0 && fi >= 0 && fi < 13) {
            /* derive AudioSpecificConfig from the ADTS header */
            uint32_t v = ((uint32_t)(aot & 0x1f) << 11) |
                         ((uint32_t)fi << 7) | ((uint32_t)(ch & 0xf) << 3);
            c->cookie[0] = (uint8_t)(v >> 8);
            c->cookie[1] = (uint8_t)(v & 0xff);
            c->cookieSize = 2;
        }
        data += hdrLen;
        len -= (size_t)hdrLen;
    }

    if (len == 0)
        return 0;
    if (len > c->pendingCap) {
        uint8_t *p = (uint8_t *)realloc(c->pending, len);
        if (!p) {
            LOGE("OOM pending packet %zu", len);
            return -1;
        }
        c->pending = p;
        c->pendingCap = len;
    }
    memcpy(c->pending, data, len);
    c->pendingSize = len;
    c->havePending = 1;
    return 1;
}

/* Try to push the pending compressed packet into MediaCodec. */
static int feed_pending(Converter *c) {
    if (!c->havePending)
        return 0;
    if (!c->codecStarted)
        return -1;

    ssize_t ibi = AMediaCodec_dequeueInputBuffer(c->codec, 0);
    if (ibi == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
        return 0;
    if (ibi < 0) {
        LOGW("dequeueInputBuffer unexpected %zd", ibi);
        return 0;
    }
    size_t cap = 0;
    uint8_t *buf = AMediaCodec_getInputBuffer(c->codec, (size_t)ibi, &cap);
    if (!buf) {
        LOGE("getInputBuffer failed");
        return -1;
    }
    if (cap < c->pendingSize) {
        LOGE("input buffer too small (%zu < %zu), dropping packet", cap, c->pendingSize);
        AMediaCodec_queueInputBuffer(c->codec, (size_t)ibi, 0, 0, c->nextPtsUs, 0);
        c->packetsDropped++;
        c->havePending = 0;
        c->pendingSize = 0;
        return -1;
    }
    memcpy(buf, c->pending, c->pendingSize);
    uint32_t fpp = c->inFmt.framesPerPacket ? c->inFmt.framesPerPacket : 1024;
    media_status_t st = AMediaCodec_queueInputBuffer(c->codec, (size_t)ibi, 0,
                                                     c->pendingSize, c->nextPtsUs, 0);
    if (st != AMEDIA_OK) {
        LOGE("queueInputBuffer failed: %d", (int)st);
        c->havePending = 0;
        return -1;
    }
    c->nextPtsUs += (int64_t)fpp * 1000000LL / (int64_t)(c->codecRate ? c->codecRate : 44100);
    c->havePending = 0;
    c->pendingSize = 0;
    c->packetsFed++;
    return 1;
}

static void read_output_format(Converter *c) {
    AMediaFormat *of = AMediaCodec_getOutputFormat(c->codec);
    if (!of)
        return;
    int32_t v = 0;
    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_SAMPLE_RATE, &v) && v > 0)
        c->codecRate = (uint32_t)v;
    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0)
        c->codecChannels = (uint32_t)v;
    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_PCM_ENCODING, &v) && v > 0)
        c->codecPcmEncoding = v;
    if (!c->loggedFmtChange) {
        LOGI("decoder output format: %u Hz %u ch pcm-encoding=%d", c->codecRate,
             c->codecChannels, c->codecPcmEncoding);
        c->loggedFmtChange = 1;
    }
    AMediaFormat_delete(of);
}

static int drain_codec(Converter *c, int64_t timeoutUs) {
    if (!c->codecStarted)
        return 0;
    int total = 0;
    int64_t timeout = timeoutUs;
    for (;;) {
        AMediaCodecBufferInfo info;
        memset(&info, 0, sizeof(info));
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(c->codec, &info, timeout);
        timeout = 0;
        if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
        if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            read_output_format(c);
            continue;
        }
        if (idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
            continue;
        if (idx < 0) {
            if (!c->codecError)
                LOGE("dequeueOutputBuffer error: %zd", idx);
            c->codecError = 1;
            break;
        }
        size_t cap = 0;
        uint8_t *buf = AMediaCodec_getOutputBuffer(c->codec, (size_t)idx, &cap);
        if (buf && info.size > 0 && (size_t)info.offset + (size_t)info.size <= cap) {
            int enc = c->codecPcmEncoding;
            uint32_t bps = (enc == 4) ? 4 : 2;
            int isFloat = (enc == 4);
            if (append_pcm_chunk(c, buf + info.offset, (size_t)info.size,
                                 c->codecRate, c->codecChannels, bps, isFloat, 0) == 0)
                total += info.size;
        }
        int eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
        AMediaCodec_releaseOutputBuffer(c->codec, (size_t)idx, false);
        if (eos) {
            c->codecEosSeen = 1;
            LOGI("decoder reached end of stream");
            break;
        }
    }
    return total;
}

static void queue_eos(Converter *c) {
    if (c->codecEosQueued || !c->codecStarted)
        return;
    for (int tries = 0; tries < 20; tries++) {
        ssize_t ibi = AMediaCodec_dequeueInputBuffer(c->codec, 1000);
        if (ibi >= 0) {
            AMediaCodec_queueInputBuffer(c->codec, (size_t)ibi, 0, 0, c->nextPtsUs,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            c->codecEosQueued = 1;
            return;
        }
    }
    c->codecEosQueued = 1; /* give up; final drain will time out */
}

/* ------------------------------------------------------------------ */
/* Fill loop                                                           */
/* ------------------------------------------------------------------ */

static UInt32 output_bytes_per_frame(const Converter *c) {
    return c->outBytesPerFrame;
}

static OSStatus fill_decode(Converter *c, UInt32 wantFrames) {
    size_t wantBytes = (size_t)wantFrames * output_bytes_per_frame(c);
    int idleIters = 0;
    int stopPulling = 0;

    for (int iter = 0; iter < 32; iter++) {
        if (c->fifoLen >= wantBytes)
            break;
        if (c->codecError)
            break;
        int progress = 0;

        if (c->havePending) {
            int r = feed_pending(c);
            if (r != 0)
                progress = 1;
        } else if (!c->inputFinal && !c->inputError && !stopPulling) {
            int r = pull_packet(c);
            if (r > 0) {
                progress = 1;
                if (feed_pending(c) != 0)
                    progress = 1;
            } else if (r < 0) {
                progress = 0;
            } else if (c->lastInputStatus == AC_Err_NoMoreData) {
                /* Contract (AUDIO_CONVERTER_ABI.md §8.5): do not call the input
                 * proc again after -6750 in the same Fill call. */
                stopPulling = 1;
            }
        }

        int drained = drain_codec(c, progress ? 800 : 3000);
        if (drained > 0)
            progress = 1;

        if (c->fifoLen >= wantBytes)
            break;

        if (!progress) {
            if (c->inputFinal && !c->codecEosSeen) {
                queue_eos(c);
                if (drain_codec(c, 10000) > 0)
                    continue;
            }
            if (++idleIters >= 2)
                break;
        } else {
            idleIters = 0;
        }
    }
    return AC_NoErr;
}

static OSStatus fill_pcm_passthrough(Converter *c, UInt32 wantFrames) {
    size_t wantBytes = (size_t)wantFrames * output_bytes_per_frame(c);
    for (int iter = 0; iter < 32 && c->fifoLen < wantBytes; iter++) {
        UInt32 np = wantFrames;
        AudioBufferList abl;
        memset(&abl, 0, sizeof(abl));
        abl.mNumberBuffers = 1;
        AudioStreamPacketDescription *pd = NULL;
        OSStatus st = c->inputProc(c->self, &np, &abl, &pd, c->inputUserData);
        c->lastInputStatus = st;
        if (st != AC_NoErr) {
            c->sawNoData = 1;
            if (st != AC_Err_NoMoreData)
                c->inputError = st;
            break;
        }
        if (np == 0 || abl.mNumberBuffers == 0 || !abl.mBuffers[0].mData ||
            abl.mBuffers[0].mDataByteSize == 0) {
            c->inputFinal = 1;
            break;
        }
        uint32_t srcBps = c->inFmt.bitsPerChannel ? (c->inFmt.bitsPerChannel + 7) / 8 : 2;
        if (c->inFmt.bytesPerFrame && c->inFmt.channels)
            srcBps = c->inFmt.bytesPerFrame / c->inFmt.channels;
        append_pcm_chunk(c, (const uint8_t *)abl.mBuffers[0].mData,
                         abl.mBuffers[0].mDataByteSize, c->inFmt.sampleRate,
                         c->inFmt.channels, srcBps,
                         (c->inFmt.formatFlags & FLAG_IS_FLOAT) ? 1 : 0,
                         (c->inFmt.formatFlags & FLAG_IS_BIG_ENDIAN) ? 1 : 0);
        if (np < wantFrames)
            break;
    }
    return AC_NoErr;
}

/* ------------------------------------------------------------------ */
/* Exported API                                                        */
/* ------------------------------------------------------------------ */

__attribute__((visibility("default")))
OSStatus AudioConverterNew(const void *inFormat, const void *outFormat,
                           AudioConverterRef *outConverter) {
    if (!inFormat || !outFormat || !outConverter)
        return AC_ParamErr;
    *outConverter = NULL;

    Converter *c = (Converter *)calloc(1, sizeof(Converter));
    if (!c) {
        LOGE("OOM allocating converter");
        return -6728; /* memFullErr-ish, matches shim's calloc failure code */
    }
    pthread_mutex_init(&c->lock, NULL);
    c->self = (AudioConverterRef)c;
    c->lastInputStatus = AC_NoErr;
    c->codecPcmEncoding = 2;
    c->nextPtsUs = 0;

    log_raw_format("in ", inFormat);
    log_raw_format("out", outFormat);
    int inOk = parse_format(inFormat, &c->inFmt);
    int outOk = parse_format(outFormat, &c->outFmt);
    if (!outOk) {
        LOGE("AudioConverterNew: cannot parse output format");
        pthread_mutex_destroy(&c->lock);
        free(c);
        return AC_ParamErr;
    }
    if (!inOk) {
        if (c->outFmt.formatID == FMT_LPCM && force_aac_enabled()) {
            memset(&c->inFmt, 0, sizeof(c->inFmt));
            c->inFmt.valid = 1;
            c->inFmt.vendorLayout = -1;
            c->inFmt.sampleRate = c->outFmt.sampleRate;
            c->inFmt.formatID = FMT_AAC;
            c->inFmt.framesPerPacket = 1024;
            c->inFmt.channels = c->outFmt.channels;
            LOGW("force-AAC: unparseable input format, assuming AAC-LC %u Hz %u ch",
                 c->inFmt.sampleRate, c->inFmt.channels);
        } else {
            char a[128], b[128];
            format_to_string(&c->inFmt, a, sizeof(a));
            format_to_string(&c->outFmt, b, sizeof(b));
            LOGE("AudioConverterNew: cannot parse input format (in: %s | out: %s)", a, b);
            pthread_mutex_destroy(&c->lock);
            free(c);
            return AC_ParamErr;
        }
    }
    if (force_aac_enabled() && c->outFmt.formatID == FMT_LPCM) {
        switch (c->inFmt.formatID) {
        case FMT_AAC: case FMT_ADTS: case FMT_AACE: case FMT_AACL: case FMT_ELDA:
            break;
        default:
            LOGW("force-AAC: overriding input format 0x%08x -> 'aac '", c->inFmt.formatID);
            c->inFmt.formatID = FMT_AAC;
            if (c->inFmt.framesPerPacket == 0)
                c->inFmt.framesPerPacket = 1024;
            break;
        }
    }

    c->outChannels = c->outFmt.channels;
    c->outIsFloat = (c->outFmt.formatFlags & FLAG_IS_FLOAT) ? 1 : 0;
    c->outBigEndian = (c->outFmt.formatFlags & FLAG_IS_BIG_ENDIAN) ? 1 : 0;
    c->outIsInterleaved = (c->outFmt.formatFlags & FLAG_IS_NON_INTERLEAVED) ? 0 : 1;
    if (c->outIsFloat) {
        c->outBytesPerSample = 4;
    } else {
        uint32_t bits = c->outFmt.bitsPerChannel ? c->outFmt.bitsPerChannel : 16;
        c->outBytesPerSample = (bits + 7) / 8;
        if (c->outBytesPerSample < 2)
            c->outBytesPerSample = 2;
        if (c->outBytesPerSample > 4) {
            LOGW("unsupported output bit depth %u, forcing 16-bit", bits);
            c->outBytesPerSample = 2;
        }
    }
    c->outBytesPerFrame = c->outFmt.bytesPerFrame;
    uint32_t calc = c->outBytesPerSample * c->outChannels;
    if (c->outBytesPerFrame == 0)
        c->outBytesPerFrame = calc;

    char istr[160], ostr[160];
    format_to_string(&c->inFmt, istr, sizeof(istr));
    format_to_string(&c->outFmt, ostr, sizeof(ostr));
    LOGI("AudioConverterNew: in[%s] (%s) -> out[%s] (%s)", istr,
         c->inFmt.vendorLayout ? "vendor36" : "apple40", ostr,
         c->outFmt.vendorLayout ? "vendor36" : "apple40");

    if (c->outFmt.formatID == FMT_LPCM) {
        if (c->inFmt.formatID == FMT_LPCM) {
            c->mode = MODE_PCM_CONVERT;
        } else if (c->inFmt.formatID == FMT_AAC || c->inFmt.formatID == FMT_ADTS ||
                   c->inFmt.formatID == FMT_AACE || c->inFmt.formatID == FMT_AACL ||
                   c->inFmt.formatID == FMT_ELDA) {
            c->mode = MODE_AAC_DECODE;
            c->codecRate = c->inFmt.sampleRate;
            c->codecChannels = c->inFmt.channels;
            /* decoder creation is deferred to the first Fill so that a
             * misconfigured/unused converter does not break session setup */
            LOGI("AudioConverterNew: AAC decode mode (deferred decoder init)");
        } else {
            LOGE("AudioConverterNew: unsupported input format 0x%08x",
                 c->inFmt.formatID);
            c->mode = MODE_NONE;
        }
    } else if (c->inFmt.formatID == FMT_LPCM) {
        /* PCM -> compressed: the CarPlay microphone path.  Session setup must
         * succeed even though we cannot encode AAC-ELD on Android, otherwise
         * the whole AirPlay session is torn down (and therefore no playback).
         * Report as follow-up; Fill returns "operation not supported". */
        c->mode = MODE_ENCODE_UNSUPPORTED;
        LOGW("AudioConverterNew: PCM->compressed (mic/encode) not implemented, "
             "converter created as stub so session setup succeeds");
    } else {
        LOGE("AudioConverterNew: unsupported conversion 0x%08x -> 0x%08x",
             c->inFmt.formatID, c->outFmt.formatID);
        c->mode = MODE_NONE;
    }

    *outConverter = (AudioConverterRef)c;
    return AC_NoErr;
}

__attribute__((visibility("default")))
OSStatus AudioConverterDispose(AudioConverterRef conv) {
    Converter *c = (Converter *)conv;
    if (!c)
        return AC_ParamErr;
    pthread_mutex_lock(&c->lock);
    if (c->codec) {
        if (c->codecStarted) {
            AMediaCodec_stop(c->codec);
            c->codecStarted = 0;
        }
        AMediaCodec_delete(c->codec);
        c->codec = NULL;
    }
    if (c->codecFormat) {
        AMediaFormat_delete(c->codecFormat);
        c->codecFormat = NULL;
    }
    pthread_mutex_unlock(&c->lock);
    pthread_mutex_destroy(&c->lock);
    free(c->pending);
    free(c->fifo);
    free(c->scratch);
    free(c);
    LOGI("AudioConverterDispose");
    return AC_NoErr;
}

__attribute__((visibility("default")))
OSStatus AudioConverterReset(AudioConverterRef conv) {
    Converter *c = (Converter *)conv;
    if (!c)
        return AC_ParamErr;
    pthread_mutex_lock(&c->lock);
    if (c->codec && c->codecStarted)
        AMediaCodec_flush(c->codec);
    c->fifoLen = 0;
    c->havePending = 0;
    c->pendingSize = 0;
    c->inputFinal = 0;
    c->inputError = 0;
    c->sawNoData = 0;
    c->lastInputStatus = AC_NoErr;
    c->codecEosQueued = 0;
    c->codecEosSeen = 0;
    c->nextPtsUs = 0;
    c->rsActive = 0;
    c->rsHavePrev = 0;
    pthread_mutex_unlock(&c->lock);
    LOGI("AudioConverterReset");
    return AC_NoErr;
}

__attribute__((visibility("default")))
OSStatus AudioConverterSetProperty(AudioConverterRef conv, UInt32 propID,
                                   UInt32 size, const void *data) {
    Converter *c = (Converter *)conv;
    if (!c)
        return AC_ParamErr;
    pthread_mutex_lock(&c->lock);
    switch (propID) {
    case AC_Prop_DecompressionMagicCookie:
    case AC_Prop_CompressionMagicCookie: {
        if (!data || size == 0 || size > sizeof(c->cookie)) {
            pthread_mutex_unlock(&c->lock);
            return AC_Err_BadPropertySizeError;
        }
        memcpy(c->cookie, data, size);
        c->cookieSize = size;
        LOGI("SetProperty magic cookie (%u bytes, propID 0x%08x) first=%02x%02x%02x%02x",
             size, propID, size > 0 ? ((const uint8_t *)data)[0] : 0,
             size > 1 ? ((const uint8_t *)data)[1] : 0,
             size > 2 ? ((const uint8_t *)data)[2] : 0,
             size > 3 ? ((const uint8_t *)data)[3] : 0);
        pthread_mutex_unlock(&c->lock);
        return AC_NoErr;
    }
    case AC_Prop_Pakl:
        LOGI("SetProperty 'pakl' size=%u value=%d (accepted, ignored)",
             size, (size >= 4 && data) ? *(const int32_t *)data : -1);
        pthread_mutex_unlock(&c->lock);
        return AC_NoErr;
    case AC_Prop_CurrentInputStreamDesc:
    case AC_Prop_CurrentOutputStreamDesc:
        LOGI("SetProperty stream description 0x%08x size=%u (accepted, ignored)",
             propID, size);
        pthread_mutex_unlock(&c->lock);
        return AC_NoErr;
    default:
        /* libAirPlay tears the session down if any property set fails. */
        if (!c->loggedUnknownProp) {
            LOGW("SetProperty unknown propID 0x%08x size=%u accepted (ignored)",
                 propID, size);
            c->loggedUnknownProp = 1;
        } else {
            LOGV("SetProperty unknown propID 0x%08x size=%u", propID, size);
        }
        pthread_mutex_unlock(&c->lock);
        return AC_NoErr;
    }
}

__attribute__((visibility("default")))
OSStatus AudioConverterFillComplexBuffer(
        AudioConverterRef conv, AudioConverterComplexInputDataProc proc,
        void *userData, UInt32 *ioNumberDataPackets, AudioBufferList *ioData,
        AudioStreamPacketDescription *outPacketDescriptions) {
    Converter *c = (Converter *)conv;
    if (!c || !proc || !ioNumberDataPackets || !ioData)
        return AC_ParamErr;
    if (ioData->mNumberBuffers == 0 || ioData->mBuffers[0].mData == NULL)
        return AC_ParamErr;

    pthread_mutex_lock(&c->lock);
    c->inputProc = proc;
    c->inputUserData = userData;
    c->calls++;

    UInt32 requested = *ioNumberDataPackets;
    UInt32 obpf = output_bytes_per_frame(c);
    if (obpf == 0) {
        *ioNumberDataPackets = 0;
        pthread_mutex_unlock(&c->lock);
        return AC_ParamErr;
    }

    if (c->mode == MODE_ENCODE_UNSUPPORTED) {
        *ioNumberDataPackets = 0;
        if (c->calls <= 3)
            LOGW("FillComplexBuffer on unsupported encode converter -> no data");
        pthread_mutex_unlock(&c->lock);
        return AC_Err_OperationNotSupported;
    }
    if (c->mode == MODE_NONE) {
        *ioNumberDataPackets = 0;
        pthread_mutex_unlock(&c->lock);
        return AC_Err_FormatNotSupported;
    }

    /* Output capacity: single interleaved buffer. */
    size_t capBytes = ioData->mBuffers[0].mDataByteSize;
    if (capBytes == 0 && ioData->mNumberBuffers > 1) {
        capBytes = ioData->mBuffers[1].mDataByteSize;
    }
    uint32_t capFrames = obpf ? (uint32_t)(capBytes / obpf) : 0;
    uint32_t want = requested;
    if (capFrames < want)
        want = capFrames;

    if (want > 0) {
        if (c->mode == MODE_AAC_DECODE) {
            if (!c->codecStarted) {
                if (codec_create_decoder(c) != 0) {
                    *ioNumberDataPackets = 0;
                    pthread_mutex_unlock(&c->lock);
                    return AC_Err_FormatNotSupported;
                }
            }
            if (c->fifoLen < (size_t)want * obpf) {
                fill_decode(c, want);
            } else if (!c->havePending && !c->inputFinal && !c->inputError &&
                       c->fifoLen < (size_t)want * obpf + 4096) {
                /* We already have enough PCM from a previous packet.  libAirPlay
                 * stages exactly one compressed packet per Fill call and reuses
                 * that staging buffer afterwards, so consume the pending packet
                 * now (copy + feed) instead of dropping it, but bound how much
                 * extra audio we are willing to buffer ahead. */
                if (pull_packet(c) > 0) {
                    feed_pending(c);
                    drain_codec(c, 0);
                }
            }
        } else {
            fill_pcm_passthrough(c, want);
        }
    }

    UInt32 framesOut = obpf ? (UInt32)(c->fifoLen / obpf) : 0;
    if (framesOut > want)
        framesOut = want;

    if (c->calls <= 3 || c->calls % 20 == 0) {
        int first = 0;
        if (framesOut > 0) {
            if (c->outIsFloat) {
                float f = 0.0f;
                memcpy(&f, c->fifo, 4);
                first = (int)(f * 32767.0f);
            } else if (c->outBytesPerSample == 2) {
                int16_t v = 0;
                memcpy(&v, c->fifo, 2);
                first = v;
            } else if (c->outBytesPerSample >= 4) {
                int32_t v = 0;
                memcpy(&v, c->fifo, 4);
                first = (int)(v >> 16);
            }
        }
        if (c->calls == 1) {
            char ibuf[160], obuf[160];
            format_to_string(&c->inFmt, ibuf, sizeof(ibuf));
            format_to_string(&c->outFmt, obuf, sizeof(obuf));
            LOGI("Fill#1 pid=%d uid=%d: in[%s] -> out[%s] req=%u capFrames=%u "
                 "produced=%u bytes=%u firstSample=%d mode=%d",
                 (int)getpid(), (int)getuid(), ibuf, obuf, requested, capFrames,
                 framesOut, (unsigned)(framesOut * obpf), first, c->mode);
        } else {
            LOGI("Fill#%d pid=%d uid=%d: req=%u capFrames=%u produced=%u bytes=%u "
                 "firstSample=%d mode=%d fifo=%zu", c->calls, (int)getpid(),
                 (int)getuid(), requested, capFrames, framesOut,
                 (unsigned)(framesOut * obpf), first, c->mode, c->fifoLen);
        }
    }

    if (framesOut > 0 && c->outIsInterleaved && ioData->mNumberBuffers == 1) {
        size_t bytes = (size_t)framesOut * obpf;
        memcpy(ioData->mBuffers[0].mData, c->fifo, bytes);
        ioData->mBuffers[0].mDataByteSize = (UInt32)bytes;
        ioData->mBuffers[0].mNumberChannels = c->outChannels;
        fifo_consume(c, bytes);
    } else if (framesOut > 0) {
        /* Non-interleaved output: deinterleave. */
        size_t need = 0;
        UInt32 bps = c->outBytesPerSample;
        size_t total = (size_t)framesOut * obpf;
        size_t avail = c->fifoLen;
        if (avail < total)
            total = avail;
        framesOut = (UInt32)(total / obpf);
        for (UInt32 b = 0; b < ioData->mNumberBuffers && b < c->outChannels; b++) {
            size_t sz = (size_t)framesOut * bps;
            uint8_t *dst = (uint8_t *)ioData->mBuffers[b].mData;
            if (!dst)
                continue;
            if (sz > ioData->mBuffers[b].mDataByteSize)
                sz = ioData->mBuffers[b].mDataByteSize;
            ioData->mBuffers[b].mDataByteSize = (UInt32)sz;
            for (UInt32 f = 0; f < framesOut; f++) {
                size_t srcOff = (size_t)f * obpf + (size_t)b * bps;
                if (srcOff + bps <= avail)
                    memcpy(dst + (size_t)f * bps, c->fifo + srcOff, bps);
            }
            need += sz;
        }
        (void)need;
        fifo_consume(c, (size_t)framesOut * obpf);
    } else if (ioData->mNumberBuffers >= 1) {
        ioData->mBuffers[0].mDataByteSize = 0;
    }

    *ioNumberDataPackets = framesOut;

    /* Packet descriptions are only meaningful for VBR/compressed output;
     * our output is always uncompressed, and libAirPlay passes NULL. */
    (void)outPacketDescriptions;

    OSStatus rc;
    if (framesOut > 0) {
        rc = AC_NoErr;
    } else if (c->inputError) {
        rc = c->inputError;
    } else if (c->sawNoData || c->lastInputStatus == AC_Err_NoMoreData) {
        rc = AC_Err_NoMoreData;
    } else {
        rc = AC_NoErr;
    }
    if (c->codecError)
        rc = AC_Err_OperationNotSupported;

    if (c->calls % 500 == 0) {
        LOGI("stats: calls=%d packetsFed=%llu dropped=%llu fifo=%zu rc=%d",
             c->calls, (unsigned long long)c->packetsFed,
             (unsigned long long)c->packetsDropped, c->fifoLen, (int)rc);
    }
    pthread_mutex_unlock(&c->lock);
    return rc;
}
