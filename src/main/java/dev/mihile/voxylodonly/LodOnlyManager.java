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
        Thread thread = new Thread(r, "Voxy LOD Only generator"); thread.setDaemon(true); return thread;
    });
    private final Map<ServerLevel, Set<Long>> realChunks = new IdentityHashMap<>();
    private final Map<ServerLevel, CompletionLedger> ledgers = new IdentityHashMap<>();
    private Job job;
    private CompletableFuture<InMemoryGenerator.Batch> pending;
    private InMemoryGenerator.Batch ready;
    private int chunkIndex, sectionIndex;
    private boolean stopping, automatic;
    private long generated, skipped, sections, batches, ticks, nextBatchTick;
    private String lastError = "none";
    private int automaticPlayerCursor;

    private static final class Job {
        final ServerLevel level;
        final TilePlan plan;
        final UUID owner;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Job(ServerLevel level, int x, int z, int radius, UUID owner) {
            this.level = level; this.plan = new TilePlan(x, z, radius); this.owner = owner;
        }
    }

    public LodOnlyManager(MinecraftServer server, ServerLodEngine engine, LodOnlyConfig config) {
        this.server = server; this.engine = engine; this.config = config; this.automatic = config.automatic;
    }
    public boolean generate(ServerLevel level, int x, int z, int radius) {
        if (!config.enabled || stopping || job != null || pending != null || ready != null) return false;
        realChunks.clear();
        job = new Job(level, x, z, radius, null); return true;
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
            if (pending != null && pending.isDone()) {
                try {
                    var completed = pending.join();
                    if (job != null && !job.cancelled.get()) { ready = completed; chunkIndex = sectionIndex = 0; }
                } catch (CompletionException | CancellationException e) {
                    if (job != null && !job.cancelled.get()) throw e;
                } finally { pending = null; }
            }
            if (job != null && job.cancelled.get() && pending == null) { ready = null; job = null; }
            if (server.getAverageTickTime() > config.pauseAboveTickMillis || !engine.savingServiceRateLimiter.getAsBoolean()) return;
            if (ready != null) commit();
            if (pending != null || ready != null || ticks < nextBatchTick) return;
            if (job == null && automatic) findAutomaticJob();
            if (job == null) return;
            // A single pending tile, a single worker, and a bounded number of scans per tick.
            for (int scan = 0; scan < 8 && job != null; scan++) {
                if (!job.plan.hasNext()) {
                    VoxyLodOnly.LOGGER.info("LOD Only job finished: dimension={}, generated={}, skipped={}, sections={}",
                            job.level.dimension().location(), generated, skipped, sections);
                    job = null; nextBatchTick = ticks + config.cooldownTicks; return;
                }
                var tile = job.plan.next();
                Job current = job;
                Map<Long, CompletableFuture<Optional<CompoundTag>>> reads = new LinkedHashMap<>();
                var reader = (ChunkMapAccessor)current.level.getChunkSource().chunkMap;
                for (int dz = 0; dz < InMemoryGenerator.TILE; dz++) for (int dx = 0; dx < InMemoryGenerator.TILE; dx++) {
                    ChunkPos pos = new ChunkPos(tile.x() * InMemoryGenerator.TILE + dx, tile.z() * InMemoryGenerator.TILE + dz);
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
                pending = CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new)).thenApplyAsync(unused -> {
                    if (current.cancelled.get()) throw new CancellationException();
                    Set<Long> eligible = new HashSet<>();
                    for (var entry : reads.entrySet()) if (entry.getValue().join().isEmpty()) eligible.add(entry.getKey());
                    if (eligible.isEmpty()) return new InMemoryGenerator.Batch(tile.x(), tile.z(), List.of(), null);
                    return new InMemoryGenerator(current.level, current.cancelled::get).generate(tile.x(), tile.z(), eligible, config.structures);
                }, worker);
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
        while (chunkIndex < ready.output().size() && finished < config.commitChunksPerTick) {
            ProtoChunk chunk = ready.output().get(chunkIndex);
            // A chunk loaded, edited, or visited while generation was in flight always wins.
            if (real(job.level, chunk.getPos())) { skipped++; chunkIndex++; sectionIndex = 0; finished++; continue; }
            var world = engine.getOrCreate(job.level);
            if (world == null) throw new IllegalStateException("VoxyServer world unavailable");
            world.acquireRef();
            try {
                for (; sectionIndex < chunk.getSections().length; sectionIndex++) {
                    var source = chunk.getSection(sectionIndex);
                    int sectionY = chunk.getMinSection() + sectionIndex;
                    var pos = SectionPos.of(chunk.getPos(), sectionY);
                    var sky = ready.light().getLayerListener(LightLayer.SKY).getDataLayerData(pos);
                    var block = ready.light().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
                    var section = WorldConversionFactory.convert(VoxelizedSection.createEmpty().setPosition(chunk.getPos().x, sectionY, chunk.getPos().z),
                            world.getMapper(), source.getStates(), source.getBiomes(), (x, y, z) -> (byte)(
                                    (sky == null ? 0 : sky.get(x, y, z)) | ((block == null ? 0 : block.get(x, y, z)) << 4)));
                    WorldVoxilizedSectionMipper.mipSection(section, world.getMapper());
                    WorldUpdater.insertUpdate(world, section);
                    sections++;
                    // Yield between sections; do not run an entire terrain tile on the tick thread.
                    if ((System.nanoTime() - started) > 3_000_000L) { sectionIndex++; return; }
                }
                ledger(job.level).add(chunk.getPos().toLong());
                generated++; finished++; chunkIndex++; sectionIndex = 0;
            } finally { world.releaseRef(); }
        }
        if (chunkIndex >= ready.output().size()) {
            batches++; ready = null; nextBatchTick = ticks + config.cooldownTicks;
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
            job = new Job(level, pos.x, pos.z, config.radiusChunks, player.getUUID());
            return;
        }
    }
    public String status() {
        return "Voxy LOD Only: automatic=" + automatic + ", state=" + (stopping ? "stopped" : pending != null ? "generating" : ready != null ? "committing" : job != null ? "scanning" : "idle")
                + ", generated=" + generated + ", skipped=" + skipped + ", sections=" + sections + ", batches=" + batches + ", error=" + lastError;
    }
    public void cancel() {
        if (job != null) job.cancelled.set(true);
        ready = null;
        if (pending == null) job = null;
    }
    public void shutdown() {
        if (stopping) return;
        stopping = true; cancel(); worker.shutdown();
        // Worker never waits for the tick thread or holds a Voxy engine reference.
        try {
            while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                VoxyLodOnly.LOGGER.info("Waiting for private LOD generation stage to drain");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
        pending = null; ready = null; job = null;
        VoxyLodOnly.LOGGER.info("LOD Only generator stopped; no pending tile or Voxy references");
    }
    public void saveCheckpoints() {
        for (var ledger : ledgers.values()) {
            try { ledger.save(); }
            catch (IOException e) { VoxyLodOnly.LOGGER.error("Cannot save LOD completion checkpoint", e); }
        }
    }
}
