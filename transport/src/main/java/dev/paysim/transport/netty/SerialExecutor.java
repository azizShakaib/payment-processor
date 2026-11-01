package dev.paysim.transport.netty;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs tasks one at a time, in submission order, on top of a shared executor.
 *
 * <p>One instance per connection. The first task submitted while idle starts
 * a drain loop on the delegate (one virtual thread); further tasks just join
 * the queue and are picked up by that loop. When the queue empties the loop
 * ends and the virtual thread is released, so idle connections hold no thread.
 * Different connections have different instances and so run in parallel.
 *
 * <p>The queue is unbounded: a handler slower than the sender builds backlog.
 * Real flow control (limits on in-flight messages) belongs to the layers above.
 */
final class SerialExecutor implements Executor {

    private static final Logger LOG = Logger.getLogger(SerialExecutor.class.getName());

    private final Executor delegate;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    SerialExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        queue.add(task);
        schedule();
    }

    private void schedule() {
        if (draining.compareAndSet(false, true)) {
            try {
                delegate.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // Executor shut down (transport closing): drop pending work.
                queue.clear();
                draining.set(false);
                LOG.fine("Executor rejected work; dropping queued tasks");
            }
        }
    }

    private void drain() {
        try {
            Runnable task;
            while ((task = queue.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "Handler failed: " + t.getClass().getName() + ": " + t.getMessage());
                }
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty()) {
                schedule();
            }
        }
    }
}
