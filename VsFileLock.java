package org.framework.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One lock per service, held while its .vs file is read, changed and written back.
 *
 * <p>Two writers touch the same file: the execution-mode sync ({@link ExecutionModeXmlUpdater})
 * and response recording ({@link VsResponseWriter}). Each one reads the whole file, edits it and
 * writes the whole file, so without a shared lock one could overwrite the other's change.
 */
public final class VsFileLock {

    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private VsFileLock() {
    }

    public static Object of(String serviceName) {
        return LOCKS.computeIfAbsent(serviceName == null ? "" : serviceName, k -> new Object());
    }
}
