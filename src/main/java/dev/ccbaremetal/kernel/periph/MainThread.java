package dev.ccbaremetal.kernel.periph;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Work that must run on the Minecraft server thread (CC "main thread" peripheral methods, world access).
 * Drained every server tick with a time budget, so guests can never stall the server.
 */
public final class MainThread {
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    private static final long BUDGET_NANOS = 5_000_000L;

    private MainThread() {
    }

    public static void submit(Runnable task) {
        QUEUE.add(task);
    }

    /** Called from the server tick. */
    public static void drain() {
        var deadline = System.nanoTime() + BUDGET_NANOS;
        Runnable task;
        while (System.nanoTime() < deadline && (task = QUEUE.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger(MainThread.class).error("Error running main thread task", t);
            }
        }
    }

    public static void clear() {
        QUEUE.clear();
    }
}
