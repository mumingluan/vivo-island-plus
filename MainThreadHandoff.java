package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A timed handoff to one serial queue, with ordered cleanup on uncertainty. */
final class MainThreadHandoff {
    interface Queue {
        boolean post(Runnable action);
        void remove(Runnable action);
    }

    interface Operation { void run() throws Throwable; }

    static void call(Queue queue, final Operation operation, Runnable cleanup, long timeoutMillis) throws Exception {
        final Throwable[] error = new Throwable[1];
        final CountDownLatch done = new CountDownLatch(1);
        Runnable request = new Runnable() { public void run() {
            try { operation.run(); }
            catch (Throwable t) { error[0] = t; }
            finally { done.countDown(); }
        }};
        if (!queue.post(request)) throw new IOException("native island main looper stopped");
        boolean completed;
        try { completed = done.await(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            withdraw(queue, request, cleanup);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!completed) {
            withdraw(queue, request, cleanup);
            throw new IOException("native island main-thread timeout; withdrawal queued");
        }
        if (error[0] != null) {
            withdraw(queue, request, cleanup);
            throw new IOException("native island", error[0]);
        }
    }

    private static void withdraw(Queue queue, Runnable request, Runnable cleanup) {
        // A pending request is removed. A running request finishes before this
        // cleanup, which is queued before the caller can submit its next update.
        queue.remove(request);
        if (!queue.post(cleanup)) throw new IllegalStateException("native island withdrawal queue stopped");
    }
}
