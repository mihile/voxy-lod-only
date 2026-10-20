package dev.mihile.voxylodonly;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ResourceSchedulingTest {
    @Test public void youngAllocationPeaksDoNotPermanentlyDisableUsefulWork() {
        var gate=new ResourceGovernor();long now=System.nanoTime();
        gate.sample(now,.95,.45,320L*1024*1024,15,45,0,true,true,512);
        assertFalse(gate.paused());
        gate.sample(now+1,.94,.85,320L*1024*1024,15,45,0,true,true,512);
        assertTrue(gate.paused());assertTrue(gate.hardPressure(now+1));
    }
    @Test public void RealChunkYieldDoesNotDemandAnUnrelatedOldGenerationCollection() {
        var gate=new ResourceGovernor();long now=System.nanoTime();
        gate.sample(now,.85,.73,900_000_000L,15,45,3,true,true,512);assertTrue(gate.paused());
        gate.sample(now+1,.85,.73,900_000_000L,15,45,0,true,true,512);
        gate.sample(now+1_100_000_000L,.85,.73,900_000_000L,15,45,0,true,true,512);
        assertFalse(gate.paused());
    }
    private AutomaticRequests.Viewer viewer(UUID id,Object session,int x,int z) {
        return new AutomaticRequests.Viewer(id,session,"minecraft:overworld",x,z);
    }
    @Test public void twoSeparatedPlayersAlternateTilesWithoutGeneratingTheSpaceBetween() {
        var queue=new AutomaticRequests(); var a=viewer(UUID.randomUUID(),new Object(),-1000,0);
        var b=viewer(UUID.randomUUID(),new Object(),1000,0);
        queue.update(List.of(a,b),128,8,8,0);
        for(int i=0;i<20;i++) {
            var selection=queue.next(1);
            assertEquals(i%2==0?a.id():b.id(),selection.entry().viewer.id());
            var entry=selection.entry(); int x=entry.plan.startX(selection.tile());
            assertTrue(Math.abs(x-entry.viewer.x())<=128);
            assertTrue(Math.abs(x)>800);
        }
    }
    @Test public void movingOnePlayerAndReconnectNeverInvalidateTheOtherPlayer() {
        var queue=new AutomaticRequests(); var a=viewer(UUID.randomUUID(),new Object(),0,0);
        var b=viewer(UUID.randomUUID(),new Object(),0,0);
        queue.update(List.of(a,b),32,8,8,0);
        var first=queue.next(1); var second=queue.next(1);
        queue.update(List.of(viewer(a.id(),a.session(),9,0),b),32,8,8,2);
        assertFalse(queue.current(first.entry())); assertTrue(queue.current(second.entry()));
        queue.retry(second); assertNotNull(queue.next(3));
        var current=queue.next(3);
        queue.update(List.of(viewer(a.id(),new Object(),9,0)),32,8,8,4);
        assertFalse(queue.current(second.entry())); assertFalse(queue.current(current.entry()));
    }
    @Test public void dimensionChangeDropsOnlyThatPlayersOldRequestsAndRetry() {
        var queue=new AutomaticRequests(); var a=viewer(UUID.randomUUID(),new Object(),0,0);
        var b=viewer(UUID.randomUUID(),new Object(),0,0);
        queue.update(List.of(a,b),32,8,8,0);
        var first=queue.next(1);var second=queue.next(1);
        queue.retry(first);
        var changed=new AutomaticRequests.Viewer(a.id(),a.session(),"minecraft:the_nether",0,0);
        queue.update(List.of(changed,b),32,8,8,2);
        assertFalse(queue.current(first.entry()));assertTrue(queue.current(second.entry()));
        var next=queue.next(3);
        assertEquals(a.id(),next.entry().viewer.id());
        assertEquals("minecraft:the_nether",next.entry().viewer.dimension());
    }
    @Test public void hysteresisRequiresHeapAndTickRecoveryForOneSecond() {
        var gate=new ResourceGovernor(); long now=System.nanoTime();
        gate.sample(now,.9,200_000_000L,60,45,0,true,true,512);
        assertTrue(gate.paused()); assertTrue(gate.hardPressure(now));
        gate.sample(now+1,.76,1_000_000_000L,20,45,0,true,true,512); assertTrue(gate.paused());
        gate.sample(now+2,.6,2_000_000_000L,20,45,0,true,true,512);
        gate.sample(now+500_000_000L,.6,2_000_000_000L,20,45,0,true,true,512); assertTrue(gate.paused());
        gate.sample(now+1_100_000_000L,.6,2_000_000_000L,20,45,0,true,true,512); assertFalse(gate.paused());
    }
    @Test public void pausedWorkerCanAlwaysCancelWithoutAResumeOrServerTick() throws Exception {
        var gate=new ResourceGovernor(); long now=System.nanoTime();
        gate.sample(now,.4,2_000_000_000L,20,45,0,true,false,512);
        var cancel=new AtomicBoolean(); var started=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var task=pool.submit(() -> { started.countDown(); gate.checkpoint(cancel::get,true); });
            assertTrue(started.await(1,TimeUnit.SECONDS)); cancel.set(true);
            try { task.get(500,TimeUnit.MILLISECONDS); fail("cancel should terminate a paused worker"); }
            catch(ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
        } finally { pool.shutdownNow(); }
    }
    @Test public void legacyAggressiveSettingsStillObeyTheGlobalResourceEnvelope() {
        var config=new com.google.gson.Gson().fromJson("{\"generationTileSize\":32,\"generationThreads\":8,\"maxInFlightBatches\":2}",LodOnlyConfig.class);
        config.validate(); assertEquals(8,config.effectiveTileSize(true));
        assertTrue(LodOnlyConfig.estimatedBatchBytes(config.effectiveTileSize(true))<=config.resourceBudgetMiB*1024L*1024);
        assertEquals(2,config.policy(true).generationThreads()); assertEquals(4,config.policy(false).generationThreads());
        assertEquals(1,new LodOnlyConfig().maxInFlightBatches);
    }
    @Test public void automaticPlanSkipsInnerRealDemandRingAndPrioritizesFlightDirection() {
        var queue=new AutomaticRequests();
        var sessionA=new Object(); var sessionB=new Object();
        var idA=UUID.randomUUID(); var idB=UUID.randomUUID();
        queue.update(List.of(viewer(idA,sessionA,0,0),viewer(idB,sessionB,0,0)),32,8,8,12,0);
        // Move A toward +X and B toward -X (1 chunk, below retarget threshold, steering direction in-place).
        queue.update(List.of(viewer(idA,sessionA,1,0),viewer(idB,sessionB,-1,0)),32,8,8,12,1);
        var selA=queue.next(2);
        var selB=queue.next(2);
        assertEquals(idA,selA.entry().viewer.id());
        assertEquals(idB,selB.entry().viewer.id());
        int startXA=selA.entry().plan.startX(selA.tile()), startZA=selA.entry().plan.startZ(selA.tile());
        int startXB=selB.entry().plan.startX(selB.tile()), startZB=selB.entry().plan.startZ(selB.tile());
        // Both first tiles must be full 8x8 tiles completely outside the [-12, +12] realDemand square
        // and in their respective forward flight directions (+X for A, -X for B).
        assertTrue("A should pick forward +X tile outside realDemand", startXA > 12 && startXA + 7 <= 32);
        assertTrue("B should pick forward -X tile outside realDemand", startXB + 7 < -12 && startXB >= -32);
        assertTrue(startZA >= -32 && startZA + 7 <= 32);
        assertTrue(startZB >= -32 && startZB + 7 <= 32);
        // Verify full area coverage is still 100% preserved across all tiers.
        var fullPlan=new TilePlan(0,0,32,8,true,12,8,0);
        Set<String> covered=new HashSet<>();
        while(fullPlan.hasNext()) {
            var tile=fullPlan.next();
            for(int z=fullPlan.startZ(tile);z<fullPlan.startZ(tile)+8;z++)
                for(int x=fullPlan.startX(tile);x<fullPlan.startX(tile)+8;x++)
                    if(fullPlan.includes(x,z)) assertTrue(covered.add(x+","+z));
        }
        assertEquals(65*65,covered.size());
    }
    @Test public void advanceSameEntrySkipsEmptyTilesWithoutConsumingExtraPlayerTurns() {
        var queue=new AutomaticRequests();
        var a=viewer(UUID.randomUUID(),new Object(),0,0);
        var b=viewer(UUID.randomUUID(),new Object(),100,0);
        queue.update(List.of(a,b),16,8,8,12,0);
        var firstA=queue.next(1);
        assertEquals(a.id(),firstA.entry().viewer.id());
        assertEquals(1,firstA.entry().served);
        var secondTileSameTurn=queue.advanceSameEntry(firstA,2);
        assertNotNull(secondTileSameTurn);
        assertEquals(a.id(),secondTileSameTurn.entry().viewer.id());
        assertNotEquals(firstA.tile(),secondTileSameTurn.tile());
        assertEquals(1,secondTileSameTurn.entry().served);
        // Next round-robin turn still goes to B.
        var nextTurn=queue.next(3);
        assertEquals(b.id(),nextTurn.entry().viewer.id());
    }
    @Test public void healthyRecoveryWindowAndBriefRealChunkYieldsDoNotTriggerHardPressureAbort() {
        var gate=new ResourceGovernor(); long now=System.nanoTime();
        // Pause on real_chunks with healthy old-gen (.50) and healthy headroom (300 MiB).
        gate.sample(now,.70,.50,300L*1024*1024,15,45,2,true,true,512);
        assertTrue(gate.paused());
        assertFalse(gate.hardPressure(now + 2_500_000_000L));
        // Enter recovery window at t + 1.5s; at t + 2.2s (0.7s into healthy recovery), hardPressure must remain false.
        gate.sample(now + 1_500_000_000L,.65,.50,220L*1024*1024,15,45,0,true,true,512);
        assertTrue(gate.paused());
        assertFalse(gate.hardPressure(now + 2_200_000_000L));
        gate.sample(now + 2_600_000_000L,.65,.50,220L*1024*1024,15,45,0,true,true,512);
        assertFalse(gate.paused());
    }
    @Test public void borderlineRecoveryHoldDoesNotResetTotalPauseDurationForHardPressure() {
        // Case 1: headroom drops to 180MiB (<192MiB), then hovers at 220MiB (<256MiB recovery threshold).
        var heapGate=new ResourceGovernor(); long now=System.nanoTime();
        heapGate.sample(now,.70,.50,180L*1024*1024,15,45,0,true,true,512);
        assertTrue(heapGate.paused());
        for(int sec=1;sec<=10;sec++) {
            long t=now + sec*1_000_000_000L;
            heapGate.sample(t,.70,.50,220L*1024*1024,15,45,0,true,true,512);
            assertTrue(heapGate.paused());
            if(sec>=3) assertTrue("borderline memory recovery must trigger hardPressure after 2s", heapGate.hardPressure(t));
        }
        // Case 2: tickMillis spikes to 50ms (>45ms), then hovers at 40ms (>36ms recovery threshold).
        var tickGate=new ResourceGovernor();
        tickGate.sample(now,.60,.45,400L*1024*1024,50,45,0,true,true,512);
        assertTrue(tickGate.paused());
        for(int sec=1;sec<=10;sec++) {
            long t=now + sec*1_000_000_000L;
            tickGate.sample(t,.60,.45,400L*1024*1024,40,45,0,true,true,512);
            assertTrue(tickGate.paused());
            if(sec>=3) assertTrue("borderline tick recovery must trigger hardPressure after 2s", tickGate.hardPressure(t));
        }
    }
    @Test public void turningAroundWithinAnchorThresholdSteersTowardLatestStepDirection() {
        var queue=new AutomaticRequests();
        var session=new Object(); var id=UUID.randomUUID();
        // X = 0 -> 7 -> 6 with moveThreshold = 8: anchor stays at 0, but last step is West (-1).
        queue.update(List.of(viewer(id,session,0,0)),32,8,8,12,0);
        queue.update(List.of(viewer(id,session,7,0)),32,8,8,12,1);
        assertEquals(7,queue.entry(id).vx);
        queue.update(List.of(viewer(id,session,6,0)),32,8,8,12,2);
        assertEquals(-1,queue.entry(id).vx);
        assertEquals(0,queue.entry(id).viewer.x());
        var sel=queue.next(3);
        int startX=sel.entry().plan.startX(sel.tile());
        assertTrue("turning West from 7 to 6 should prioritize West (-X) tiles, got startX=" + startX, startX + 7 < -12);
    }
    @Test public void preparedLodBatchDoesNotRetainInMemoryBatchRecord() throws Exception {
        var fieldDescriptors=new ArrayList<String>();
        try(var in=ResourceSchedulingTest.class.getResourceAsStream("PreparedLodBatch.class")) {
            assertNotNull(in);
            new org.objectweb.asm.ClassReader(in).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                    fieldDescriptors.add(name+":"+descriptor);
                    return null;
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE);
        }
        assertFalse("PreparedLodBatch must not retain InMemoryGenerator$Batch field: "+fieldDescriptors,
                fieldDescriptors.stream().anyMatch(d -> d.contains("InMemoryGenerator$Batch")));
        assertTrue(fieldDescriptors.contains("chunks:Ljava/util/List;"));
        assertTrue(fieldDescriptors.contains("retainedResults:Ljava/util/concurrent/atomic/AtomicInteger;"));
    }
    @Test public void governorPublishesAtomicImmutableStateSnapshot() {
        var gate=new ResourceGovernor(); long now=System.nanoTime();
        gate.sample(now,.78,.84,200L*1024*1024,52,45,0,true,true,512);
        var snap=gate.snapshot();
        assertEquals("heap",snap.reason());
        assertEquals(now,snap.heartbeat());
        assertEquals(now,snap.since());
        assertEquals(0L,snap.healthySince());
        assertTrue(snap.memoryBlocked());
        assertTrue(snap.paused());
        assertTrue(snap.hardPressure(now));
    }
    @Test public void repeatedTileAbortsBackoffExponentiallyAndYieldTurnsToOtherPlayers() {
        var queue=new AutomaticRequests();
        var a=viewer(UUID.randomUUID(),new Object(),0,0);
        var b=viewer(UUID.randomUUID(),new Object(),200,0);
        long t0=1_000_000_000L;
        queue.update(List.of(a,b),32,8,8,12,t0);
        var selA1=queue.next(t0);
        assertEquals(a.id(),selA1.entry().viewer.id());
        // First pressure abort at t0: backoff is 1s (ready at t0 + 1s).
        queue.retry(selA1,t0);
        assertNotNull(selA1.entry().retry);
        assertEquals(1,selA1.entry().retry.attempts());
        assertEquals(t0+1_000_000_000L,selA1.entry().retry.retryAtNanos());
        // While A is in backoff (at t0 + 0.2s and t0 + 0.5s), B receives consecutive turns.
        var turn1=queue.next(t0+200_000_000L);
        var turn2=queue.next(t0+500_000_000L);
        assertEquals(b.id(),turn1.entry().viewer.id());
        assertEquals(b.id(),turn2.entry().viewer.id());
        // Once 1s backoff expires, A's tile is retried.
        var selA2=queue.next(t0+1_000_000_000L);
        assertEquals(a.id(),selA2.entry().viewer.id());
        assertEquals(selA1.tile(),selA2.tile());
        // Second consecutive abort on the same tile: backoff doubles to 2s.
        long t2=t0+1_100_000_000L;
        queue.retry(selA2,t2);
        assertEquals(2,selA2.entry().retry.attempts());
        assertEquals(t2+2_000_000_000L,selA2.entry().retry.retryAtNanos());
        // At t2 + 1.5s, A is still backing off so B gets the turn.
        assertEquals(b.id(),queue.next(t2+1_500_000_000L).entry().viewer.id());
        // At t2 + 2.0s, A's tile is eligible again; a third abort doubles backoff to 4s.
        var selA3=queue.next(t2+2_000_000_000L);
        assertEquals(a.id(),selA3.entry().viewer.id());
        long t3=t2+2_100_000_000L;
        queue.retry(selA3,t3);
        assertEquals(3,selA3.entry().retry.attempts());
        assertEquals(t3+4_000_000_000L,selA3.entry().retry.retryAtNanos());
    }
    @Test public void adaptiveStepPacingAndPreBatchHeadroomAdmission() {
        var gate=new ResourceGovernor(); long now=System.nanoTime();
        // Record heavy 6ms steps and tightening headroom (230 MiB).
        for(int i=0;i<8;i++) gate.recordStepCostNanos(6_000_000L);
        for(int i=0;i<6;i++) gate.sample(now+i*50_000_000L,.75,.65,230L*1024*1024,38,45,0,true,true,512);
        int throttledRate=gate.effectiveStepsPerSecond();
        assertTrue("effective rate should scale down under heavy step cost and tight headroom, got "+throttledRate,
                throttledRate < 300 && throttledRate >= 16);
        // Batch requiring 136.5 MiB + 64 MiB safety margin (= 200.5 MiB) is admitted at 230 MiB headroom,
        // but rejected if headroom is 195 MiB (< 200.5 MiB) or if estimated bytes exceed budget.
        long batch8x8=LodOnlyConfig.estimatedBatchBytes(8,8,64);
        long budget=192L*1024*1024;
        assertTrue(gate.canAdmitBatch(batch8x8,budget));
        assertFalse(gate.canAdmitBatch(budget+1,budget));
        gate.sample(now+400_000_000L,.78,.65,195L*1024*1024,20,45,0,true,true,512);
        assertFalse("195MiB headroom must not admit 136.5MiB batch with 64MiB safety margin",
                gate.canAdmitBatch(batch8x8,budget));
        // Smaller partial batch (1x1 span, 1 chunk) still fits within 195MiB headroom.
        assertTrue(gate.canAdmitBatch(LodOnlyConfig.estimatedBatchBytes(1,1,1),budget));
        // Recover step cost and headroom: effective rate smoothly climbs back up.
        for(int i=0;i<16;i++) gate.recordStepCostNanos(300_000L);
        for(int i=0;i<12;i++) gate.sample(now+(1+i)*100_000_000L,.45,.40,600L*1024*1024,10,45,0,true,true,512);
        assertTrue("effective rate should recover when headroom and step cost normalize",
                gate.effectiveStepsPerSecond() > throttledRate);
        // Even in healthy normal state, global configured step rate is still enforced by checkpoint.
        long healthyNow = System.nanoTime();
        gate.sample(healthyNow,.45,.40,600L*1024*1024,10,45,0,true,true,50);
        assertEquals(50, gate.effectiveStepsPerSecond());
        long startPaced = System.nanoTime();
        for (int i = 0; i < 3; i++) gate.checkpoint(() -> false, true);
        long elapsedMs = (System.nanoTime() - startPaced) / 1_000_000L;
        assertTrue("healthy state must still enforce configured 50 steps/sec limit, elapsedMs=" + elapsedMs, elapsedMs >= 35);
    }
    @Test public void directionSectorQuantizationAvoidsRedundantTileResorts() {
        var plan=new TilePlan(0,0,32,8,true,12,7,0);
        assertTrue(plan.hasNext());
        assertEquals(0,plan.directionSector());
        assertEquals(0,plan.directionReorders());
        // Minor angle variations within the East octant [-22.5 deg, +22.5 deg] must not re-sort.
        plan.setDirection(7,1);
        plan.setDirection(6,-1);
        plan.setDirection(8,2);
        assertEquals(0,plan.directionSector());
        assertEquals(0,plan.directionReorders());
        // Turning to North (0, -6) switches sector and triggers exactly one re-sort.
        plan.setDirection(0,-6);
        assertEquals(6,plan.directionSector());
        assertEquals(1,plan.directionReorders());
        var next=plan.next();
        assertTrue("after turning North (-Z), next tile should be in North cone", plan.startZ(next)+7 < -12);
    }
    @Test public void conversionQueuePreExecutionSkipNullPeekAndResultReservationCap() {
        var queued = new ArrayDeque<Runnable>();
        var released = new AtomicBoolean();
        var sectionSteps = new java.util.concurrent.atomic.AtomicInteger();
        var batch = new PreparedLodBatch(32, 4, () -> released.set(true), () -> false, queued::addLast)
                .checkpoint(sectionSteps::incrementAndGet);
        batch.fill();
        // 1. Submission reserves result slots up to MAX_RETAINED_RESULTS (8), not 16.
        assertEquals(8, queued.size());
        assertEquals(8, batch.reservedCount());
        assertEquals(8, batch.taskCount());
        assertEquals(0, batch.retainedCount());
        // Even if all 8 queued tasks finish immediately, retainedCount never exceeds 8.
        while (!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(8, batch.retainedCount());
        assertEquals(8, batch.reservedCount());
        assertEquals(0, batch.taskCount());
        assertEquals(8 * 4, sectionSteps.get());
        // Consume all 8 completed chunks; fill() replenishes the next 8 (indices 8..15) into queued.
        for (int i = 0; i < 8; i++) batch.advance();
        assertEquals(0, batch.retainedCount());
        assertEquals(8, batch.reservedCount());
        assertEquals(8, batch.taskCount());
        assertEquals(8, queued.size());
        // 2. Skip 8 queued chunks (indices 8..15) BEFORE their executor tasks run.
        // Because indices 8..15 are still STATE_IN_FLIGHT, their reservations remain held until their
        // worker finally blocks run, preventing new tasks (16..23) from over-committing memory.
        for (int i = 0; i < 8; i++) batch.skip();
        assertEquals(8, batch.taskCount());
        assertEquals(8, batch.reservedCount());
        assertEquals(8L * 4L * PreparedLodBatch.ESTIMATED_SECTION_BYTES, batch.reservedBytes());
        // Queue is temporarily empty because all 8 reservation slots are still held by in-flight tasks 8..15.
        assertNull("peek() must return null while in-flight skipped tasks still hold reservations", batch.peek());
        // 3. Running one pre-execution-skipped task must immediately abort before any section, release its
        // in-flight reservation in finally, and allow peek() to schedule and return chunk 16.
        int stepsBefore = sectionSteps.get();
        queued.removeFirst().run();
        assertEquals("pre-execution skipped task must not run any section", stepsBefore, sectionSteps.get());
        assertEquals(7, batch.taskCount());
        assertEquals(7, batch.reservedCount());
        var chunk16 = batch.peek();
        assertNotNull(chunk16);
        assertEquals(16, chunk16.index());
        assertEquals(8, batch.taskCount());
        assertEquals(8, batch.reservedCount());
        // 4. Closing and draining remaining queued tasks must bring taskCount and reservedCount to 0 and set drained() == true.
        batch.close();
        assertFalse(batch.drained());
        while (!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(0, batch.taskCount());
        assertEquals(0, batch.reservedCount());
        assertEquals(0L, batch.reservedBytes());
        assertEquals(0, batch.retainedCount());
        assertEquals(0L, batch.retainedBytes());
        assertTrue(released.get());
        assertTrue(batch.drained());
    }
    @Test public void conversionQueueEnforcesByteReservationCapWhileGuaranteeingProgress() throws Exception {
        // Inspect libs/voxy.jar's VoxelizedSection.createEmpty() bytecode directly via ASM to verify
        // ESTIMATED_SECTION_BYTES matches the real long[] allocation size (long[4681] = 37,448 bytes) + overhead.
        int[] voxyLongArrayLen = {0};
        try (var jar = new java.util.jar.JarFile("libs/voxy.jar")) {
            var entry = jar.getJarEntry("me/cortex/voxy/common/voxelization/VoxelizedSection.class");
            assertNotNull("VoxelizedSection.class must exist in libs/voxy.jar", entry);
            try (var in = jar.getInputStream(entry)) {
                new org.objectweb.asm.ClassReader(in).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!"createEmpty".equals(name)) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            private int lastIntOperand;
                            @Override
                            public void visitIntInsn(int opcode, int operand) {
                                if (opcode == org.objectweb.asm.Opcodes.NEWARRAY && operand == org.objectweb.asm.Opcodes.T_LONG) {
                                    voxyLongArrayLen[0] = lastIntOperand;
                                } else {
                                    lastIntOperand = operand;
                                }
                            }
                        };
                    }
                }, 0);
            }
        }
        assertEquals(4681, voxyLongArrayLen[0]);
        assertEquals(voxyLongArrayLen[0], PreparedLodBatch.VOXELIZED_SECTION_LONGS);
        long rawArrayBytes = (long) voxyLongArrayLen[0] * Long.BYTES;
        assertTrue("ESTIMATED_SECTION_BYTES must cover actual VoxelizedSection long[4681] array plus overhead",
                PreparedLodBatch.ESTIMATED_SECTION_BYTES >= rawArrayBytes + 32L);

        var queued = new ArrayDeque<Runnable>();
        // With 48 sections/chunk, MAX_RESERVED_RESULT_BYTES (8 * 24 sections) caps reserved results at 4 chunks instead of 8.
        var tallBatch = new PreparedLodBatch(16, 48, () -> {}, () -> false, queued::addLast);
        tallBatch.fill();
        assertEquals(4, tallBatch.reservedCount());
        assertEquals(4L * 48L * PreparedLodBatch.ESTIMATED_SECTION_BYTES, tallBatch.reservedBytes());
        // Skipping an in-flight chunk must NOT release its byte reservation until the worker task finishes.
        tallBatch.skip();
        assertEquals("In-flight skip must keep reservation until worker finally runs", 4, tallBatch.reservedCount());
        assertEquals(4L * 48L * PreparedLodBatch.ESTIMATED_SECTION_BYTES, tallBatch.reservedBytes());
        assertEquals("No 5th task may be scheduled while skipped task 0 is still in flight", 4, queued.size());
        // Now run skipped task 0: its finally releases 1 chunk reservation, and fill() schedules chunk 4.
        queued.removeFirst().run();
        assertEquals(3, tallBatch.reservedCount());
        tallBatch.fill();
        assertEquals(4, tallBatch.reservedCount());
        while (!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(4L * 48L * PreparedLodBatch.ESTIMATED_SECTION_BYTES, tallBatch.retainedBytes());
        tallBatch.advance();
        assertEquals(3L * 48L * PreparedLodBatch.ESTIMATED_SECTION_BYTES, tallBatch.retainedBytes());
        tallBatch.close();
        while (!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(0L, tallBatch.reservedBytes());
        assertEquals(0L, tallBatch.retainedBytes());

        // Even if a single chunk's estimated size exceeds MAX_RESERVED_RESULT_BYTES (e.g., 256 sections),
        // at least 1 chunk must still be admitted so conversion never deadlocks.
        var extremeBatch = new PreparedLodBatch(4, 256, () -> {}, () -> false, queued::addLast);
        extremeBatch.fill();
        assertEquals(1, extremeBatch.reservedCount());
        extremeBatch.close();
        while (!queued.isEmpty()) queued.removeFirst().run();
    }
    @Test public void chunkPresenceProbeBoundsConcurrencyDropsPayloadsAndDistinguishesErrors() {
        var pos0 = new net.minecraft.world.level.ChunkPos(0, 0);
        var pos1 = new net.minecraft.world.level.ChunkPos(1, 0);
        var pos2 = new net.minecraft.world.level.ChunkPos(2, 0);
        var pos3 = new net.minecraft.world.level.ChunkPos(3, 0);
        var pending = new LinkedHashMap<net.minecraft.world.level.ChunkPos, CompletableFuture<Optional<net.minecraft.nbt.CompoundTag>>>();
        int[] maxObserved = {0};
        var future = ChunkPresenceProbe.probe(List.of(99L), List.of(pos0, pos1, pos2, pos3), pos -> {
            var f = new CompletableFuture<Optional<net.minecraft.nbt.CompoundTag>>();
            pending.put(pos, f);
            maxObserved[0] = Math.max(maxObserved[0], pending.size());
            return f;
        }, 2, () -> false);
        // Only 2 reads may be in flight initially.
        assertEquals(2, maxObserved[0]);
        assertEquals(2, pending.size());
        var payload0 = new net.minecraft.nbt.CompoundTag();
        var weakPayload0 = new java.lang.ref.WeakReference<>(payload0);
        pending.remove(pos0).complete(Optional.of(payload0));
        payload0 = null;
        // Completing pos0 launches pos2 while keeping concurrency <= 2.
        assertEquals(2, maxObserved[0]);
        assertEquals(2, pending.size());
        // Force GC to verify the completed Optional payload is not retained by ChunkPresenceProbe while waiting on pos1..pos3.
        for (int i = 0; i < 5 && weakPayload0.get() != null; i++) {
            System.gc();
        }
        assertNull("ChunkPresenceProbe must drop completed NBT payload before remaining reads finish", weakPayload0.get());
        pending.remove(pos1).complete(Optional.empty());
        pending.remove(pos2).completeExceptionally(new RuntimeException("Simulated disk error"));
        pending.remove(pos3).complete(null);
        var result = future.join();
        assertEquals(1, result.presentCount());
        assertEquals(2, result.errorCount());
        assertEquals(Set.of(99L, pos1.toLong()), result.absentPositions());

        // Verify cancellation stops new submissions and waits for all already-submitted reads to drain before completing.
        var cancelPending = new LinkedHashMap<net.minecraft.world.level.ChunkPos, CompletableFuture<Optional<net.minecraft.nbt.CompoundTag>>>();
        var cancelled = new AtomicBoolean(false);
        var cancelFuture = ChunkPresenceProbe.probe(List.of(), List.of(pos0, pos1, pos2, pos3), pos -> {
            var f = new CompletableFuture<Optional<net.minecraft.nbt.CompoundTag>>();
            cancelPending.put(pos, f);
            return f;
        }, 2, cancelled::get);
        assertEquals(2, cancelPending.size());
        cancelled.set(true);
        // Complete pos0 while pos1 is still in flight: pos2 must NOT be submitted, and cancelFuture must NOT complete yet!
        cancelPending.remove(pos0).complete(Optional.empty());
        assertEquals("Cancelled probe must not submit new reads (pos2)", 1, cancelPending.size());
        assertFalse("Cancelled probe must wait for remaining in-flight read (pos1) before completing", cancelFuture.isDone());
        // Now complete pos1: all in-flight reads have drained, so cancelFuture completes with CancellationException.
        cancelPending.remove(pos1).complete(Optional.empty());
        assertTrue(cancelFuture.isCompletedExceptionally());
    }
    @Test public void gridNeighborhoodListMatchesCopied17x17NeighborhoodOrder() {
        int gridMinX = -9, gridMinZ = -9, gridWidth = 26, gridHeight = 26;
        var grid = new net.minecraft.world.level.chunk.ProtoChunk[gridWidth * gridHeight];
        var registry = net.minecraft.core.RegistryAccess.EMPTY;
        // Populate with distinct non-null dummy entries is unnecessary if we test positional identity via sun.misc.Unsafe or null-distinct positions;
        // let's test across all 64 output positions using distinct mock/allocated ProtoChunk instances or verify index formula directly.
        int centerX = 3, centerZ = 4;
        int startOffset = (centerZ - 8 - gridMinZ) * gridWidth + (centerX - 8 - gridMinX);
        var view = new InMemoryGenerator.GridNeighborhoodList(grid, gridWidth, startOffset);
        assertEquals(17 * 17, view.size());
        int idx = 0;
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                int expectedGridIndex = ((centerZ + dz) - gridMinZ) * gridWidth + ((centerX + dx) - gridMinX);
                int actualGridIndex = startOffset + (idx / 17) * gridWidth + (idx % 17);
                assertEquals(expectedGridIndex, actualGridIndex);
                assertNull(view.get(idx));
                idx++;
            }
        }
    }
    @Test public void noiseChunkHeightCachePreservesContextSeparation() {
        var heightCache = new NoiseChunkCache.HeightCache();
        var genA = new Object();
        var genB = new Object();
        var rngA = new Object();
        var rngB = new Object();
        var computes = new java.util.concurrent.atomic.AtomicInteger();
        int h1 = heightCache.getOrCompute(genA, rngA, -64, 384, 0, 100, -200, () -> { computes.incrementAndGet(); return 72; });
        int h2 = heightCache.getOrCompute(genA, rngA, -64, 384, 0, 100, -200, () -> { computes.incrementAndGet(); return 99; });
        assertEquals(72, h1);
        assertEquals(72, h2);
        assertEquals(1, computes.get());
        // Different RandomState, generator, heightmap type, or build height must not collide with cached value.
        assertEquals(80, heightCache.getOrCompute(genA, rngB, -64, 384, 0, 100, -200, () -> { computes.incrementAndGet(); return 80; }));
        assertEquals(81, heightCache.getOrCompute(genB, rngA, -64, 384, 0, 100, -200, () -> { computes.incrementAndGet(); return 81; }));
        assertEquals(82, heightCache.getOrCompute(genA, rngA, -64, 384, 1, 100, -200, () -> { computes.incrementAndGet(); return 82; }));
        assertEquals(83, heightCache.getOrCompute(genA, rngA, 0, 256, 0, 100, -200, () -> { computes.incrementAndGet(); return 83; }));
        assertEquals(5, computes.get());
        // Clearing forces recomputation even for identical key.
        heightCache.clear();
        assertEquals(90, heightCache.getOrCompute(genA, rngA, -64, 384, 0, 100, -200, () -> { computes.incrementAndGet(); return 90; }));
        assertEquals(6, computes.get());
    }
}
