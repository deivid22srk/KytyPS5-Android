/*
 * android/guest/guest_entry.c — process entry for the bionic x86_64 guest.
 *
 * The emulator binary targets x86_64-linux-android, but it never runs on a
 * real Android/x86_64 system: it runs inside box64 (library mode) on an
 * ARM64 Android host. Under box64 the normal bionic crt path must NOT run:
 * crtbegin calls __libc_init(), which re-initializes the host process's libc
 * (TLS, atexit, ...) with the emulated context — fatal in-process.
 *
 * box64 already provides everything the entry path would:
 *   - the initial stack (argc / argv / envp) per the x86_64 SysV ABI,
 *   - the emulated FS/TLS state (RefreshElfTLS),
 *   - execution of the binary's .init_array (static C++ constructors).
 *
 * So _start only fetches argc/argv/envp from the stack, aligns, and calls
 * main(); the result goes through exit(), which box64's my_exit() turns into
 * a longjmp() back to the host bridge (box64lib library mode).
 */

extern int main(int argc, char **argv, char **envp);
extern void exit(int status) __attribute__((noreturn));

extern int guest_main(int argc, char **argv, char **envp);

__asm__(
    ".globl _start\n"
    ".type _start, @function\n"
    "_start:\n"
    "   xorl %ebp, %ebp\n"            /* deepest frame marker */
    "   movq (%rsp), %rdi\n"          /* argc */
    "   leaq 8(%rsp), %rsi\n"         /* argv */
    "   leaq 16(%rsp,%rdi,8), %rdx\n" /* envp = &argv[argc + 1] */
    "   andq $-16, %rsp\n"            /* ABI stack alignment for the call */
    "   call guest_main\n"
    "   hlt\n"                         /* guest_main never returns */
    ".size _start, .-_start\n"
);

int guest_main(int argc, char **argv, char **envp) {
    /* .init_array ran under box64 (RunElfInit); go straight to main. */
    int status = main(argc, argv, envp);

    /* wrapped bionic exit() -> box64 my_exit() -> longjmp to the host */
    exit(status);

    __builtin_unreachable();
}
