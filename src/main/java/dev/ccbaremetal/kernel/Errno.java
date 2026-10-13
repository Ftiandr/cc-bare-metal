package dev.ccbaremetal.kernel;

/**
 * WASI preview1 errno values. The kernel uses these everywhere (WASI, our own syscalls, Lua errors) so that
 * there is a single error vocabulary.
 */
public final class Errno {
    public static final int SUCCESS = 0;
    public static final int E2BIG = 1;
    public static final int EACCES = 2;
    public static final int EADDRINUSE = 3;
    public static final int EAGAIN = 6;
    public static final int EBADF = 8;
    public static final int EBUSY = 10;
    public static final int ECHILD = 12;
    public static final int EEXIST = 20;
    public static final int EFAULT = 21;
    public static final int EFBIG = 22;
    public static final int ECONNREFUSED = 14;
    public static final int ECONNRESET = 15;
    public static final int EHOSTUNREACH = 23;
    public static final int ENETDOWN = 38;
    public static final int ENOTCONN = 53;
    public static final int ENOTSOCK = 57;
    public static final int EINTR = 27;
    public static final int EINVAL = 28;
    public static final int EIO = 29;
    public static final int EISDIR = 31;
    public static final int EMFILE = 33;
    public static final int ENAMETOOLONG = 37;
    public static final int ENFILE = 41;
    public static final int ENODEV = 43;
    public static final int ENOENT = 44;
    public static final int ENOEXEC = 45;
    public static final int ENOMEM = 48;
    public static final int ENOSPC = 51;
    public static final int ENOSYS = 52;
    public static final int ENOTDIR = 54;
    public static final int ENOTEMPTY = 55;
    public static final int ENOTSUP = 58;
    public static final int ENOTTY = 59;
    public static final int EPERM = 63;
    public static final int EPIPE = 64;
    public static final int EROFS = 69;
    public static final int ESPIPE = 70;
    public static final int ESRCH = 71;
    public static final int ETIMEDOUT = 73;
    public static final int EXDEV = 75;

    private Errno() {
    }

    public static String describe(int errno) {
        return switch (errno) {
            case SUCCESS -> "Success";
            case E2BIG -> "Argument list too long";
            case EACCES -> "Permission denied";
            case EADDRINUSE -> "Address in use";
            case ECONNREFUSED -> "Connection refused";
            case ECONNRESET -> "Connection reset";
            case EHOSTUNREACH -> "Host is unreachable";
            case ENETDOWN -> "Network is down";
            case ENOTCONN -> "Socket not connected";
            case ENOTSOCK -> "Not a socket";
            case EAGAIN -> "Resource temporarily unavailable";
            case EBADF -> "Bad file descriptor";
            case EBUSY -> "Device or resource busy";
            case ECHILD -> "No child processes";
            case EEXIST -> "File exists";
            case EFAULT -> "Bad address";
            case EFBIG -> "File too large";
            case EINTR -> "Interrupted";
            case EINVAL -> "Invalid argument";
            case EIO -> "I/O error";
            case EISDIR -> "Is a directory";
            case EMFILE -> "Too many open files";
            case ENAMETOOLONG -> "File name too long";
            case ENODEV -> "No such device";
            case ENOENT -> "No such file or directory";
            case ENOEXEC -> "Exec format error";
            case ENOMEM -> "Out of memory";
            case ENOSPC -> "Out of space";
            case ENOSYS -> "Function not implemented";
            case ENOTDIR -> "Not a directory";
            case ENOTEMPTY -> "Directory not empty";
            case ENOTSUP -> "Not supported";
            case ENOTTY -> "Not a terminal";
            case EPERM -> "Operation not permitted";
            case EPIPE -> "Broken pipe";
            case EROFS -> "Read-only file system";
            case ESPIPE -> "Illegal seek";
            case ESRCH -> "No such process";
            case ETIMEDOUT -> "Timed out";
            case EXDEV -> "Cross-device link";
            default -> "Error " + errno;
        };
    }
}
