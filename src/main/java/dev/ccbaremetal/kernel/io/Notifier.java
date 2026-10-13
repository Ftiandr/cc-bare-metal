package dev.ccbaremetal.kernel.io;

/**
 * A coarse "something happened" signal used by poll_oneoff: any pipe, terminal or event activity on a machine
 * wakes all pollers on that machine, which then re-check their subscriptions.
 */
public final class Notifier {
    private long generation;

    public synchronized void signal() {
        generation++;
        notifyAll();
    }

    public synchronized long generation() {
        return generation;
    }

    /** Wait until the generation differs from {@code seen}, or the timeout (in nanoseconds) elapses. */
    public synchronized void await(long seen, long timeoutNanos) throws InterruptedException {
        var deadline = System.nanoTime() + timeoutNanos;
        while (generation == seen) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            wait(Math.max(1, remaining / 1_000_000), (int) (remaining % 1_000_000 > 0 ? 1 : 0));
        }
    }
}
