package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.util.List;

/**
 * A mounted file system. Paths are relative to the mount point, '/'-separated, canonical, with no leading slash;
 * the empty string is the mount root.
 */
public interface FsBackend {
    Stat stat(String path) throws FsException;

    List<String> list(String path) throws FsException;

    /** Open a regular file. Directories are opened by the VFS itself. */
    OpenFile open(String path, String guestPath, int flags) throws FsException;

    default void mkdir(String path) throws FsException {
        throw new FsException(Errno.EROFS);
    }

    default void unlink(String path) throws FsException {
        throw new FsException(Errno.EROFS);
    }

    default void rmdir(String path) throws FsException {
        throw new FsException(Errno.EROFS);
    }

    default void rename(String from, String to) throws FsException {
        throw new FsException(Errno.EROFS);
    }

    /**
     * Change permission bits and owner ({@code -1}: keep). New files get theirs from the VFS right after creation.
     * File systems without ownership (CC floppies, the ROM) refuse.
     */
    default void setAttributes(String path, int mode, int uid, int gid) throws FsException {
        throw new FsException(isReadOnly() ? Errno.EROFS : Errno.EPERM);
    }

    default boolean isReadOnly() {
        return true;
    }

    default long freeSpace() {
        return 0;
    }

    default long capacity() {
        return 0;
    }

    /** Called when the file system is unmounted (a disk ejected); files still open on it should stop working. */
    default void unmounted() {
    }

    /** Human readable description for /proc/mounts and df. */
    String describe();
}
