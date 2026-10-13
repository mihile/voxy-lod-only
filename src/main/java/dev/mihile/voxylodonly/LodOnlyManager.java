package dev.mihile.voxylodonly;

import com.dripps.voxyserver.server.ServerLodEngine;
import dev.mihile.voxylodonly.mixin.ChunkMapAccessor;
import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.dimension.DimensionType;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LodOnlyManager {
    private final MinecraftServer server;
    private final ServerLodEngine engine;
    private LodOnlyConfig config, pendingConfig;
    private boolean pendingAutomatic;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Voxy LOD Only coordinator"); thread.setDaemon(true); return thread;
    });
    private final ThreadPoolExecutor stages;
    private final ChunkPacer chunkPacer = new ChunkPacer();
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
    private int automaticPlayerCursor;
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

    public LodOnlyManager(MinecraftServer server, ServerLodEngine engine, LodOnlyConfig config) {
        this.server = server; this.engine = engine; this.config = config; this.automatic = config.automatic;
        int generationThreads = config.effectiveGenerationThreads();
        stages = (ThreadPoolExecutor)Executors.newFixedThreadPool(generationThreads, r -> {
            Thread thread = new Thread(r, "Voxy LOD Only terrain"); thread.setDaemon(true); return thread;
        });
        VoxyLodOnly.LOGGER.info("LOD scheduler: terrainThreads={}, tileSize={}, inFlight={}, commitBudget={}ms",
                generationThreads, config.generationTileSize, config.maxInFlightBatches, config.commitBudgetMillis);
    }
    public boolean generate(ServerLevel level, int x, int z, int radius) {
        if (!config.enabled || stopping || queued != null || job != null || !pending.isEmpty() || ready != null || preparation != null) return false;
        realChunks.clear();
        job = new Job(level, x, z, radius, null, config.generationTileSize);
        job.generatedBefore = generated; job.skippedBefore=skipped; manualPaused=false; nextBatchTick=ticks; return true;
    }
    public boolean startArea(ServerLevel level, GenerationArea area) {
        if (!config.enabled || stopping || queued != null || (job != null && job.owner == null && !job.cancelled.get())) return false;
        // Explicit areas take over automatic work only after its futures and conversion leases drain.
        cancel(); automatic=false; if (pendingConfig!=null) pendingAutomatic=false;
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
        automatic=enabled; if (pendingConfig!=null) pendingAutomatic=enabled;
        if (!enabled && queued==null && job!=null && job.owner!=null && !job.cancelled.get()) cancel();
    }
    public void radius(int radius) {
        config=config.withRadius(radius);
        if (pendingConfig!=null) pendingConfig=pendingConfig.withRadius(radius);
        if (job!=null && job.owner!=null && !job.cancelled.get()) cancelActiveJob();
        nextBatchTick=ticks;
    }
    public void reload(LodOnlyConfig replacement) {
        if (stopping) throw new IllegalStateException("Server is stopping");
        replacement.validate();
        if (queued==null && job!=null && job.owner==null && !job.cancelled.get()) queued=Request.of(job);
        pendingConfig=replacement;
        pendingAutomatic=queued==null?replacement.automatic:automatic;
        cancelActiveJob();
    }
    private void applyReloadIfDrained() {
        if (pendingConfig==null || job!=null || !pending.isEmpty() || ready!=null || preparation!=null) return;
        config=pendingConfig; pendingConfig=null; automatic=pendingAutomatic;
        playersPresent=null; nextBatchTick=ticks;
        VoxyLodOnly.LOGGER.info("LOD config applied: radiusChunks={}, enabled={}, automatic={}, structures={}, tileSize={}",
                config.radiusChunks,config.enabled,automatic,config.structures,config.generationTileSize);
    }
    public void markRealChunk(ServerLevel level, ChunkPos pos) {
        // Retain only the bounded active job's positions, not every chunk ever visited on the server.
        if (job != null && job.level == level && job.plan.includes(pos.x, pos.z))
            realChunks.computeIfAbsent(level, unused -> new HashSet<>()).add(pos.toLong());
    }
    private boolean real(ServerLevel level, ChunkPos pos) {
        return realChunks.getOrDefault(level, Set.of()).contains(pos.toLong())
                || level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
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
            if (playersPresent == null || playersPresent != occupied) {
                playersPresent = occupied;
                // A reduced pool also limits conversion tasks. Already running tasks finish safely.
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
                } else if (player != job.owner || player.serverLevel() != job.level
                        || !job.plan.includes(player.chunkPosition().x, player.chunkPosition().z)) cancel();
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
                    }
                } catch (CompletionException | CancellationException e) {
                    if (job != null && !job.cancelled.get()) throw e;
                }
            }
            if (job != null && job.cancelled.get()) {
                if (pending.isEmpty()) job = null;
                return;
            }
            if (pendingConfig!=null) return;
            if (config.enabled && job==null && queued!=null && pending.isEmpty() && ready==null && preparation==null) {
                realChunks.clear(); job=queued.create(config.generationTileSize);
                job.generatedBefore=generated; job.skippedBefore=skipped; queued=null; nextBatchTick=ticks;
            }
            if (manualPaused || !policy.enabled() || server.getAverageTickTime() > policy.pauseAboveTickMillis() || !engine.savingServiceRateLimiter.getAsBoolean()) return;
            if (ready != null) commit();
            if (pending.size() + (ready == null ? 0 : 1) >= config.maxInFlightBatches || ticks < nextBatchTick) return;
            if (job == null && automatic) findAutomaticJob();
            if (job == null) return;
            // Prefetch generation while the tick thread applies the previous batch. Total retained
            // batches remain bounded, and a cancelled job drains before a new job takes the slot.
            for (int scan = 0; scan < 8 && job != null; scan++) {
                if (!job.plan.hasNext()) {
                    if (!pending.isEmpty() || ready != null || preparation != null) return;
                    boolean unchanged = job.generatedBefore == generated;
                    if (job.owner == null || !unchanged)
                        VoxyLodOnly.LOGGER.info("LOD Only job finished: dimension={}, generated={}, skipped={}, sections={}",
                                job.level.dimension().location(), generated, skipped, sections);
                    // Completed automatic areas need no per-tick full rescan or repeated log.
                    nextBatchTick = ticks + (job.owner != null && unchanged ? Math.max(100, policy.cooldownTicks()) : policy.cooldownTicks());
                    job = null; return;
                }
                var tile = job.plan.next();
                Job current = job;
                Map<Long, CompletableFuture<Optional<CompoundTag>>> reads = new LinkedHashMap<>();
                var reader = (ChunkMapAccessor)current.level.getChunkSource().chunkMap;
                for (int dz = 0; dz < current.tileSize; dz++) for (int dx = 0; dx < current.tileSize; dx++) {
                    ChunkPos pos = new ChunkPos(current.plan.startX(tile) + dx, current.plan.startZ(tile) + dz);
                    if (!current.plan.includes(pos.x, pos.z)) continue;
                    if (!current.level.getWorldBorder().isWithinBounds(pos)) { skipped++; continue; }
                    if (ledger(current.level).contains(pos.toLong()) || real(current.level, pos)) { skipped++; continue; }
                    // Vanilla RegionFileStorage.read creates an empty .mca even for an absent region.
                    // Check its existence first; private generation must not create those headers either.
                    var regionFile = DimensionType.getStorageFolder(current.level.dimension(), server.getWorldPath(LevelResource.ROOT))
                            .resolve("region").resolve("r." + Math.floorDiv(pos.x, 32) + "." + Math.floorDiv(pos.z, 32) + ".mca");
                    reads.put(pos.toLong(), Files.isRegularFile(regionFile) ? reader.lodOnly$readChunk(pos)
                            : CompletableFuture.completedFuture(Optional.empty()));
                }
                if (reads.isEmpty()) continue;
                // Player lists belong to the server thread. Capture the limit before dispatch.
                int batchWorkers = policy.generationThreads();
                var future=CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new)).thenApplyAsync(unused -> {
                    if (current.cancelled.get()) throw new CancellationException();
                    Set<Long> eligible = new HashSet<>();
                    for (var entry : reads.entrySet()) if (entry.getValue().join().isEmpty()) eligible.add(entry.getKey());
                    if (eligible.isEmpty()) return new InMemoryGenerator.Batch(tile.x(), tile.z(), List.of(), null);
                    // Reevaluate between batches so player arrival also reserves a worker.
                    return new InMemoryGenerator(current.level, current.cancelled::get, stages, batchWorkers,
                            current.tileSize).generateAt(current.plan.startX(tile), current.plan.startZ(tile), eligible, config.structures);
                }, worker);
                pending.addLast(new PendingBatch(future,reads.size()));
                nextBatchTick = ticks + policy.cooldownTicks();
                return;
            }
        } catch (RuntimeException e) {
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
            preparation=new PreparedLodBatch(world,ready,job.cancelled::get,stages);
            if (GenerationTiming.enabled()) {
                commitStart=System.nanoTime(); commitTicks=conversionWaits=budgetYields=capYields=0;
            }
        }
        if (GenerationTiming.enabled()) commitTicks++;
        preparation.fill();
        while (chunkIndex < ready.output().size() && finished < policy.commitChunksPerTick()) {
            ProtoChunk chunk = ready.output().get(chunkIndex);
            var prepared=preparation.peek();
            if (prepared.index()!=chunkIndex) throw new IllegalStateException("LOD conversion order changed");
            // A chunk loaded, edited, or visited while generation was in flight always wins.
            if (real(job.level, chunk.getPos())) { skipped++; chunkIndex++; sectionIndex = 0; chunkAdmitted = false; finished++; preparation.advance(); continue; }
            if (!prepared.sections().isDone()) {
                if (GenerationTiming.enabled()) conversionWaits++;
                return;
            }
            var converted=prepared.sections().join();
            if (!chunkAdmitted) {
                if (!chunkPacer.admit(System.nanoTime(), policy.maxChunksPerSecond())) return;
                chunkAdmitted = true;
            }
            for (; sectionIndex < chunk.getSections().length; sectionIndex++) {
                WorldUpdater.insertUpdate(preparation.world, converted[sectionIndex]);
                sections++;
                // Yield between sections; do not run an entire terrain tile on the tick thread.
                if ((System.nanoTime() - started) > budgetMillis * 1_000_000L) {
                    if (GenerationTiming.enabled()) budgetYields++;
                    sectionIndex++; return;
                }
            }
            ledger(job.level).add(chunk.getPos().toLong());
            ledgers.get(new LedgerKey(job.level,!config.structures)).remove(chunk.getPos().toLong());
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
        var players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            var player = players.get(Math.floorMod(automaticPlayerCursor++, players.size()));
            var level = player.serverLevel();
            if (!config.dimensions.contains(level.dimension().location().toString())) continue;
            var pos = player.chunkPosition();
            realChunks.clear();
            job = new Job(level, pos.x, pos.z, config.radiusChunks, player, config.generationTileSize);
            job.generatedBefore = generated; job.skippedBefore=skipped;
            return;
        }
    }
    public String status() {
        String state=stopping?"stopped":job!=null && job.cancelled.get()?"cancelling":
                (manualPaused || policy!=null && !policy.enabled()) && (job!=null || queued!=null)?"paused":
                queued!=null?"queued":!pending.isEmpty()?"generating":ready!=null?"committing":job!=null?"scanning":"idle";
        return "Voxy LOD Only: automatic=" + automatic + ", state=" + state
                + ", radiusChunks="+config.radiusChunks+", configReloadPending="+(pendingConfig!=null)
                + ", generated=" + generated + ", skipped=" + skipped + ", sections=" + sections + ", batches=" + batches
                + ", inFlight=" + (pending.size() + (ready == null && preparation == null ? 0 : 1))
                + ", conversionTasks=" + (preparation == null ? 0 : preparation.taskCount()) + ", terrainThreads=" + stages.getMaximumPoolSize()
                + ", mode=" + (Boolean.TRUE.equals(playersPresent) ? "withPlayers" : "withoutPlayers")
                + ", profileEnabled=" + (policy != null && policy.enabled())
                + ", maxChunksPerSecond=" + (policy == null ? 0 : policy.maxChunksPerSecond())
                + ", manualPaused="+manualPaused
                + ", terminalPoolsRepaired="+StructureCompatibility.terminalPoolsRepaired.get()
                + ", optionalEntitiesRemoved="+StructureCompatibility.optionalEntitiesRemoved.get()
                + ", lodEntitiesSkipped="+StructureCompatibility.lodEntitiesSkipped.get()
                + (job==null?queued==null?"":", requestQueued=true":
                   ", dimension="+job.level.dimension().location()+", area={"+job.plan.description()+"}, completed="
                   +(generated-job.generatedBefore+skipped-job.skippedBefore)+"/"+job.total)
                + ", error=" + lastError;
    }
    public void cancel() {
        queued=null; manualPaused=false;
        cancelActiveJob();
    }
    private void cancelActiveJob() {
        if (job != null) job.cancelled.set(true);
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
