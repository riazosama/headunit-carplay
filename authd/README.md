# authd — root MFi authentication helper for the BWIC A100 (AC8257)

`authd` gives the unprivileged CarPlay bridge app access to the genuine Apple
MFi coprocessor on the unit, so the iAP2 authentication exchange uses a real
Apple-issued certificate and a real chip signature instead of a stub.

* **Approach used: A — reuse the vendor's own MFi platform layer**
  (`/system/lib64/libCoreUtils.so`, `MFiPlatform_*`), loaded with `dlopen` from a
  native root process. The vendor library resolves `/dev/i2c-6` + `0x10` itself
  (via `libMFICoprocessor` + the Autochips *metalogo* HIDL service) and already
  contains the vendor's ERMOTEIO retry logic. Approach B (raw CP2.0C register
  protocol) is implemented for diagnostics only — it is documented at the end.
* **Verified on the unit** (BWIC A100, AC8257, Android 9, `adb -s
  <unit-ip>:5555`, root): 30/30 separate `selftest` runs PASS, 54/54
  socket replies under 6-way concurrency, signature cryptographically verified
  against the certificate's public key.

## Measured results (what the bridge app needs)

| item | value |
|---|---|
| **certificate** | **607 bytes** (`0x25F`), DER `30 82 02 5B …` |
| certificate format | PKCS#7 `SignedData` containing **one** X.509 certificate |
| certificate subject | `C=US, O=Apple Inc., OU=Apple Accessories, CN=IPA_<redacted-unit-mfi-serial>` |
| certificate issuer | `Apple Accessories Certification Authority - 00000002` |
| certificate key | ECDSA **P-256** (`prime256v1`), signed `ecdsa-with-SHA256`, valid 2022-03-24 → 2049-12-31 |
| certificate SHA-256 (of the 1214-char lowercase hex string) | `<redacted>` |
| **signature** | **64 bytes** = raw ECDSA `r‖s` (P-256), no DER wrapper |
| signature semantics | ECDSA-P256 over the **32-byte challenge used directly as the message digest** (the chip does *not* hash it again) — verified with `openssl pkeyutl` |
| challenge | exactly **32 bytes** (Auth-v3 chip) |
| **MFi serial** | 32 ASCII bytes `<redacted-unit-mfi-serial>` (= the certificate's X.509 serial) |
| chip | reg `0x02` = `0x03` (**“Auth v3”**), reg `0x00` = `0x07` (serial length 32) |
| bus/address | `/dev/i2c-6`, slave `0x10`, userspace i2c-dev |
| latency | `sign` **415–440 ms** typically; certificate is cached by the vendor lib (~60–100 ms); process start does one i2c certificate read (0.04–2.3 s depending on contention) |
| stability | 30/30 `selftest` runs, identical 607-byte cert, always 64-byte signatures |

Two things that surprise people:

1. The certificate is a **single certificate**, not a chain. The phone builds
   the chain to the Apple root itself, so send the 607-byte blob as-is.
2. Signatures are **not reproducible**: the chip uses a randomized ECDSA nonce,
   so the same challenge gives a different 64-byte signature every time. That is
   correct — never compare signatures, always length/validity-check.

## Build

```sh
./build.sh          # NDK 26.1.10909125, aarch64-linux-android28, pure C
```
Produces `authd` (~33 KB) and `cpauth_client` (~19 KB, reference socket client).
No third-party dependencies; only bionic `libc`/`libdl`. Override with
`NDK=/path/to/ndk ./build.sh` or `API=28`.

## Install and run

```sh
adb -s <unit-ip>:5555 root
adb -s <unit-ip>:5555 push authd cpauth_client /data/local/tmp/
adb -s <unit-ip>:5555 shell chmod 755 /data/local/tmp/authd /data/local/tmp/cpauth_client

# CLI (root)
adb shell /data/local/tmp/authd cert                 # 1214 hex chars, len=607 on stderr
adb shell /data/local/tmp/authd sign <64-hex-challenge>
adb shell /data/local/tmp/authd selftest 1           # sizes, timings, stability

# daemon: detached, double-fork, survives the adb shell
adb shell /data/local/tmp/authd spawn                # prints pid
adb shell /data/local/tmp/authd status
adb shell /data/local/tmp/authd stop

# or the host helper
./run.sh build && ./run.sh push && ./run.sh start && ./run.sh selftest 10
```

`spawn` is idempotent: if the pidfile points at a live daemon it just prints
that pid instead of starting a second one.

### Starting the daemon after a reboot

The daemon is **not** installed as a boot service (the unit's configuration was
left untouched, as required). `/data/local/tmp` persists across reboot, so pick
one of:

1. **The bridge app starts it** (recommended — no unit changes):
   ```java
   Runtime.getRuntime().exec(new String[]{"su", "-c",
           "/data/local/tmp/authd spawn"});
   ```
2. **adb**, after `adb root`:
   ```sh
   adb shell /data/local/tmp/authd spawn
   ```
3. **Magisk boot script** (if the unit owner agrees to that config change):
   create `/data/adb/service.d/authd.sh`
   ```sh
   #!/system/bin/sh
   /data/local/tmp/authd spawn
   ```
   and `chmod 755` it. This was **not** done here.

## App-facing socket protocol

* Unix domain socket, **filesystem** path `/data/local/tmp/cpauth.sock`,
  mode `0666` (`srw-rw-rw-`, owned by root). Any app uid can connect; verified
  with uid 2000 (shell) and uid 10123 (app-like).
* All integers **big-endian**. One connection may carry several sequential
  requests (open once, keep it; or open per request — both work).

```
request :  u8 op | u16 payload_len | payload[payload_len]
response:  u16 len | bytes[len]                    len == 0  ->  error

op 1 = cert    payload: none            response: 607-byte certificate
op 2 = sign    payload: 32-byte challenge   response: 64-byte signature
op 3 = serial  payload: none            response: 32-byte ASCII MFi serial
```

* `op 2` with a challenge that is not 32 bytes returns a 0-length error.
* Requests are serialized internally (exactly one chip transaction at a time,
  single worker thread) and each caller gets a bounded deadline — default
  **15 000 ms**, override with `CPAUTH_TIMEOUT_MS` in the daemon's environment.
  A busy/stuck chip therefore yields a `0`-length error, never a hung app.
  Max 16 queued requests, max 8 concurrent client connections.
* Daemon log: `/data/local/tmp/authd.log`; pidfile `/data/local/tmp/authd.pid`.

### Android client example

```java
android.net.LocalSocket sock = new android.net.LocalSocket();
sock.setSoTimeout(20000);
sock.connect(new android.net.LocalSocketAddress(
        "/data/local/tmp/cpauth.sock",
        android.net.LocalSocketAddress.Namespace.FILESYSTEM));

java.io.OutputStream os = sock.getOutputStream();
os.write(2);                       // op = sign
os.write((32 >> 8) & 0xff);        // u16 len, big-endian
os.write(32 & 0xff);
os.write(challenge32);
os.flush();

java.io.InputStream in = sock.getInputStream();
int hi = in.read(), lo = in.read();
int n = (hi << 8) | lo;            // 0 => error
byte[] sig = new byte[n];
for (int off = 0; off < n; ) off += in.read(sig, off, n - off);
sock.close();                      // 64 bytes for a 32-byte challenge
```

Use `android.net.LocalSocket` (not `java.net.Socket`) — Android 9 has no
`UnixDomainSocketAddress` and `LocalSocket` with `Namespace.FILESYSTEM` is the
supported way to reach a filesystem unix socket.

## How it works (approach A)

`libCoreUtils.so` exports the same MFi platform layer the vendor AirPlay
receiver uses:

```
MFiPlatform_Initialize(void)                                   @ 0x62980
MFiPlatform_CopyCertificate(void **data, size_t *len)          @ 0x62bd8
MFiPlatform_CreateSignature(const void *chal, size_t clen,
                            void **sig, size_t *siglen)        @ 0x63050
MFiPlatform_Finalize(void)                                     @ 0x63010
```

`authd` `dlopen`s the library, calls `Initialize()` (which resolves
`/dev/i2c-6` and slave `0x10` through `libMFICoprocessor` + the Autochips
metalogo HIDL service and caches the certificate), then uses
`CopyCertificate`/`CreateSignature` for the socket operations. The returned
buffers are `malloc`ed by the vendor library and freed by `authd`.

### The chip-contention problem and the Auth-v3 work-around

Both the vendor iAP2 daemon and the AirPlay control process also drive this
chip, so nearly every i2c operation can transiently fail with
`121/0x79 ERMOTEIO`. The vendor library already retries internally (~600 write
attempts, 500 µs back-off, 2 s cap per i2c op); `authd` adds an outer retry
(5 attempts for the certificate, 12 for the signature, 10–120 ms back-off) and
opens the chip from exactly one worker thread.

The dangerous failure mode found during bring-up: on its first
`CreateSignature`, the vendor library reads register `0x02` once and caches the
result for the whole process. Under contention that read can come back
corrupted — observed values include `0x17`, logged by the vendor as
`Using MFi Auth v.23 chip` — after which the library takes its legacy
20-byte-challenge path and **every** 32-byte signature fails with
`-6743 kSizeErr` for the rest of the process' life (measured 2/10 runs).

`authd` fixes this: it determines the generation itself with a majority vote
over several raw `0x02` reads, cross-checks it against the certificate (an
ECDSA P-256 certificate only exists on an Auth-v3 chip), validates the loaded
library's layout by comparing all four `MFiPlatform_*` symbol addresses against
the known build, and only then pre-sets the library's cached "auth v3" flag
(offset `+0x100588` of the load bias). If the library ever changes, the offsets
fail to validate and the work-around disables itself (vendor behaviour is used
unchanged). Disable explicitly with `CPAUTH_NO_AUTH3_PATCH=1`. Result: 30/30
clean runs.

## Caveats / operational notes

* **The vendor HAL must keep running** — do not kill `iap@1.0-service` or the
  AirPlay process. `authd` coexists with them (that is the design); the chip is
  simply shared. Keep transactions short; request a signature only when a
  challenge actually needs signing.
* Root is required in practice (the helper process is started as root; the app
  reaches it over the socket). SELinux is permissive on this unit.
* `/dev/i2c-6` is mode `0666`, but a second independent i2c user would fight the
  vendor HAL over the chip's single register pointer — always go through one
  `authd`.
* Raw register reads from a *second* file descriptor (our diagnostics) frequently
  fail while the vendor library's own reads succeed, which is why `selftest`
  sometimes prints `certlen reg0x30: unavailable`. This is expected and does not
  affect the certificate/signature path. `i2cdetect -y 6` still shows `10`.
* `authd spawn` refuses to start a second instance (the old process keeps the
  socket); use `authd stop` first.
* Certificate bytes are constant for the life of the unit — the app can fetch
  once and cache. Signatures are per-challenge and randomized.

## Approach B: raw CP2.0C register protocol (documented fallback / diagnostics)

Confirmed from `libCoreUtils.so` disassembly and reproduced with `i2ctransfer`
/ `authd raw`; the transport is `open("/dev/i2c-6", O_RDWR)` +
`ioctl(fd, I2C_SLAVE /*0x703*/, 0x10)`, each register access being
`write(fd, {reg, data…})` then `read(fd, buf, n)` (the chip keeps one register
pointer, so write and read must not be interleaved with another user).

| register | access | meaning |
|---|---|---|
| `0x00` | read 1 | chip type: `5` → serial 31 B, `7` → serial 32 B |
| `0x02` | read 1 | auth generation: `3` = Auth v3 (32-byte challenge) |
| `0x10` | write 1 = `1` | start signing |
| `0x10` | read 1 (signed) | status; negative = busy/failed |
| `0x11` | read 2 (**BE**) | signature/response length |
| `0x12` | read len | signature/response bytes (64 on v3) |
| `0x21` | write 32 | challenge (Auth v3; `0x20` with a 22-byte buffer on v2) |
| `0x30` | read 2 (**BE**) | certificate length (0x025F = 607) |
| `0x31 + offset` | read ≤128 | certificate data, 128-byte chunks (last chunk short) |
| `0x4E` | read 31/32 | MFi serial, ASCII (here `<redacted-unit-mfi-serial>`) |

Signing sequence (Auth v3): write 32-byte challenge to `0x21` → write `1` to
`0x10` → read `0x10` (must be ≥ 0) → read `0x11` (BE, must be 64) → read `0x12`
(64 bytes). `authd raw <reg> [len]` exposes single register reads for debugging.

## Files

```
authd/
├── authd                 # built aarch64 binary (33 KB)
├── cpauth_client         # built reference socket client
├── build.sh              # NDK build
├── run.sh                # host helper (build/push/start/stop/selftest/log)
├── src/authd.c           # the whole implementation (single C file)
├── tools/cpauth_client.c # reference client for the socket protocol
├── README.md             # this file
└── refs/                 # libraries pulled off the unit for analysis
    ├── libCoreUtils.so   # /system/lib64/libCoreUtils.so (MFiPlatform_*)
    ├── libMFICoprocessor.so
    └── libcoprocessor.so
```

## Verification evidence (on <unit-ip>:5555, 2026-10-25)

* `authd selftest 1` → `cert_len=607 sig_len=64 cert_stable=yes`, `RESULT: PASS`.
* 30 separate `selftest` invocations → `pass=30 fail=0`; 10-iteration run →
  `ok=10 distinct_sigs=10` (randomized nonce, as expected).
* `cert` from 3 fresh processes → identical SHA-256
  `<redacted>`.
* Signature of challenge `00 01 … 1f` verified against the certificate's
  P-256 public key:
  `openssl pkeyutl -verify -pubin -inkey mfi_pub.pem -sigfile sig.der
  -in challenge.bin -pkeyopt digest:sha256` → **Signature Verified Successfully**
  (and the SHA-256-of-challenge interpretation correctly fails, proving the
  challenge is used directly as the digest).
* `i2cdetect -y 6` → device `10` present (`UU` markers show the vendor’s other
  i2c users are active).
* 6 concurrent clients × 3 round-trips → 54/54 replies, 0 errors, daemon alive.
* Non-root connect check: uid 2000 and uid 10123 got valid cert/serial replies
  over `srw-rw-rw-`.
