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
    private final LodOnlyConfig config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Voxy LOD Only coordinator"); thread.setDaemon(true); return thread;
    });
    private final ExecutorService stages;
    private final int generationThreads;
    private final Map<ServerLevel, Set<Long>> realChunks = new IdentityHashMap<>();
    private final Map<ServerLevel, CompletionLedger> ledgers = new IdentityHashMap<>();
    private Job job;
    private final Deque<CompletableFuture<InMemoryGenerator.Batch>> pending = new ArrayDeque<>();
    private InMemoryGenerator.Batch ready;
    private PreparedLodBatch preparation;
    private int chunkIndex, sectionIndex;
    private boolean stopping, automatic;
    private long generated, skipped, sections, batches, ticks, nextBatchTick;
    private String lastError = "none";
    private int automaticPlayerCursor;

    private static final class Job {
        final ServerLevel level;
        final TilePlan plan;
        final int tileSize;
        final UUID owner;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Job(ServerLevel level, int x, int z, int radius, UUID owner, int tileSize) {
            this.level = level;
            boolean overworld=level.dimension()==net.minecraft.world.level.Level.OVERWORLD;
            // Preserve the smaller fixed-grid context outside the cropped Overworld domain.
            this.tileSize=overworld?tileSize:Math.min(tileSize,8);
            this.plan = new TilePlan(x, z, radius, this.tileSize, overworld); this.owner = owner;
        }
    }

    public LodOnlyManager(MinecraftServer server, ServerLodEngine engine, LodOnlyConfig config) {
        this.server = server; this.engine = engine; this.config = config; this.automatic = config.automatic;
        generationThreads = config.effectiveGenerationThreads();
        stages = Executors.newFixedThreadPool(generationThreads, r -> {
            Thread thread = new Thread(r, "Voxy LOD Only terrain"); thread.setDaemon(true); return thread;
        });
        VoxyLodOnly.LOGGER.info("LOD scheduler: terrainThreads={}, tileSize={}, inFlight={}, commitBudget={}ms",
                generationThreads, config.generationTileSize, config.maxInFlightBatches, config.commitBudgetMillis);
    }
    public boolean generate(ServerLevel level, int x, int z, int radius) {
        if (!config.enabled || stopping || job != null || !pending.isEmpty() || ready != null || preparation != null) return false;
        realChunks.clear();
        job = new Job(level, x, z, radius, null, config.generationTileSize); return true;
    }
    public void automatic(boolean enabled) { automatic = enabled; if (!enabled && job != null && job.owner != null) cancel(); }
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
        return ledgers.computeIfAbsent(level, key -> {
            String filename = Base64.getUrlEncoder().withoutPadding().encodeToString(key.dimension().location().toString().getBytes(StandardCharsets.UTF_8));
            try { return new CompletionLedger(server.getWorldPath(LevelResource.ROOT).resolve("voxy_lod_only").resolve(filename + (config.structures ? "-structures.bin" : "-terrain.bin")), key.getSeed()); }
            catch (IOException e) { throw new IllegalStateException("Cannot read LOD checkpoint", e); }
        });
    }

    public void tick() {
        if (stopping || !config.enabled) return;
        ticks++;
        try {
            if (job != null && job.owner != null) {
                var player = server.getPlayerList().getPlayer(job.owner);
                if (player == null || player.serverLevel() != job.level
                        || !job.plan.includes(player.chunkPosition().x, player.chunkPosition().z)) cancel();
            }
            if (job != null && job.cancelled.get()) ready = null;
            if (ready == null && preparation != null) {
                preparation.close();
                if (!preparation.drained()) return;
                preparation = null;
            }
            if (ready == null && !pending.isEmpty() && pending.peekFirst().isDone()) {
                try {
                    var completed = pending.removeFirst().join();
                    if (job != null && !job.cancelled.get()) { ready = completed; chunkIndex = sectionIndex = 0; }
                } catch (CompletionException | CancellationException e) {
                    if (job != null && !job.cancelled.get()) throw e;
                }
            }
            if (job != null && job.cancelled.get()) {
                if (pending.isEmpty()) job = null;
                return;
            }
            if (server.getAverageTickTime() > config.pauseAboveTickMillis || !engine.savingServiceRateLimiter.getAsBoolean()) return;
            if (ready != null) commit();
            if (pending.size() + (ready == null ? 0 : 1) >= config.maxInFlightBatches || ticks < nextBatchTick) return;
            if (job == null && automatic) findAutomaticJob();
            if (job == null) return;
            // Prefetch generation while the tick thread applies the previous batch. Total retained
            // batches remain bounded, and a cancelled job drains before a new job takes the slot.
            for (int scan = 0; scan < 8 && job != null; scan++) {
                if (!job.plan.hasNext()) {
                    if (!pending.isEmpty() || ready != null || preparation != null) return;
                    VoxyLodOnly.LOGGER.info("LOD Only job finished: dimension={}, generated={}, skipped={}, sections={}",
                            job.level.dimension().location(), generated, skipped, sections);
                    job = null; nextBatchTick = ticks + config.cooldownTicks; return;
                }
                var tile = job.plan.next();
                Job current = job;
                Map<Long, CompletableFuture<Optional<CompoundTag>>> reads = new LinkedHashMap<>();
                var reader = (ChunkMapAccessor)current.level.getChunkSource().chunkMap;
                for (int dz = 0; dz < current.tileSize; dz++) for (int dx = 0; dx < current.tileSize; dx++) {
                    ChunkPos pos = new ChunkPos(current.plan.startX(tile) + dx, current.plan.startZ(tile) + dz);
                    if (!current.plan.includes(pos.x, pos.z) || !current.level.getWorldBorder().isWithinBounds(pos)) continue;
                    if (ledger(current.level).contains(pos.toLong()) || real(current.level, pos)) { skipped++; continue; }
                    // Vanilla RegionFileStorage.read creates an empty .mca even for an absent region.
                    // Check its existence first; private generation must not create those headers either.
                    var regionFile = DimensionType.getStorageFolder(current.level.dimension(), server.getWorldPath(LevelResource.ROOT))
                            .resolve("region").resolve("r." + Math.floorDiv(pos.x, 32) + "." + Math.floorDiv(pos.z, 32) + ".mca");
                    reads.put(pos.toLong(), Files.isRegularFile(regionFile) ? reader.lodOnly$readChunk(pos)
                            : CompletableFuture.completedFuture(Optional.empty()));
                }
                if (reads.isEmpty()) continue;
                pending.addLast(CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new)).thenApplyAsync(unused -> {
                    if (current.cancelled.get()) throw new CancellationException();
                    Set<Long> eligible = new HashSet<>();
                    for (var entry : reads.entrySet()) if (entry.getValue().join().isEmpty()) eligible.add(entry.getKey());
                    if (eligible.isEmpty()) return new InMemoryGenerator.Batch(tile.x(), tile.z(), List.of(), null);
                    return new InMemoryGenerator(current.level, current.cancelled::get, stages, generationThreads,
                            current.tileSize).generateAt(current.plan.startX(tile), current.plan.startZ(tile), eligible, config.structures);
                }, worker));
                nextBatchTick = ticks + config.cooldownTicks;
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
        int finished = 0;
        if (preparation == null) {
            var world=engine.getOrCreate(job.level);
            if (world == null) throw new IllegalStateException("VoxyServer world unavailable");
            preparation=new PreparedLodBatch(world,ready,job.cancelled::get,stages);
        }
        preparation.fill();
        while (chunkIndex < ready.output().size() && finished < config.commitChunksPerTick) {
            ProtoChunk chunk = ready.output().get(chunkIndex);
            var prepared=preparation.peek();
            if (prepared.index()!=chunkIndex) throw new IllegalStateException("LOD conversion order changed");
            // A chunk loaded, edited, or visited while generation was in flight always wins.
            if (real(job.level, chunk.getPos())) { skipped++; chunkIndex++; sectionIndex = 0; finished++; preparation.advance(); continue; }
            if (!prepared.sections().isDone()) return;
            var converted=prepared.sections().join();
            for (; sectionIndex < chunk.getSections().length; sectionIndex++) {
                WorldUpdater.insertUpdate(preparation.world, converted[sectionIndex]);
                sections++;
                // Yield between sections; do not run an entire terrain tile on the tick thread.
                if ((System.nanoTime() - started) > config.commitBudgetMillis * 1_000_000L) { sectionIndex++; return; }
            }
            ledger(job.level).add(chunk.getPos().toLong());
            generated++; finished++; chunkIndex++; sectionIndex = 0;
            preparation.advance();
        }
        if (chunkIndex >= ready.output().size()) {
            batches++; ready = null;
            preparation.close();
            if (preparation.drained()) preparation = null;
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
            job = new Job(level, pos.x, pos.z, config.radiusChunks, player.getUUID(), config.generationTileSize);
            return;
        }
    }
    public String status() {
        return "Voxy LOD Only: automatic=" + automatic + ", state=" + (stopping ? "stopped" : !pending.isEmpty() ? "generating" : ready != null ? "committing" : job != null ? "scanning" : "idle")
                + ", generated=" + generated + ", skipped=" + skipped + ", sections=" + sections + ", batches=" + batches
                + ", inFlight=" + (pending.size() + (ready == null && preparation == null ? 0 : 1))
                + ", conversionTasks=" + (preparation == null ? 0 : preparation.taskCount()) + ", terrainThreads=" + generationThreads + ", error=" + lastError;
    }
    public void cancel() {
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
            try { ledger.save(); }
            catch (IOException e) { VoxyLodOnly.LOGGER.error("Cannot save LOD completion checkpoint", e); }
        }
    }
}
