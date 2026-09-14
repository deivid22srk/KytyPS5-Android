/*
 * android/guest/glibc_compat.cpp — glibc-only names used by prebuilt
 * third-party archives, forwarded to their bionic equivalents.
 *
 * The emulator binary itself is compiled against bionic, but some vendored
 * static libraries (notably the shadPS4 ext-ffmpeg-core prebuilts) were
 * built on glibc Linux and reference a handful of glibc-specific symbols.
 * bionic does not export those names, so the link would fail (and, if
 * silently allowed, the calls would go to NULL under box64).
 *
 * Every shim below forwards to a bionic function with identical semantics;
 * under box64 the forward target resolves through the wrapped libc to the
 * host's own implementation, so behaviour stays consistent on both sides.
 *
 * Add new shims ONLY for symbols that are pure renames of bionic APIs —
 * anything semantically different needs real porting work instead.
 */

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <fcntl.h>
#include <locale.h>

extern "C" {

/* Declare the bionic targets we forward to (not all are in public headers). */
int __cxa_atexit(void (*fn)(void*), void* arg, void* dso_handle);

/* Bionic's <string.h> renames strerror_r to __gnu_strerror_r when _GNU_SOURCE
 * is visible (the NDK clang defines it by default), and box64's wrapped libc
 * does not map __gnu_strerror_r. Reach the literal "strerror_r" symbol — which
 * bionic implements as the POSIX int-returning variant — through an asm label,
 * dodging the header rename. */
extern "C" int kyty_posix_strerror_r(int err, char* buf, size_t len) __asm__("strerror_r");

/* glibc errno access: int *__errno_location(void). Bionic's is __errno(). */
int *__errno_location(void) {
    return __errno();
}

/* glibc _FORTIFY_SOURCE fprintf, referenced by the prebuilt FFmpeg archives
 * (compiled on glibc with fortify enabled). Bionic has no __fprintf_chk. */
int __fprintf_chk(FILE* f, int flag, const char* fmt, ...) {
    (void)flag;
    va_list ap;
    va_start(ap, fmt);
    int r = vfprintf(f, fmt, ap);
    va_end(ap);
    return r;
}

/* glibc LFS64 fcntl alias used by the FFmpeg prebuilts. Every call site
 * passes the optional third argument (F_SETFD/F_SETFL); commands that
 * ignore the argument simply never read it. */
int fcntl64(int fd, int cmd, ...) {
    va_list ap;
    va_start(ap, cmd);
    long arg = va_arg(ap, long);
    va_end(ap);
    return fcntl(fd, cmd, arg);
}

/* glibc's XPG strerror_r (the int-returning flavour). Bionic's strerror_r
 * IS the XPG variant (reached via the asm label above). */
int __xpg_strerror_r(int err, char* buf, size_t len) {
    return kyty_posix_strerror_r(err, buf, len);
}

/* cpuinfo's clog (prebuilt with an Android toolchain) logs through liblog.
 * The guest cannot DT_NEEDED liblog: box64 has no wrapped liblog, so the
 * dependency would be loaded as an emulated x86_64 library and fail. Route
 * to stderr instead — the host bridge redirects it to the session log. */
int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap) {
    static const char* const prio_names[] = {"UNKNOWN", "DEFAULT", "VERBOSE", "DEBUG",
                                             "INFO", "WARN", "ERROR", "FATAL"};
    const char* p = (prio >= 0 && prio <= 7) ? prio_names[prio] : "?";
    int n = std::fprintf(stderr, "%s/%s: ", p, (tag != nullptr) ? tag : "clog");
    n += vfprintf(stderr, fmt, ap);
    std::fputc('\n', stderr);
    return n + 1;
}

/* bionic's libc.so exports __cxa_atexit but never atexit itself (the NDK
 * clang auto-wraps plain-C atexit calls only; the C++ <cstdlib> path stays
 * an extern call). Forward to __cxa_atexit — under box64 that resolves to
 * my___cxa_atexit, which registers the callback on box64's own guest
 * cleanup list. The extra argument register is ignored by a void(void)
 * callee under the x86_64 SysV ABI. */
int atexit(void (*fn)(void)) {
    union {
        void (*plain)(void);
        void (*with_arg)(void*);
    } cast;
    cast.plain = fn;
    return __cxa_atexit(cast.with_arg, nullptr, nullptr);
}

/* glibc ISO-C99 stdio renames (headers redirect scanf family when
 * __USE_ISOC99 is in effect at build time). Bionic uses plain names. */
int __isoc99_sscanf(const char *s, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = vsscanf(s, fmt, ap);
    va_end(ap);
    return r;
}

int __isoc99_vsscanf(const char *s, const char *fmt, va_list ap) {
    return vsscanf(s, fmt, ap);
}

int __isoc99_scanf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = vscanf(fmt, ap);
    va_end(ap);
    return r;
}

int __isoc99_vscanf(const char *fmt, va_list ap) {
    return vscanf(fmt, ap);
}

int __isoc99_fscanf(FILE *f, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = vfscanf(f, fmt, ap);
    va_end(ap);
    return r;
}

int __isoc99_vfscanf(FILE *f, const char *fmt, va_list ap) {
    return vfscanf(f, fmt, ap);
}

/* glibc 2.38+ ISO-C23 strtoul renames. */
unsigned long __isoc23_strtoul(const char *nptr, char **endptr, int base) {
    return strtoul(nptr, endptr, base);
}

long __isoc23_strtol(const char *nptr, char **endptr, int base) {
    return strtol(nptr, endptr, base);
}

unsigned long long __isoc23_strtoull(const char *nptr, char **endptr, int base) {
    return strtoull(nptr, endptr, base);
}

long long __isoc23_strtoll(const char *nptr, char **endptr, int base) {
    return strtoll(nptr, endptr, base);
}

/* glibc's GNU basename (bionic's <string.h> renames basename() to
 * __gnu_basename; the host bionic libc only exports that name since API 35).
 * GNU semantics: pure pointer arithmetic, the argument is never modified. */
char* __gnu_basename(const char* path) {
    const char* p = strrchr(path, '/');
    return const_cast<char*>(p != nullptr ? p + 1 : path);
}

/* C99 long-double string conversions. bionic implements them, but a box64
 * passthrough cannot forward an x87 80-bit return value from the ARM64 host
 * (whose long double is 128-bit). Evaluate with the double-precision
 * variants and widen here, in guest code, where the x87 ABI applies. */
long double strtold(const char* nptr, char** endptr) {
    return static_cast<long double>(strtod(nptr, endptr));
}

long double strtold_l(const char* nptr, char** endptr, locale_t loc) {
    return static_cast<long double>(strtod_l(nptr, endptr, loc));
}

long double wcstold(const wchar_t* nptr, wchar_t** endptr) {
    return static_cast<long double>(wcstod(nptr, endptr));
}

} /* extern "C" */
