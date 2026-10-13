package dev.ccbaremetal.kernel.event;

import java.util.ArrayDeque;
import java.util.Iterator;

/** A bounded per-process event queue with blocking, filtered take. */
public final class EventQueue {
    private static final int LIMIT = 256;
    private final ArrayDeque<Event> queue = new ArrayDeque<>();

    public synchronized void offer(Event event) {
        if (queue.size() >= LIMIT) return;
        queue.addLast(event);
        notifyAll();
    }

    /**
     * Take the first event matching {@code filter} (null = any; "terminate" always matches). Non-matching events are
     * left in the queue. Returns null on timeout; {@code timeoutMs < 0} waits forever.
     */
    public synchronized Event take(String filter, long timeoutMs) throws InterruptedException {
        var deadline = timeoutMs < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMs;
        while (true) {
            for (Iterator<Event> it = queue.iterator(); it.hasNext(); ) {
                var event = it.next();
                if (filter == null || filter.equals(event.name()) || event.name().equals("terminate")) {
                    it.remove();
                    return event;
                }
            }
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return null;
            wait(timeoutMs < 0 ? 0 : remaining);
        }
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized void clear() {
        queue.clear();
    }
}
