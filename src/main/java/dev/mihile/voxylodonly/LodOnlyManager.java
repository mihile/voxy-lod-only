package dev.mihile.voxylodonly;

import com.dripps.voxyserver.server.ServerLodEngine;
import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.dimension.DimensionType;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LodOnlyManager {
    private final MinecraftServer server;
    private final ServerLodEngine engine;
    private LodOnlyConfig config, pendingConfig;
    private boolean pendingAutomatic;
    /** Runtime on/off chosen by a command; survives /voxylod reload but not a server restart. */
    private Boolean automaticOverride;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Voxy LOD Only coordinator"); thread.setDaemon(true); thread.setPriority(4); return thread;
    });
    private final ThreadPoolExecutor stages;
    private final ChunkPacer chunkPacer = new ChunkPacer();
    private final ResourceGovernor governor = new ResourceGovernor();
    private final java.lang.management.MemoryPoolMXBean oldPool=java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .stream().filter(pool -> pool.getName().contains("Old") || pool.getName().contains("Tenured")).findFirst().orElse(null);
    private final AutomaticRequests automaticRequests = new AutomaticRequests();
    private record WorkKey(String dimension,long position,boolean structures) {}
    private final Set<WorkKey> reservations = new HashSet<>();
    private long inFlightDuplicateSkips, completedDuplicateSkips, cancellations, discardedChunks;
    private long realDemandSkips, overtakenBatches, discardedGenerationNanos, admissionYields, scanBudgetYields;
    private long pressureAborts, obsoleteTileCancellations, manualCancellations;
    private final AtomicLong readErrorSkips = new AtomicLong();
    private int nearChunkBacklog;
    private LodOnlyConfig.Policy policy;
    private Boolean playersPresent;
    private boolean chunkAdmitted;
    private final Map<ServerLevel, Set<Long>> realChunks = new IdentityHashMap<>();
    private record LedgerKey(ServerLevel level,boolean structures) {}
    private final Map<LedgerKey, CompletionLedger> ledgers = new HashMap<>();
    private Job job;
    private record PendingBatch(CompletableFuture<InMemoryGenerator.Batch> future, int requested) {}
    private record Request(ServerLevel level, GenerationArea area,int x,int z,int radius) {
        Request(ServerLevel level,GenerationArea area) { this(level,area,0,0,0); }
        static Request of(Job job) { return new Request(job.level,job.plan.blockArea(),job.plan.centerX,job.plan.centerZ,job.plan.radius); }
        Job create(int tileSize) { return area==null?new Job(level,x,z,radius,null,tileSize):new Job(level,area,tileSize); }
    }
    private Request queued;
    private boolean manualPaused;
    private final Deque<PendingBatch> pending = new ArrayDeque<>();
    private InMemoryGenerator.Batch ready;
    private PreparedLodBatch preparation;
    private int chunkIndex, sectionIndex;
    private boolean stopping, automatic;
    private long generated, skipped, sections, batches, ticks, nextBatchTick;
    private String lastError = "none";
    private long commitStart;
    private int commitTicks, conversionWaits, budgetYields, capYields;

    private static final class Job {
        final ServerLevel level;
        final TilePlan plan;
        final int tileSize;
        final net.minecraft.server.level.ServerPlayer owner;
        long generatedBefore, skippedBefore;
        final long total;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long created = System.nanoTime();
        AutomaticRequests.Selection selection;
        boolean tileDispatched;
        Job(ServerLevel level, int x, int z, int radius, net.minecraft.server.level.ServerPlayer owner, int tileSize) {
            this.level = level;
            boolean overworld=level.dimension()==net.minecraft.world.level.Level.OVERWORLD;
            // Preserve the smaller fixed-grid context outside the cropped Overworld domain.
            this.tileSize=overworld?tileSize:Math.min(tileSize,8);
            this.plan = new TilePlan(x, z, radius, this.tileSize, overworld); this.owner = owner;
            this.total = plan.totalChunks();
        }
        Job(ServerLevel level, GenerationArea area, int tileSize) {
            this.level=level; this.owner=null;
            boolean overworld=level.dimension()==net.minecraft.world.level.Level.OVERWORLD;
            this.tileSize=overworld?tileSize:Math.min(tileSize,8);
            this.plan=new TilePlan(area,this.tileSize,overworld);
            this.total=plan.totalChunks();
        }
    }
    private int tileSize() { return config.effectiveTileSize(!server.getPlayerList().getPlayers().isEmpty()); }

    public LodOnlyManager(MinecraftServer server, ServerLodEngine engine, LodOnlyConfig config) {
        this.server = server; this.engine = engine; this.config = config; this.automatic = config.automatic;
        int generationThreads = config.effectiveGenerationThreads();
        stages = (ThreadPoolExecutor)Executors.newFixedThreadPool(generationThreads, r -> {
            Thread thread = new Thread(r, "Voxy LOD Only terrain"); thread.setDaemon(true); thread.setPriority(4); return thread;
        });
        VoxyLodOnly.LOGGER.info("LOD scheduler: terrainThreads={}, tileSize={}, inFlight={}, commitBudget={}ms",
                generationThreads, config.generationTileSize, config.maxInFlightBatches, config.commitBudgetMillis);
    }
    public boolean generate(ServerLevel level, int x, int z, int radius) {
        if (!config.enabled || stopping || queued != null || job != null || !pending.isEmpty() || ready != null || preparation != null) return false;
        realChunks.clear();
        job = new Job(level, x, z, radius, null, tileSize());
        job.generatedBefore = generated; job.skippedBefore=skipped; manualPaused=false; nextBatchTick=ticks; return true;
    }
    public boolean startArea(ServerLevel level, GenerationArea area) {
        if (!config.enabled || stopping || queued != null || (job != null && job.owner == null && !job.cancelled.get())) return false;
        // Explicit areas take over automatic work only after its futures and conversion leases drain.
        cancel(); automatic=false; automaticOverride=false; if (pendingConfig!=null) pendingAutomatic=false;
        manualPaused=false; queued=new Request(level,area);
        return true;
    }
    public boolean pause() {
        if ((job==null || job.cancelled.get()) && queued==null) return false;
        manualPaused=true; return true;
    }
    public boolean resume() { if (!manualPaused) return false; manualPaused=false; return true; }
    public void stopWork() { automatic(false); cancel(); }
    public void automatic(boolean enabled) {
        automatic=enabled; automaticOverride=enabled; if (pendingConfig!=null) pendingAutomatic=enabled;
        if (!enabled && queued==null && job!=null && job.owner!=null && !job.cancelled.get()) cancel();
    }
    public void radius(int radius) {
        config=config.withRadius(radius);
        if (pendingConfig!=null) pendingConfig=pendingConfig.withRadius(radius);
        if (job!=null && job.owner!=null && !job.cancelled.get()) { manualCancellations++; cancelActiveJob(); }
        nextBatchTick=ticks;
    }
    public void reload(LodOnlyConfig replacement) {
        if (stopping) throw new IllegalStateException("Server is stopping");
        replacement.validate();
        if (queued==null && job!=null && job.owner==null && !job.cancelled.get()) queued=Request.of(job);
        pendingConfig=replacement;
        pendingAutomatic=automaticOverride!=null?automaticOverride:queued==null?replacement.automatic:automatic;
        if (job!=null && !job.cancelled.get()) manualCancellations++;
        cancelActiveJob();
    }
    private void applyReloadIfDrained() {
        if (pendingConfig==null || job!=null || !pending.isEmpty() || ready!=null || preparation!=null) return;
        config=pendingConfig; pendingConfig=null; automatic=pendingAutomatic;
        automaticRequests.clear();
        playersPresent=null; nextBatchTick=ticks;
        VoxyLodOnly.LOGGER.info("LOD config applied: radiusChunks={}, enabled={}, automatic={}, structures={}, tileSize={}",
                config.radiusChunks,config.enabled,automatic,config.structures,config.generationTileSize);
    }
    public void markRealChunk(ServerLevel level, ChunkPos pos) {
        // Only in-flight output needs the visit fence; never retain an entire exploration area's history.
        if (job != null && job.level == level && reservations.contains(
                new WorkKey(level.dimension().location().toString(),pos.toLong(),config.structures)))
            realChunks.computeIfAbsent(level, unused -> new HashSet<>()).add(pos.toLong());
    }
    private boolean real(ServerLevel level, ChunkPos pos) {
        return realChunks.getOrDefault(level, Set.of()).contains(pos.toLong())
                || level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
    }
    private boolean realDemand(ServerLevel level,ChunkPos pos) {
        int radius=server.getPlayerList().getViewDistance()+2;
        for(var player:server.getPlayerList().getPlayers()) {
            if(player.serverLevel()!=level) continue;
            var center=player.chunkPosition();
            if(Math.abs((long)pos.x-center.x)<=radius && Math.abs((long)pos.z-center.z)<=radius) return true;
        }
        return false;
    }
    private CompletionLedger ledger(ServerLevel level) {
        CompletionLedger existing=ledgers.get(new LedgerKey(level,config.structures));
        if (existing!=null) return existing;
        // Load both modes once, so replacing preview data invalidates an older opposite-mode checkpoint.
        for (boolean structures:new boolean[]{false,true}) ledgers.computeIfAbsent(new LedgerKey(level,structures), key -> {
            String filename = Base64.getUrlEncoder().withoutPadding().encodeToString(key.level().dimension().location().toString().getBytes(StandardCharsets.UTF_8));
            try { return new CompletionLedger(server.getWorldPath(LevelResource.ROOT).resolve("voxy_lod_only").resolve(filename + (key.structures() ? "-structures.bin" : "-terrain.bin")), key.level().getSeed()); }
            catch (IOException e) { throw new IllegalStateException("Cannot read LOD checkpoint", e); }
        });
        return ledgers.get(new LedgerKey(level,config.structures));
    }

    public void tick() {
        if (stopping) return;
        ticks++;
        try {
            applyReloadIfDrained();
            boolean occupied = !server.getPlayerList().getPlayers().isEmpty();
            policy = config.policy(occupied);
            refreshAutomaticRequests();
            if (ticks%10==1 || nearChunkBacklog>0) {
                nearChunkBacklog=0;
                for(var player:server.getPlayerList().getPlayers()) {
                    var pos=player.chunkPosition(); var source=player.serverLevel().getChunkSource();
                    for(int z=-1;z<=1;z++) for(int x=-1;x<=1;x++)
                        if(source.getChunkNow(pos.x+x,pos.z+z)==null) nearChunkBacklog++;
                    nearChunkBacklog+=Math.max(0,source.getPendingTasksCount()-32);
                }
            }
            var runtime=Runtime.getRuntime(); long used=runtime.totalMemory()-runtime.freeMemory();
            double recent=server.tickTimes[Math.floorMod(server.getTickCount()-1,server.tickTimes.length)]/1_000_000.0;
            double oldFraction=oldPool==null?(double)used/runtime.maxMemory():(double)oldPool.getUsage().getUsed()/runtime.maxMemory();
            governor.sample(System.nanoTime(),(double)used/runtime.maxMemory(),oldFraction,runtime.maxMemory()-used,
                    Math.max(recent,server.getAverageTickTime()),policy.pauseAboveTickMillis(),nearChunkBacklog,
                    engine.savingServiceRateLimiter.getAsBoolean(),policy.enabled() && !manualPaused,config.maxGenerationStepsPerSecond);
            if(job!=null && !job.cancelled.get() && governor.hardPressure(System.nanoTime())) preemptForPressure();
            if (playersPresent == null || playersPresent != occupied) {
                playersPresent = occupied;
                int limit = policy.generationThreads();
                if (limit > stages.getMaximumPoolSize()) {
                    stages.setMaximumPoolSize(limit); stages.setCorePoolSize(limit);
                } else {
                    stages.setCorePoolSize(limit); stages.setMaximumPoolSize(limit);
                }
                nextBatchTick = ticks + policy.cooldownTicks() - 1L;
                VoxyLodOnly.LOGGER.info("LOD activity profile: mode={}, enabled={}, workers={}, chunksPerSecond={}, commitBudget={}ms",
                        occupied ? "withPlayers" : "withoutPlayers", policy.enabled(), limit,
                        policy.maxChunksPerSecond(), policy.commitBudgetMillis());
            }
            if (job != null && job.owner != null && !job.cancelled.get()) {
                var player = server.getPlayerList().getPlayer(job.owner.getUUID());
                if (player == null) {
                    if (occupied || !config.continueAutomaticWhenEmpty) cancel();
                    else if(job.selection!=null) job.selection=null;
                } else if (player != job.owner || player.serverLevel() != job.level
                        || job.selection!=null && !automaticRequests.current(job.selection.entry()) && obsoleteTile(player)
                        || !job.plan.includes(player.chunkPosition().x, player.chunkPosition().z)) {
                    obsoleteTileCancellations++;
                    cancelActiveJob();
                }
            }
            if(job!=null && job.owner!=null && !job.cancelled.get() && ticks%10==1 && !reservations.isEmpty()
                    && reservations.stream().allMatch(key -> realDemand(job.level,new ChunkPos(key.position())))) {
                overtakenBatches++;cancelActiveJob();
            }
            if (job != null && job.cancelled.get()) ready = null;
            if (ready == null && preparation != null) {
                preparation.close();
                if (!preparation.drained()) return;
                preparation = null;
            }
            if (ready == null && !pending.isEmpty() && pending.peekFirst().future().isDone()) {
                try {
                    var entry=pending.removeFirst();
                    var completed=entry.future().join();
                    if (job != null && !job.cancelled.get()) {
                        skipped += entry.requested()-completed.output().size();
                        ready = completed; chunkIndex = sectionIndex = 0; chunkAdmitted = false;
                    } else if (completed != null && completed.cpuNanos() > 0L) {
                        InMemoryGenerator.discardedCpuNanos.addAndGet(completed.cpuNanos());
                    }
                } catch (CompletionException | CancellationException e) {
                    if (pressureAbort(e)) preemptForPressure();
                    else if (job != null && !job.cancelled.get()) throw e;
                }
            }
            if (job != null && job.cancelled.get()) {
                if (pending.isEmpty()) { job = null; reservations.clear(); }
                return;
            }
            if (pendingConfig!=null) return;
            if (config.enabled && !governor.paused() && job==null && queued!=null && pending.isEmpty() && ready==null && preparation==null) {
                realChunks.clear(); job=queued.create(tileSize());
                job.generatedBefore=generated; job.skippedBefore=skipped; queued=null; nextBatchTick=ticks;
            }
            if (manualPaused || !policy.enabled() || governor.paused()) return;
            if (ready != null) commit();
            if (!pending.isEmpty() || ready != null || preparation != null || ticks < nextBatchTick) return;
            reservations.clear();
            if (job == null && automatic) findAutomaticJob();
            if (job == null) return;
            long scanStarted = System.nanoTime();
            long scanBudgetNanos = (long)(Math.max(1.0, policy.commitBudgetMillis() * 0.5) * 1_000_000.0);
            var regionExists = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
            ServerLevel cachedRegionLevel = null;
            java.nio.file.Path cachedRegionDir = null;
            // Prefetch generation while the tick thread applies the previous batch. Total retained
            // batches remain bounded, and a cancelled job drains before a new job takes the slot.
            for (int scan = 0; scan < 16 && job != null; scan++) {
                if (scan > 0 && System.nanoTime() - scanStarted > scanBudgetNanos) {
                    scanBudgetYields++;
                    return;
                }
                if (job.selection!=null && job.tileDispatched || job.selection==null && !job.plan.hasNext()) {
                    if (!pending.isEmpty() || ready != null || preparation != null) return;
                    boolean unchanged = job.generatedBefore == generated;
                    if (job.owner == null || job.selection==null && !unchanged)
                        VoxyLodOnly.LOGGER.info("LOD Only job finished: dimension={}, generated={}, skipped={}, sections={}",
                                job.level.dimension().location(), generated, skipped, sections);
                    // Completed automatic areas need no per-tick full rescan or repeated log.
                    nextBatchTick = ticks + (job.owner != null && job.selection==null && unchanged
                            ? Math.max(100, policy.cooldownTicks()) : policy.cooldownTicks());
                    job = null; return;
                }
                var tile = job.selection==null ? job.plan.peek() : job.selection.tile();
                Job current = job;
                List<ChunkPos> candidatePositions = new ArrayList<>();
                long tileSkipped = 0, tileCompletedDup = 0, tileRealDemandSkip = 0, tileInFlightDup = 0;
                int cMinX = Integer.MAX_VALUE, cMaxX = Integer.MIN_VALUE;
                int cMinZ = Integer.MAX_VALUE, cMaxZ = Integer.MIN_VALUE;
                String dimName = current.level.dimension().location().toString();
                var currentLedger = ledger(current.level);
                var border = current.level.getWorldBorder();
                for (int dz = 0; dz < current.tileSize; dz++) for (int dx = 0; dx < current.tileSize; dx++) {
                    ChunkPos pos = new ChunkPos(current.plan.startX(tile) + dx, current.plan.startZ(tile) + dz);
                    if (!current.plan.includes(pos.x, pos.z)) continue;
                    if (!border.isWithinBounds(pos)) { tileSkipped++; continue; }
                    if (currentLedger.contains(pos.toLong())) { tileSkipped++; tileCompletedDup++; continue; }
                    if (real(current.level, pos)) { tileSkipped++; continue; }
                    // Loaded-only checks miss vanilla FULL futures already requested for players.
                    // Do not compete to preview their current view envelope (plus two chunks).
                    if (realDemand(current.level, pos)) { tileSkipped++; tileRealDemandSkip++; continue; }
                    var key = new WorkKey(dimName, pos.toLong(), config.structures);
                    if (reservations.contains(key)) { tileInFlightDup++; tileSkipped++; continue; }
                    candidatePositions.add(pos);
                    cMinX = Math.min(cMinX, pos.x); cMaxX = Math.max(cMaxX, pos.x);
                    cMinZ = Math.min(cMinZ, pos.z); cMaxZ = Math.max(cMaxZ, pos.z);
                }
                if (candidatePositions.isEmpty()) {
                    if (job.selection == null) job.plan.next();
                    else job.tileDispatched = true;
                    skipped += tileSkipped;
                    completedDuplicateSkips += tileCompletedDup;
                    realDemandSkips += tileRealDemandSkip;
                    inFlightDuplicateSkips += tileInFlightDup;
                    if (job.selection != null) {
                        var nextTile = automaticRequests.advanceSameEntry(job.selection, System.nanoTime());
                        if (nextTile != null) {
                            job.selection = nextTile;
                            job.tileDispatched = false;
                            continue;
                        }
                        job = null;
                        findAutomaticJob();
                    }
                    continue;
                }
                int spanX = current.level.dimension() != net.minecraft.world.level.Level.OVERWORLD
                        ? current.tileSize : (cMaxX - cMinX + 1);
                int spanZ = current.level.dimension() != net.minecraft.world.level.Level.OVERWORLD
                        ? current.tileSize : (cMaxZ - cMinZ + 1);
                long estimatedBytes = LodOnlyConfig.estimatedBatchBytes(spanX, spanZ, candidatePositions.size());
                if (!governor.canAdmitBatch(estimatedBytes, config.resourceBudgetMiB * 1024L * 1024L)) {
                    admissionYields++;
                    nextBatchTick = ticks + Math.max(1, policy.cooldownTicks());
                    return;
                }
                if (job.selection == null) job.plan.next();
                job.tileDispatched = true;
                skipped += tileSkipped;
                completedDuplicateSkips += tileCompletedDup;
                realDemandSkips += tileRealDemandSkip;
                inFlightDuplicateSkips += tileInFlightDup;
                if (cachedRegionLevel != current.level) {
                    cachedRegionLevel = current.level;
                    cachedRegionDir = DimensionType.getStorageFolder(current.level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
                    regionExists.clear();
                }
                var regionDir = cachedRegionDir;
                List<Long> knownAbsent = new ArrayList<>(candidatePositions.size());
                List<ChunkPos> toRead = new ArrayList<>();
                var chunkMap = current.level.getChunkSource().chunkMap;
                for (ChunkPos pos : candidatePositions) {
                    var key = new WorkKey(dimName, pos.toLong(), config.structures);
                    if (!reservations.add(key)) { inFlightDuplicateSkips++; skipped++; continue; }
                    // Vanilla RegionFileStorage.read creates an empty .mca even for an absent region.
                    // Check its existence once per 32x32 region within the batch scan.
                    int rx = pos.x >> 5, rz = pos.z >> 5;
                    long regionKey = ChunkPos.asLong(rx, rz);
                    boolean exists;
                    if (regionExists.containsKey(regionKey)) {
                        exists = regionExists.get(regionKey);
                    } else {
                        exists = Files.isRegularFile(regionDir.resolve("r." + rx + "." + rz + ".mca"));
                        regionExists.put(regionKey, exists);
                    }
                    if (exists) {
                        toRead.add(pos);
                    } else {
                        knownAbsent.add(pos.toLong());
                    }
                }
                int requestedCount = knownAbsent.size() + toRead.size();
                if (requestedCount == 0) continue;
                // Player lists belong to the server thread. Capture the limit before dispatch.
                int batchWorkers = policy.generationThreads();
                boolean structures = config.structures;
                var probeFuture = ChunkPresenceProbe.probe(
                        knownAbsent,
                        toRead,
                        chunkMap::read,
                        ChunkPresenceProbe.DEFAULT_MAX_CONCURRENCY,
                        current.cancelled::get
                );
                var future = probeFuture.thenApplyAsync(probeResult -> {
                    if (current.cancelled.get()) throw new CancellationException();
                    if (probeResult.errorCount() > 0) {
                        readErrorSkips.addAndGet(probeResult.errorCount());
                    }
                    Set<Long> eligible = probeResult.absentPositions();
                    if (eligible.isEmpty()) return new InMemoryGenerator.Batch(tile.x(), tile.z(), List.of(), null, List.of(), 0L);
                    // Reevaluate between batches so player arrival also reserves a worker.
                    return new InMemoryGenerator(current.level, current.cancelled::get, stages, batchWorkers,
                            current.tileSize).checkpoint(() -> governor.checkpoint(current.cancelled::get,true))
                            .generateAt(current.plan.startX(tile), current.plan.startZ(tile), eligible, structures);
                }, worker);
                pending.addLast(new PendingBatch(future, requestedCount));
                nextBatchTick = ticks + policy.cooldownTicks();
                return;
            }
        } catch (RuntimeException e) {
            if(pressureAbort(e)) { preemptForPressure(); return; }
            lastError = e.toString();
            VoxyLodOnly.LOGGER.error("LOD Only generation failed; automatic generation paused, real chunk storage unchanged", e);
            automatic = false; cancel();
        }
    }

    private void commit() {
        long started = System.nanoTime();
        double budgetMillis = policy.commitBudgetMillis();
        int finished = 0;
        if (preparation == null) {
            var world=engine.getOrCreate(job.level);
            if (world == null) throw new IllegalStateException("VoxyServer world unavailable");
            Job current=job;
            preparation=new PreparedLodBatch(world,ready,current.cancelled::get,stages)
                    .releaseConsumed()
                    .checkpoint(() -> governor.checkpoint(current.cancelled::get,false));
            ready=new InMemoryGenerator.Batch(ready.tileX(),ready.tileZ(),ready.output(),null,null,ready.cpuNanos());
            if (GenerationTiming.enabled()) {
                commitStart=System.nanoTime(); commitTicks=conversionWaits=budgetYields=capYields=0;
            }
        }
        if (GenerationTiming.enabled()) commitTicks++;
        preparation.fill();
        while (chunkIndex < ready.output().size() && finished < policy.commitChunksPerTick()) {
            var prepared=preparation.peek();
            if (prepared == null) {
                if (GenerationTiming.enabled()) conversionWaits++;
                return;
            }
            if (prepared.index()!=chunkIndex) throw new IllegalStateException("LOD conversion order changed");
            ChunkPos pos = prepared.pos();
            // A chunk loaded, edited, or visited while generation was in flight always wins.
            if (real(job.level, pos) || realDemand(job.level,pos)) { skipped++; chunkIndex++; sectionIndex = 0; chunkAdmitted = false; finished++; preparation.skip(); continue; }
            if (!prepared.sections().isDone()) {
                if (GenerationTiming.enabled()) conversionWaits++;
                return;
            }
            var converted=prepared.sections().join();
            if (!chunkAdmitted) {
                int rate = policy.maxChunksPerSecond();
                if (!chunkPacer.admit(System.nanoTime(), rate == 0 ? (Boolean.TRUE.equals(playersPresent) ? 8 : 32) : rate)) return;
                chunkAdmitted = true;
            }
            for (; sectionIndex < converted.length; sectionIndex++) {
                VoxelizedSection section = converted[sectionIndex];
                if (section != null) {
                    converted[sectionIndex] = null;
                    WorldUpdater.insertUpdate(preparation.world, section);
                }
                sections++;
                // Yield between sections; do not run an entire terrain tile on the tick thread.
                if ((System.nanoTime() - started) > budgetMillis * 1_000_000L) {
                    if (GenerationTiming.enabled()) budgetYields++;
                    sectionIndex++; return;
                }
            }
            ledger(job.level).add(pos.toLong());
            ledgers.get(new LedgerKey(job.level,!config.structures)).remove(pos.toLong());
            generated++; finished++; chunkIndex++; sectionIndex = 0; chunkAdmitted = false;
            preparation.advance();
        }
        if (chunkIndex >= ready.output().size()) {
            if (GenerationTiming.enabled()) VoxyLodOnly.LOGGER.info(
                    "LOD_COMMIT x={} z={} chunks={} wallMs={} ticks={} conversionWaits={} budgetYields={} capYields={}",
                    ready.tileX(),ready.tileZ(),ready.output().size(),(System.nanoTime()-commitStart)/1_000_000.0,
                    commitTicks,conversionWaits,budgetYields,capYields);
            batches++; ready = null;
            preparation.close();
            if (preparation.drained()) preparation = null;
        } else if (GenerationTiming.enabled() && finished >= policy.commitChunksPerTick()) {
            capYields++;
        }
    }

    private void findAutomaticJob() {
        var selected=automaticRequests.next(System.nanoTime());
        if(selected==null) return;
        var player=server.getPlayerList().getPlayer(selected.entry().viewer.id());
        if(player==null || player!=selected.entry().viewer.session()) return;
        realChunks.clear(); var pos=selected.entry().viewer;
        job=new Job(player.serverLevel(),pos.x(),pos.z(),config.radiusChunks,player,selected.entry().tileSize);
        // Share the persistent per-player cursor. Only this selection's single tile is dispatched.
        job.selection=selected; job.generatedBefore=generated; job.skippedBefore=skipped;
    }
    private void refreshAutomaticRequests() {
        if(!automatic) { automaticRequests.clear(); return; }
        var viewers=new ArrayList<AutomaticRequests.Viewer>();
        for(var player:server.getPlayerList().getPlayers()) {
            String dimension=player.serverLevel().dimension().location().toString();
            if(config.dimensions.contains(dimension)) viewers.add(new AutomaticRequests.Viewer(player.getUUID(),player,
                    dimension,player.chunkPosition().x,player.chunkPosition().z));
        }
        int innerRadius=server.getPlayerList().getViewDistance()+2;
        automaticRequests.update(viewers,config.radiusChunks,tileSize(),Math.min(config.automaticRetargetChunks,
                Math.max(1,config.radiusChunks/2)),innerRadius,System.nanoTime());
    }
    private boolean obsoleteTile(net.minecraft.server.level.ServerPlayer player) {
        // Retarget the queued cursor immediately, but do not throw away a still useful
        // running tile just because its player's anchor moved eight chunks.
        // At most one such tile exists, and it cannot hold a player's turn beyond 20s.
        int x=job.plan.startX(job.selection.tile()),z=job.plan.startZ(job.selection.tile());
        var pos=player.chunkPosition();
        long dx=Math.max(Math.max((long)x-pos.x,(long)pos.x-(x+job.tileSize-1)),0);
        long dz=Math.max(Math.max((long)z-pos.z,(long)pos.z-(z+job.tileSize-1)),0);
        var latest=automaticRequests.entry(player.getUUID());
        var dirSource=latest!=null?latest:job.selection.entry();
        long vx=dirSource.vx, vz=dirSource.vz;
        long tileCx=(long)x+job.tileSize/2-pos.x, tileCz=(long)z+job.tileSize/2-pos.z;
        boolean movingAway = (vx!=0 || vz!=0) && (tileCx*vx + tileCz*vz < 0);
        int maxDist = movingAway ? Math.min(config.radiusChunks, 16) : config.radiusChunks;
        return Math.max(dx,dz)>maxDist || System.nanoTime()-job.created>20_000_000_000L;
    }
    private static boolean pressureAbort(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause()) if(e instanceof ResourceGovernor.PressureAbort) return true;
        return false;
    }
    private void preemptForPressure() {
        if(job==null || job.cancelled.get()) return;
        long now = System.nanoTime();
        governor.aborted();
        pressureAborts++;
        discardedGenerationNanos += Math.max(0L, now - job.created);
        if(job.selection!=null) {
            automaticRequests.retry(job.selection, now);
            job.selection = null;
        } else if(job.owner==null && queued==null) {
            queued=Request.of(job);
        }
        cancelActiveJob();
    }
    private String stateKey() {
        return stopping?"stopped":job!=null && job.cancelled.get()?"cancelling":
                (manualPaused || policy!=null && !policy.enabled()) && (job!=null || queued!=null)?"paused":
                queued!=null?"queued":!pending.isEmpty()?"generating":ready!=null?"committing":job!=null?"scanning":"idle";
    }
    public boolean automaticEnabled() { return automatic; }
    public int radiusChunks() { return config.radiusChunks; }
    /** Short, human-oriented status used by the default /voxylod status. */
    public String summary() {
        String key=stateKey();
        boolean working=!key.equals("idle") && !key.equals("stopped");
        String state=switch (key) {
            case "stopped" -> "서버 종료 중";
            case "cancelling" -> "작업 취소 정리 중";
            case "paused" -> "일시정지 (/voxylod resume 으로 재개)";
            case "queued" -> "수동 구역 대기 중";
            case "generating" -> "LOD 생성 중";
            case "committing" -> "LOD 반영 중";
            case "scanning" -> "생성 대상 탐색 중";
            default -> automatic ? "대기 중 (생성할 곳 없음 / 플레이어 이동 대기)" : "정지됨";
        };
        String yield=governor.snapshot().paused()&&working?"서버 부하로 양보 중 ("+governor.snapshot().reason()+")":"정상";
        return "[Voxy LOD Only]\n"
                + " 상태: "+state+"\n"
                + " 자동 생성: "+(automatic?"켜짐":"꺼짐 (/voxylod automatic on 으로 켜기)")
                + (pendingConfig!=null?" · 설정 reload 적용 대기 중":"")+"\n"
                + " 반경: "+config.radiusChunks+"청크 · 모드: "+(Boolean.TRUE.equals(playersPresent)?"접속자 있음":"접속자 없음")
                + " · 부하: "+yield+"\n"
                + " 누적: 생성 "+generated+" · 건너뜀 "+skipped+" · 섹션 "+sections
                + (job==null?"":"\n 진행: "+(generated-job.generatedBefore+skipped-job.skippedBefore)+"/"+job.total+" ("+job.level.dimension().location()+")")
                + (lastError==null||lastError.isEmpty()||lastError.equals("none")?"":"\n 마지막 오류: "+lastError)
                + "\n 자세히: /voxylod status detail";
    }
    public String status() {
        String state=stateKey();
        return "Voxy LOD Only: automatic=" + automatic + ", state=" + state
                + ", radiusChunks="+config.radiusChunks+", configReloadPending="+(pendingConfig!=null)
                + ", generated=" + generated + ", skipped=" + skipped + ", sections=" + sections + ", batches=" + batches
                + ", inFlight=" + (pending.size() + (ready == null && preparation == null ? 0 : 1))
                + ", conversionTasks=" + (preparation == null ? 0 : preparation.taskCount())
                + ", retainedConversions=" + (preparation == null ? 0 : preparation.retainedCount())
                + ", retainedConversionBytes=" + (preparation == null ? 0 : preparation.retainedBytes())
                + ", terrainThreads=" + stages.getMaximumPoolSize()
                + ", mode=" + (Boolean.TRUE.equals(playersPresent) ? "withPlayers" : "withoutPlayers")
                + ", profileEnabled=" + (policy != null && policy.enabled())
                + ", maxChunksPerSecond=" + (policy == null ? 0 : policy.maxChunksPerSecond())
                + ", manualPaused="+manualPaused
                + ", "+governor.status()+", "+automaticRequests.status(System.nanoTime())
                + ", owner="+(job==null||job.owner==null?"manual/none":job.owner.getUUID())
                + ", requestAgeMillis="+(job==null?0:(System.nanoTime()-job.created)/1_000_000L)
                + ", reservedChunks="+reservations.size()+", estimatedRetainedMiB="+(job==null?0:LodOnlyConfig.estimatedBatchBytes(job.tileSize)/1048576)
                + ", configuredTileSize="+config.generationTileSize+", effectiveTileSize="+tileSize()
                + ", generationStepsPerSecond="+config.maxGenerationStepsPerSecond
                + ", completedDuplicateSkips="+completedDuplicateSkips+", inFlightDuplicateSkips="+inFlightDuplicateSkips
                + ", realDemandSkips="+realDemandSkips+", readErrorSkips="+readErrorSkips.get()+", overtakenBatches="+overtakenBatches
                + ", cancellations="+cancellations+", pressureAborts="+pressureAborts
                + ", obsoleteTileCancellations="+obsoleteTileCancellations+", manualCancellations="+manualCancellations
                + ", discardedChunks="+discardedChunks
                + ", discardedGenerationMillis="+(discardedGenerationNanos/1_000_000L)
                + ", discardedGenerationCpuMillis="+(InMemoryGenerator.discardedCpuNanos.get()/1_000_000L)
                + ", admissionYields="+admissionYields+", scanBudgetYields="+scanBudgetYields
                + ", nearChunkBacklog="+nearChunkBacklog
                + ", queuedStageTasks="+stages.getQueue().size()+", activeStageTasks="+stages.getActiveCount()
                + ", generationGridChunks="+InMemoryGenerator.activeGridChunks.get()+", peakGridChunks="+InMemoryGenerator.peakGridChunks.get()
                + ", readyOutputChunks="+(ready==null?0:ready.output().size()-chunkIndex)
                + ", noiseMemoizedNodes="+NoiseChunkCache.memoizedNodes.get()
                + ", heightCacheHits="+NoiseChunkCache.heightCacheHits.get()+", heightCacheMisses="+NoiseChunkCache.heightCacheMisses.get()
                + ", terminalPoolsRepaired="+StructureCompatibility.terminalPoolsRepaired.get()
                + ", villagePoolsRepaired="+StructureCompatibility.villagePoolsRepaired.get()
                + ", optionalEntitiesRemoved="+StructureCompatibility.optionalEntitiesRemoved.get()
                + ", lodEntitiesSkipped="+StructureCompatibility.lodEntitiesSkipped.get()
                + (job==null?queued==null?"":", requestQueued=true":
                   ", dimension="+job.level.dimension().location()+", area={"+job.plan.description()+"}, completed="
                   +(generated-job.generatedBefore+skipped-job.skippedBefore)+"/"+job.total)
                + ", error=" + lastError;
    }
    public void cancel() {
        queued=null; manualPaused=false;
        if (job != null && !job.cancelled.get()) manualCancellations++;
        cancelActiveJob();
    }
    private void cancelActiveJob() {
        if (job != null && job.cancelled.compareAndSet(false,true)) {
            cancellations++;
            if (ready != null) {
                int remaining = Math.max(0, ready.output().size() - chunkIndex);
                discardedChunks += remaining;
                if (ready.cpuNanos() > 0L && !ready.output().isEmpty()) {
                    InMemoryGenerator.discardedCpuNanos.addAndGet(ready.cpuNanos() * remaining / ready.output().size());
                }
            }
        }
        ready = null;
        if (preparation != null) preparation.close();
        if (pending.isEmpty() && preparation == null) job = null;
    }
    public void shutdown() {
        if (stopping) return;
        stopping = true; cancel(); worker.shutdown();
        // Keep the stage executor alive until its coordinator has drained every submitted stage.
        drain(worker);
        stages.shutdown(); drain(stages);
        if (preparation != null && !preparation.drained()) throw new IllegalStateException("LOD conversion references did not drain");
        pending.clear(); ready = null; preparation = null; job = null;
        VoxyLodOnly.LOGGER.info("LOD Only generator stopped; no pending tile or Voxy references");
    }
    private void drain(ExecutorService executor) {
        boolean interrupted = false;
        for (;;) {
            try { if (executor.awaitTermination(1, TimeUnit.SECONDS)) break; }
            catch (InterruptedException e) { interrupted = true; }
            VoxyLodOnly.LOGGER.info("Waiting for private LOD generation stage to drain");
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    public void saveCheckpoints() {
        for (var ledger : ledgers.values()) {
            if (!ledger.needsSave()) continue;
            try { ledger.save(); }
            catch (IOException e) { VoxyLodOnly.LOGGER.error("Cannot save LOD completion checkpoint", e); }
        }
    }
}
