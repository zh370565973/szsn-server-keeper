package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionSlotTest {
    @Test
    void quiescentRequiresWorkersExitedAndZeroHeldBytes() {
        assertTrue(LoadAdmission.quiescent(true, 0));
        assertFalse(LoadAdmission.quiescent(true, 4096));
        assertFalse(LoadAdmission.quiescent(false, 0));
        assertEquals("SLOT_OCCUPIED", LoadAdmission.rejection(ExecutionStatus.PAUSED, true, 0));
        assertEquals("WORKERS_ACTIVE", LoadAdmission.rejection(ExecutionStatus.STOPPED, false, 0));
        assertEquals("LOAD_HELD", LoadAdmission.rejection(ExecutionStatus.STOPPED, true, 4096));
        assertNull(LoadAdmission.rejection(null, true, 0));
    }

    @Test
    void oneSessionOccupiesEveryActiveStateAndPauseBlocksTheNextStart() {
        ResourceLoadManager manager = new ResourceLoadManager();
        ResourceLoadManager.StartAttempt first = manager.start("exec-1");
        assertTrue(first.accepted());
        assertEquals(ExecutionStatus.STARTING, first.status());
        assertOccupied(manager, "exec-1", ExecutionStatus.STARTING);

        manager.markRunning("exec-1");
        assertOccupied(manager, "exec-1", ExecutionStatus.RUNNING);
        LoadWorker worker = manager.openWorker("exec-1");
        assertTrue(worker.live());
        assertTrue(worker.claim(1024));

        manager.beginPause("exec-1");
        assertOccupied(manager, "exec-1", ExecutionStatus.PAUSING);
        assertFalse(worker.live());
        assertFalse(worker.claim(1024));
        assertEquals(1024, worker.heldBytes());

        manager.pauseCompleted("exec-1");
        assertOccupied(manager, "exec-1", ExecutionStatus.PAUSED);
        assertEquals("exec-1", manager.active().orElseThrow().executionId());

        manager.requestStop("exec-1");
        assertOccupied(manager, "exec-1", ExecutionStatus.STOPPING);
        assertEquals(1024, manager.active().orElseThrow().heldBytes());

        worker.acknowledgeExit();
        assertTrue(manager.active().isPresent());
        assertEquals(1024, manager.active().orElseThrow().heldBytes());
        assertFalse(manager.start("exec-2").accepted());

        worker.dropReferences();
        assertTrue(manager.active().isEmpty());
        assertEquals(ExecutionStatus.STOPPED, manager.find("exec-1").orElseThrow().status());
        assertEquals(0, manager.find("exec-1").orElseThrow().heldBytes());

        ResourceLoadManager.StartAttempt second = manager.start("exec-2");
        assertTrue(second.accepted());
        assertEquals(ExecutionStatus.STARTING, second.status());
    }

    @Test
    void stopOfTheSameExecutionIsIdempotent() {
        ResourceLoadManager manager = new ResourceLoadManager();
        manager.start("exec-1");
        assertEquals(ExecutionStatus.STOPPED, manager.requestStop("exec-1").status());
        assertEquals(ExecutionStatus.STOPPED, manager.requestStop("exec-1").status());
        assertTrue(manager.active().isEmpty());
    }

    @Test
    void emergencyStopInvalidatesTheOldWorkerWithoutWaitingForTheOrdinaryQueue() throws Exception {
        ResourceLoadManager manager = new ResourceLoadManager();
        ResourceLoadManager.StartAttempt started = manager.start("exec-1");
        manager.markRunning("exec-1");
        LoadWorker worker = manager.openWorker("exec-1");
        assertTrue(worker.claim(4096));
        long oldGeneration = worker.generation();

        CountDownLatch inDatabase = new CountDownLatch(1);
        CountDownLatch releaseDatabase = new CountDownLatch(1);
        AtomicInteger committedWrites = new AtomicInteger();
        Thread database = new Thread(() -> manager.inOrdinaryQueue(() -> {
            inDatabase.countDown();
            try {
                if (!releaseDatabase.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("数据库占位未释放");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            committedWrites.incrementAndGet();
        }));
        database.start();
        assertTrue(inDatabase.await(1, TimeUnit.SECONDS));
        assertTrue(manager.ordinaryBusy());

        AtomicReference<ResourceLoadManager.EmergencyEffect> effect = new AtomicReference<>();
        Thread emergency = new Thread(() -> effect.set(manager.emergencyStop()));
        emergency.start();
        try {
            emergency.join(500);
            assertFalse(emergency.isAlive(), "急停不应等待普通队列或数据库提交");
            assertEquals(0, committedWrites.get());
            assertTrue(effect.get().generation() > oldGeneration);
            assertEquals("exec-1", effect.get().executionId());
            assertFalse(worker.live());
            assertFalse(worker.claim(4096));
            assertEquals(4096, worker.heldBytes());
            assertEquals(oldGeneration, worker.generation());
            assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
        } finally {
            releaseDatabase.countDown();
            database.join(1000);
        }
        assertFalse(manager.openWorker("exec-1").live());

        assertEquals(1, committedWrites.get());
        worker.acknowledgeExit();
        assertFalse(manager.start("exec-2").accepted());
        worker.dropReferences();
        assertTrue(manager.active().isEmpty());
        assertEquals(ExecutionStatus.SAFETY_STOPPED, manager.find("exec-1").orElseThrow().status());

        ResourceLoadManager.StartAttempt next = manager.start("exec-2");
        assertTrue(next.accepted());
        assertTrue(next.generation() > oldGeneration);
        LoadWorker replacement = manager.openWorker("exec-2");
        assertTrue(replacement.live());
        assertTrue(replacement.claim(2048));
        assertFalse(worker.claim(2048));
    }

    @Test
    void repeatedEmergencyKeepsTheOldGenerationFromClaiming() {
        ResourceLoadManager manager = new ResourceLoadManager();
        manager.start("exec-1");
        manager.markRunning("exec-1");
        LoadWorker worker = manager.openWorker("exec-1");
        assertTrue(worker.claim(512));
        ResourceLoadManager.EmergencyEffect first = manager.emergencyStop();
        ResourceLoadManager.EmergencyEffect second = manager.emergencyStop();
        assertTrue(second.generation() > first.generation());
        assertFalse(worker.live());
        assertFalse(worker.claim(512));
        assertEquals(512, worker.heldBytes());
        assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
    }

    @Test
    void emergencyWithoutAnActiveSessionStillPublishesANewGeneration() {
        ResourceLoadManager manager = new ResourceLoadManager();
        ResourceLoadManager.EmergencyEffect effect = manager.emergencyStop();
        assertNull(effect.executionId());
        ResourceLoadManager.StartAttempt started = manager.start("exec-1");
        assertTrue(started.accepted());
        assertEquals(effect.generation(), started.generation());
        assertTrue(manager.openWorker("exec-1").live());
    }

    @Test
    void concurrentStartsAdmitOnlyOneSession() throws Exception {
        ResourceLoadManager manager = new ResourceLoadManager();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResourceLoadManager.StartAttempt>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int id = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return manager.start("exec-" + id);
                }));
            }
            go.countDown();
            int accepted = 0;
            for (Future<ResourceLoadManager.StartAttempt> future : futures) {
                if (future.get(2, TimeUnit.SECONDS).accepted()) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
            assertTrue(manager.active().isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ordinaryChangesWaitBehindTheSerialQueue() throws Exception {
        ResourceLoadManager manager = new ResourceLoadManager();
        manager.start("exec-1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread blocker = new Thread(() -> manager.inOrdinaryQueue(() -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("普通队列未释放");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }));
        blocker.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        Thread follower = new Thread(() -> manager.markRunning("exec-1"));
        follower.start();
        try {
            follower.join(200);
            assertTrue(follower.isAlive());
            assertEquals(ExecutionStatus.STARTING, manager.active().orElseThrow().status());
        } finally {
            release.countDown();
        }
        follower.join(1000);
        blocker.join(1000);
        assertFalse(follower.isAlive());
        assertFalse(blocker.isAlive());
        assertEquals(ExecutionStatus.RUNNING, manager.active().orElseThrow().status());
    }

    @Test
    void staleGenerationCannotRemainRunnableAfterARacingEmergency() throws Exception {
        ResourceLoadManager manager = new ResourceLoadManager();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 40; round++) {
                int current = round;
                CountDownLatch go = new CountDownLatch(1);
                String id = "race-" + round;
                Future<ResourceLoadManager.StartAttempt> start = pool.submit(() -> {
                    go.await();
                    return manager.start(id);
                });
                Future<ResourceLoadManager.EmergencyEffect> stop = pool.submit(() -> {
                    go.await();
                    return manager.emergencyStop();
                });
                go.countDown();
                ResourceLoadManager.StartAttempt attempt = start.get(2, TimeUnit.SECONDS);
                ResourceLoadManager.EmergencyEffect effect = stop.get(2, TimeUnit.SECONDS);
                if (attempt.accepted() && attempt.generation() < effect.generation() && manager.active().isPresent()) {
                    assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
                    LoadWorker worker = manager.openWorker(id);
                    assertFalse(worker.live());
                    assertFalse(worker.claim(8));
                }
                if (manager.active().isPresent()
                        && manager.active().orElseThrow().status() == ExecutionStatus.STARTING) {
                    assertEquals(effect.generation(), manager.active().orElseThrow().generation());
                }
                release(manager);
                assertTrue(manager.active().isEmpty(), () -> "round " + current + " 槽位仍被占用");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingExecutionAndBlankIdAreRejected() {
        ResourceLoadManager manager = new ResourceLoadManager();
        assertThrows(IllegalArgumentException.class, () -> manager.start(" "));
        assertThrows(IllegalArgumentException.class, () -> manager.requestStop("missing"));
        manager.start("exec-1");
        assertThrows(IllegalStateException.class, () -> manager.beginPause("exec-1"));
    }

    private static void assertOccupied(ResourceLoadManager manager, String executionId, ExecutionStatus status) {
        ResourceLoadManager.StartAttempt intruder = manager.start("intruder-" + status);
        assertFalse(intruder.accepted());
        assertEquals("SLOT_OCCUPIED", intruder.reason());
        assertEquals(executionId, intruder.executionId());
        assertEquals(status, intruder.status());
    }

    private static void release(ResourceLoadManager manager) {
        if (manager.active().isEmpty()) {
            return;
        }
        String id = manager.active().orElseThrow().executionId();
        LoadWorker worker = manager.openWorker(id);
        if (worker.live()) {
            worker.dropReferences();
            worker.acknowledgeExit();
        }
        manager.requestStop(id);
    }
}
