package dev.mihile.voxylodonly;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CorePolicyTest {
    @Test public void negativeCoordinatesUseFloorDivision() {
        TilePlan plan = new TilePlan(-1, -1, 0);
        assertEquals(new TilePlan.Tile(-1, -1), plan.next());
        assertFalse(plan.hasNext());
        assertTrue(plan.includes(-1, -1));
        assertFalse(plan.includes(0, -1));
    }
    @Test public void boundedPlanCoversRequestedAreaExactlyOnce() {
        TilePlan plan = new TilePlan(-3, 7, 9);
        Set<TilePlan.Tile> tiles = new HashSet<>();
        plan.forEachRemaining(tile -> assertTrue(tiles.add(tile)));
        for (int x = -12; x <= 6; x++) for (int z = -2; z <= 16; z++)
            assertTrue(tiles.contains(new TilePlan.Tile(Math.floorDiv(x, 4), Math.floorDiv(z, 4))));
    }
    @Test(expected = IllegalArgumentException.class) public void excessiveRadiusRejected() { new TilePlan(0, 0, 129); }
    @Test public void ledgerSurvivesRestartAndRejectsDifferentSeed() throws Exception {
        Path file = Files.createTempDirectory("lod-ledger-test").resolve("completed.bin");
        CompletionLedger first = new CompletionLedger(file, 42);
        first.add(-123); first.add(999); first.add(999); first.save();
        CompletionLedger reopened = new CompletionLedger(file, 42);
        assertEquals(2, reopened.size()); assertTrue(reopened.contains(-123));
        assertEquals(0, new CompletionLedger(file, 43).size());
    }
    @Test(expected = java.io.IOException.class) public void malformedLedgerFailsClosed() throws Exception {
        Path file = Files.createTempFile("bad-lod-ledger", ".bin");
        Files.write(file, new byte[]{0, 1, 2}); new CompletionLedger(file, 42);
    }
    @Test public void liveWorldGuardIsScopedAndDoesNotAffectOtherThreads() throws Exception {
        assertFalse(GenerationGuard.active());
        try (var guard = GenerationGuard.enter()) {
            assertThrows(IllegalStateException.class, () -> GenerationGuard.reject("write"));
            java.util.concurrent.atomic.AtomicBoolean otherActive = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread normal = new Thread(() -> otherActive.set(GenerationGuard.active()));
            normal.start(); normal.join();
            assertFalse(otherActive.get());
        }
        assertFalse(GenerationGuard.active()); GenerationGuard.reject("normal gameplay");
    }
}
