package com.axuan.vivoislandplus;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Host-side concurrency checks for the production handoff; no Android UI required. */
public final class MainThreadHandoffTest {
    static final class SerialQueue implements MainThreadHandoff.Queue, AutoCloseable {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        public boolean post(Runnable action) { executor.execute(action); return true; }
        public void remove(Runnable action) { executor.remove(action); }
        public void close() throws Exception { executor.shutdown(); if (!executor.awaitTermination(3, TimeUnit.SECONDS)) throw new AssertionError("queue stuck"); }
    }

    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static void await(CountDownLatch latch) throws Exception { check(latch.await(2, TimeUnit.SECONDS), "latch timeout"); }

    public static void main(String[] args) throws Exception {
        List<String> passed = new ArrayList<>();
        try (SerialQueue queue = new SerialQueue()) {
            List<String> events = Collections.synchronizedList(new ArrayList<String>());
            MainThreadHandoff.call(queue, () -> events.add("apply"), () -> events.add("withdraw"), 1000);
            check(events.equals(Arrays.asList("apply")), "successful request must stay");
            passed.add("successful request");
        }
        try (SerialQueue queue = new SerialQueue()) {
            CountDownLatch block = new CountDownLatch(1), entered = new CountDownLatch(1), drained = new CountDownLatch(1);
            List<String> events = Collections.synchronizedList(new ArrayList<String>());
            queue.post(() -> { entered.countDown(); try { block.await(); } catch (InterruptedException e) { throw new AssertionError(e); } });
            await(entered);
            try {
                MainThreadHandoff.call(queue, () -> events.add("late apply"), () -> events.add("withdraw"), 50);
                throw new AssertionError("expected queued timeout");
            } catch (IOException expected) { }
            queue.post(() -> { events.add("next"); drained.countDown(); });
            block.countDown(); await(drained);
            check(events.equals(Arrays.asList("withdraw", "next")), "queued request escaped timeout cancellation: " + events);
            passed.add("pending timeout removes late creation");
        }
        try (SerialQueue queue = new SerialQueue()) {
            CountDownLatch block = new CountDownLatch(1), entered = new CountDownLatch(1), drained = new CountDownLatch(1);
            List<String> events = Collections.synchronizedList(new ArrayList<String>());
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    MainThreadHandoff.call(queue, () -> { entered.countDown(); block.await(); events.add("apply"); }, () -> events.add("withdraw"), 100);
                    result.set(new AssertionError("expected running timeout"));
                } catch (IOException expected) { } catch (Throwable error) { result.set(error); }
            });
            caller.start(); await(entered); caller.join(2000);
            check(!caller.isAlive() && result.get() == null, "running timeout result: " + result.get());
            queue.post(() -> { events.add("next"); drained.countDown(); });
            block.countDown(); await(drained);
            check(events.equals(Arrays.asList("apply", "withdraw", "next")), "cleanup reordered around new update: " + events);
            passed.add("running timeout withdraws before next creation");
        }
        try (SerialQueue queue = new SerialQueue()) {
            CountDownLatch cleaned = new CountDownLatch(1);
            IOException original = new IOException("fixture failure");
            try {
                MainThreadHandoff.call(queue, () -> { throw original; }, cleaned::countDown, 1000);
                throw new AssertionError("expected operation failure");
            } catch (IOException expected) { check(expected.getCause() == original, "original cause lost"); }
            await(cleaned); passed.add("operation error retains cause and queues cleanup");
        }
        System.out.println("PASS " + passed.size() + ": " + passed);
    }
}
