package dev.mihile.voxylodonly;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CorePolicyTest {
    @Test public void legacyJsonAndPartialProfilesKeepExplicitOldValues() {
        var gson = new com.google.gson.Gson();
        var config = gson.fromJson("{\"generationThreads\":2,\"cooldownTicks\":20,\"commitChunksPerTick\":1,\"commitBudgetMillis\":2,\"idleCommitBudgetMillis\":7,\"withPlayers\":{\"maxChunksPerSecond\":5}}", LodOnlyConfig.class);
        config.validate();
        assertEquals(2,config.policy(true).generationThreads());
        assertEquals(20,config.policy(true).cooldownTicks());
        assertEquals(1,config.policy(false).commitChunksPerTick());
        assertEquals(2,config.policy(true).commitBudgetMillis(),0);
        assertEquals(7,config.policy(false).commitBudgetMillis(),0);
        assertEquals(5,config.policy(true).maxChunksPerSecond());
        assertEquals(0,config.policy(false).maxChunksPerSecond());
    }
    @Test public void activityProfilesSelectIndependentLimitsAndPause() {
        var config = new LodOnlyConfig();
        config.withPlayers.enabled=false;
        config.withPlayers.generationThreads=1;
        config.withPlayers.commitBudgetMillis=1.0;
        config.withoutPlayers.generationThreads=4;
        config.withoutPlayers.maxChunksPerSecond=20;
        config.validate();
        assertFalse(config.policy(true).enabled());
        assertTrue(config.policy(false).enabled());
        assertEquals(1,config.policy(true).generationThreads());
        assertEquals(4,config.effectiveGenerationThreads());
        assertEquals(20,config.policy(false).maxChunksPerSecond());
        config.enabled=false;
        assertFalse(config.policy(false).enabled());
    }
    @Test public void malformedProfilesFailBeforeStartingWorkers() {
        var config = new LodOnlyConfig();
        config.withPlayers.maxChunksPerSecond=-1;
        assertThrows(IllegalArgumentException.class,config::validate);
        config.withPlayers.maxChunksPerSecond=0;
        config.withoutPlayers.commitBudgetMillis=Double.NaN;
        assertThrows(IllegalArgumentException.class,config::validate);
    }
    @Test public void rateAdmissionHasNoPauseBacklogAndAdaptsToModeSwitch() {
        var pacer = new ChunkPacer();
        assertTrue(pacer.admit(0,2));
        assertFalse(pacer.admit(499_999_999L,2));
        assertTrue(pacer.admit(500_000_000L,2));
        assertTrue(pacer.admit(10_000_000_000L,2));
        assertFalse(pacer.admit(10_000_000_000L,2));
        assertTrue(pacer.admit(10_000_000_000L,4));
        assertFalse(pacer.admit(10_249_999_999L,4));
        assertTrue(pacer.admit(10_250_000_000L,4));
        assertTrue(pacer.admit(10_250_000_000L,0));
        assertTrue(pacer.admit(10_250_000_000L,0));
    }
    @Test public void automaticWorkerBudgetReservesCapacityOnlyWhenNeeded() {
        assertEquals(4,LodOnlyConfig.automaticWorkers(4,false));
        assertEquals(3,LodOnlyConfig.automaticWorkers(4,true));
        assertEquals(1,LodOnlyConfig.automaticWorkers(1,true));
        assertEquals(8,LodOnlyConfig.automaticWorkers(16,false));
        assertEquals(8,LodOnlyConfig.automaticWorkers(16,true));
        LodOnlyConfig explicit=new LodOnlyConfig();explicit.generationThreads=2;
        assertEquals(2,explicit.activeGenerationThreads(false));
        assertEquals(2,explicit.activeGenerationThreads(true));
    }
    @Test public void idleCommitAllowanceDoesNotChangePlayerBudget() {
        LodOnlyConfig config=new LodOnlyConfig();
        assertEquals(10,config.activeCommitBudgetMillis(false),0);
        assertEquals(3,config.activeCommitBudgetMillis(true),0);
        config.idleCommitBudgetMillis=3;
        assertEquals(3,config.activeCommitBudgetMillis(false),0);
    }
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
    @Test public void largerTilesCoverNegativeAndBoundaryCoordinates() {
        for (int size : new int[]{8,16,32}) {
            TilePlan plan = new TilePlan(-3, 7, 9, size);
            Set<TilePlan.Tile> tiles = new HashSet<>(); plan.forEachRemaining(tile -> assertTrue(tiles.add(tile)));
            for (int x = -12; x <= 6; x++) for (int z = -2; z <= 16; z++)
                assertTrue(tiles.contains(new TilePlan.Tile(Math.floorDiv(x, size), Math.floorDiv(z, size))));
        }
    }
    @Test public void requestOriginAvoidsArtificialSplitsAndCoversPartialEdges() {
        for(int size:new int[]{4,8,16,32}) {
            TilePlan plan=new TilePlan(-3,7,9,size,true);
            Set<String> covered=new HashSet<>();
            while(plan.hasNext()) {
                var tile=plan.next();
                for(int z=plan.startZ(tile);z<plan.startZ(tile)+size;z++)
                    for(int x=plan.startX(tile);x<plan.startX(tile)+size;x++)
                        if(plan.includes(x,z)) assertTrue(covered.add(x+","+z));
            }
            assertEquals(19*19,covered.size());
        }
        TilePlan small=new TilePlan(128,128,8,32,true);
        var only=small.next();assertEquals(120,small.startX(only));assertEquals(120,small.startZ(only));
        assertFalse(small.hasNext());
    }
    @Test public void nestedGuardPreservesOuterScope() {
        try (var outer = GenerationGuard.enter()) {
            try (var inner = GenerationGuard.enter()) { assertTrue(GenerationGuard.active()); }
            assertThrows(IllegalStateException.class, () -> GenerationGuard.reject("nested write"));
        }
        assertFalse(GenerationGuard.active());
    }
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
    @Test public void replacementCheckpointIncludesLatestPositions() throws Exception {
        Path file = Files.createTempDirectory("lod-replace-test").resolve("completed.bin");
        CompletionLedger ledger = new CompletionLedger(file, 42);
        for (long pos = -8; pos <= 8; pos++) {
            ledger.add(pos); ledger.save();
            CompletionLedger reopened = new CompletionLedger(file, 42);
            assertEquals((int)pos+9, reopened.size()); assertTrue(reopened.contains(pos));
        }
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
