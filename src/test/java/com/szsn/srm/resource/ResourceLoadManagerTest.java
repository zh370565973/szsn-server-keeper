package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResourceLoadManagerTest {
    @Test
    void onlyOneOccupyingStatusAtATime() {
        for (ExecutionStatus status : List.of(
                ExecutionStatus.STARTING,
                ExecutionStatus.RUNNING,
                ExecutionStatus.PAUSING,
                ExecutionStatus.PAUSED,
                ExecutionStatus.STOPPING)) {
            ResourceLoadManager manager = new ResourceLoadManager();
            SessionClaim claim = occupy(manager, status);
            SessionView active = manager.active().orElseThrow();
            assertEquals(status, active.status());
            assertEquals("exec-1", active.executionId());
            SessionRejectedException same = assertThrows(SessionRejectedException.class, () -> manager.claim("exec-1"));
            SessionRejectedException other = assertThrows(SessionRejectedException.class, () -> manager.claim("exec-2"));
            assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED, same.reason());
            assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED, other.reason());
            assertEquals("exec-1", manager.active().orElseThrow().executionId());
            if (status == ExecutionStatus.STOPPING) {
                assertFalse(claim.permit().valid());
            } else {
                assertTrue(claim.permit().valid());
            }
        }
    }

    @Test
    void pauseKeepsTheSlotAndResumeDoesNotAdmitAnotherSession() {
        ResourceLoadManager manager = new ResourceLoadManager();
        SessionClaim claim = occupy(manager, ExecutionStatus.PAUSED);
        long generation = claim.generation();
        manager.rejectResume("exec-1");
        assertEquals(ExecutionStatus.PAUSED, manager.active().orElseThrow().status());
        manager.acceptResume("exec-1");
        assertEquals(ExecutionStatus.STARTING, manager.active().orElseThrow().status());
        assertEquals(generation, claim.permit().issuedGeneration());
        assertTrue(claim.permit().valid());
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED,
                assertThrows(SessionRejectedException.class, () -> manager.claim("exec-2")).reason());
    }

    @Test
    void emergencyStopInvalidatesOldWorkerWithoutWaitingForTheJournal() throws Exception {
        AtomicInteger journalCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResourceLoadManager manager = new ResourceLoadManager((executionId, status) -> {
            journalCalls.incrementAndGet();
            if (status == ExecutionStatus.RUNNING) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        });
        SessionClaim claim = manager.claim("exec-1");
        AtomicBoolean workerSawValid = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            workerSawValid.set(claim.permit().valid());
            while (claim.permit().valid()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
        AtomicReference<Throwable> ordinaryFailure = new AtomicReference<>();
        Thread ordinary = new Thread(() -> {
            try {
                manager.markRunning("exec-1");
                ordinaryFailure.set(new AssertionError("急停后不应发布 RUNNING"));
            } catch (Throwable throwable) {
                ordinaryFailure.set(throwable);
            }
        });
        ExecutorService emergency = Executors.newSingleThreadExecutor();
        try {
            ordinary.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long seenDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (!workerSawValid.get() && System.nanoTime() < seenDeadline) {
                Thread.sleep(5);
            }
            assertTrue(workerSawValid.get());
            int callsWhileBlocked = journalCalls.get();
            Future<Long> stopped = emergency.submit(manager::emergencyStop);
            long nextGeneration = stopped.get(1, TimeUnit.SECONDS);
            assertTrue(nextGeneration > claim.generation());
            assertEquals(callsWhileBlocked, journalCalls.get());
            assertTrue(ordinary.isAlive());
            assertEquals(1, release.getCount());
            assertFalse(claim.permit().valid());
            assertTrue(workerSawValid.get());
            worker.join(1_000);
            assertFalse(worker.isAlive());
            assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
        } finally {
            release.countDown();
            worker.interrupt();
            ordinary.join(2_000);
            worker.join(1_000);
            emergency.shutdownNow();
        }
        assertInstanceOf(SessionRejectedException.class, ordinaryFailure.get());
        assertEquals(SessionRejectedException.Reason.EMERGENCY_PREEMPTED,
                ((SessionRejectedException) ordinaryFailure.get()).reason());
        assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
    }

    @Test
    void newClaimWaitsForWorkerExitAndZeroHeldBytes() {
        ResourceLoadManager manager = new ResourceLoadManager();
        SessionClaim first = manager.claim("exec-a");
        manager.markRunning("exec-a");
        manager.observeHeldBytes("exec-a", 4096);
        manager.emergencyStop();
        assertFalse(first.permit().valid());
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED,
                assertThrows(SessionRejectedException.class, () -> manager.claim("exec-b")).reason());
        manager.confirmWorkerExit(first.permit());
        assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
        assertEquals(4096, manager.active().orElseThrow().heldBytes());
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED,
                assertThrows(SessionRejectedException.class, () -> manager.claim("exec-b")).reason());
        manager.observeHeldBytes("exec-a", 0);
        assertTrue(manager.active().isEmpty());
        assertEquals(ExecutionStatus.SAFETY_STOPPED, manager.lastFinished().orElseThrow().status());
        SessionClaim second = manager.claim("exec-b");
        assertTrue(second.permit().valid());
        assertFalse(first.permit().valid());
        assertTrue(second.generation() > first.generation());
    }

    @Test
    void userStopIsIdempotentAndReleasesAfterWorkerExit() {
        ResourceLoadManager manager = new ResourceLoadManager();
        SessionClaim claim = manager.claim("exec-1");
        manager.markRunning("exec-1");
        manager.requestStop("exec-1", StopKind.USER);
        manager.requestStop("exec-1", StopKind.USER);
        assertFalse(claim.permit().valid());
        assertEquals(ExecutionStatus.STOPPING, manager.active().orElseThrow().status());
        assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED,
                assertThrows(SessionRejectedException.class, () -> manager.claim("exec-2")).reason());
        manager.confirmWorkerExit(claim.permit());
        assertTrue(manager.active().isEmpty());
        assertEquals(ExecutionStatus.STOPPED, manager.lastFinished().orElseThrow().status());
        manager.requestStop("exec-1", StopKind.USER);
        SessionClaim next = manager.claim("exec-2");
        assertTrue(next.permit().valid());
        assertFalse(claim.permit().valid());
    }

    @Test
    void concurrentClaimsAdmitOnlyOne() throws Exception {
        ResourceLoadManager manager = new ResourceLoadManager();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                int number = index;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return manager.claim("exec-" + number);
                    } catch (SessionRejectedException exception) {
                        return exception.reason();
                    }
                }));
            }
            start.countDown();
            int wins = 0;
            for (Future<Object> future : futures) {
                Object result = future.get(5, TimeUnit.SECONDS);
                if (result instanceof SessionClaim) {
                    wins++;
                } else {
                    assertEquals(SessionRejectedException.Reason.SLOT_OCCUPIED, result);
                }
            }
            assertEquals(1, wins);
            assertTrue(manager.active().isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    private static SessionClaim occupy(ResourceLoadManager manager, ExecutionStatus status) {
        SessionClaim claim = manager.claim("exec-1");
        switch (status) {
            case STARTING -> {
            }
            case RUNNING -> manager.markRunning("exec-1");
            case PAUSING -> {
                manager.markRunning("exec-1");
                manager.beginPause("exec-1");
            }
            case PAUSED -> {
                manager.markRunning("exec-1");
                manager.beginPause("exec-1");
                manager.completePause("exec-1");
            }
            case STOPPING -> {
                manager.markRunning("exec-1");
                manager.requestStop("exec-1", StopKind.USER);
            }
            default -> throw new AssertionError(status);
        }
        return claim;
    }
}
