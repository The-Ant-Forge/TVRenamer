package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkPlan} — the thread-safe unit counter driving the
 * unified progress bar.  Round-4 finding #31: the entire progress system
 * depended on this class with zero direct coverage.
 */
class WorkPlanTest {

    @Test
    void constructorClampsNegativeTotal() {
        assertEquals(0, new WorkPlan(-5).getTotal());
        assertEquals(0, new WorkPlan(0).getTotal());
        assertEquals(7, new WorkPlan(7).getTotal());
    }

    @Test
    void tickIncrementsCompleted() {
        WorkPlan plan = new WorkPlan(3);
        plan.tick();
        plan.tick();
        assertEquals(2, plan.getCompleted());
        assertEquals(3, plan.getTotal());
    }

    @Test
    void retractShrinksTotalAndFloorsAtZero() {
        WorkPlan plan = new WorkPlan(5);
        plan.retract(2);
        assertEquals(3, plan.getTotal());
        plan.retract(10);
        assertEquals(0, plan.getTotal(), "retract must floor at zero");
        plan.retract(-1);
        assertEquals(0, plan.getTotal(), "negative retract is a no-op");
    }

    @Test
    void completeAllSnapsCompletedToTotal() {
        WorkPlan plan = new WorkPlan(10);
        plan.tick();
        plan.completeAll();
        assertEquals(plan.getTotal(), plan.getCompleted());

        // Also after a retract shrank the total below completed.
        WorkPlan shrunk = new WorkPlan(5);
        shrunk.tick();
        shrunk.tick();
        shrunk.tick();
        shrunk.retract(4);   // total 1, completed 3
        shrunk.completeAll();
        assertEquals(shrunk.getTotal(), shrunk.getCompleted());
    }

    @Test
    void listenerFiresOnTickRetractAndCompleteAll() {
        WorkPlan plan = new WorkPlan(3);
        AtomicInteger fired = new AtomicInteger();
        plan.setProgressListener(fired::incrementAndGet);

        plan.tick();
        plan.retract(1);
        plan.completeAll();

        assertEquals(3, fired.get());
    }

    @Test
    void throwingListenerDoesNotPropagate() {
        WorkPlan plan = new WorkPlan(1);
        plan.setProgressListener(() -> {
            throw new IllegalStateException("listener bug");
        });
        plan.tick(); // must not throw
        assertEquals(1, plan.getCompleted());
    }

    @Test
    void concurrentTicksAreNotLost() throws InterruptedException {
        final int threads = 8;
        final int ticksPerThread = 500;
        WorkPlan plan = new WorkPlan(threads * ticksPerThread);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < ticksPerThread; j++) {
                        plan.tick();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS),
            "tick threads must finish promptly");
        assertEquals(threads * ticksPerThread, plan.getCompleted());
    }
}
