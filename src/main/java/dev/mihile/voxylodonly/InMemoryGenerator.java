package dev.mihile.voxylodonly;

import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns only ProtoChunks. Never registers a ChunkHolder, ticket, entity, or region-file writer. */
public final class InMemoryGenerator {
    static final AtomicInteger activeGridChunks = new AtomicInteger(), peakGridChunks = new AtomicInteger();
    static final AtomicLong discardedCpuNanos = new AtomicLong();
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    public static final int TILE = 4;
    private final ServerLevel level;
    private final BooleanSupplier cancelled;
    private final ChunkGenerator generator;
    private ProtoChunk[] grid;
    private int gridMinX, gridMinZ, gridMaxX, gridMaxZ, gridWidth;
    private final Executor stageExecutor;
    private final int parallelism, tileSize;
    private GenerationTiming timing;
    private final boolean releaseContext;
    private Runnable checkpoint = () -> {};
    private int gridAllocated;
    private final AtomicLong batchCpuNanos = new AtomicLong();

    public record ChunkLightSnapshot(DataLayer[] sky, DataLayer[] block) {
        public static ChunkLightSnapshot extract(ProtoChunk chunk, LevelLightEngine light) {
            int sectionCount = chunk.getSections().length;
            int minSection = chunk.getMinSection();
            ChunkPos pos = chunk.getPos();
            var skyListener = light.getLayerListener(LightLayer.SKY);
            var blockListener = light.getLayerListener(LightLayer.BLOCK);
            DataLayer[] skyLayers = new DataLayer[sectionCount];
            DataLayer[] blockLayers = new DataLayer[sectionCount];
            for (int i = 0; i < sectionCount; i++) {
                SectionPos secPos = SectionPos.of(pos, minSection + i);
                skyLayers[i] = skyListener.getDataLayerData(secPos);
                blockLayers[i] = blockListener.getDataLayerData(secPos);
            }
            return new ChunkLightSnapshot(skyLayers, blockLayers);
        }
    }

    public record Batch(
            int tileX,
            int tileZ,
            List<ProtoChunk> output,
            LevelLightEngine light,
            List<ChunkLightSnapshot> lightSnapshots,
            long cpuNanos) {
        public Batch(int tileX, int tileZ, List<ProtoChunk> output, LevelLightEngine light) {
            this(tileX, tileZ, output, light, null, 0L);
        }

        public Batch(int tileX, int tileZ, List<ProtoChunk> output, LevelLightEngine light, List<ChunkLightSnapshot> lightSnapshots) {
            this(tileX, tileZ, output, light, lightSnapshots, 0L);
        }
    }

    public InMemoryGenerator(ServerLevel level, BooleanSupplier cancelled) {
        this(level, cancelled, Runnable::run, 1, TILE);
    }

    public InMemoryGenerator(ServerLevel level, BooleanSupplier cancelled, Executor stageExecutor, int parallelism, int tileSize) {
        this(level, cancelled, stageExecutor, parallelism, tileSize, true);
    }

    public InMemoryGenerator(ServerLevel level, BooleanSupplier cancelled, Executor stageExecutor, int parallelism, int tileSize, boolean releaseContext) {
        this.releaseContext = releaseContext;
        this.level = level;
        this.cancelled = cancelled;
        this.generator = level.getChunkSource().getGenerator();
        this.stageExecutor = stageExecutor;
        this.parallelism = parallelism;
        this.tileSize = tileSize;
        if (!(generator instanceof NoiseBasedChunkGenerator) && !(generator instanceof FlatLevelSource)) {
            throw new IllegalArgumentException("Unsupported custom ChunkGenerator: " + generator.getClass().getName());
        }
    }

    private static long currentThreadCpuNanos() {
        return THREAD_CPU != null && THREAD_CPU.isThreadCpuTimeSupported() && THREAD_CPU.isThreadCpuTimeEnabled()
                ? Math.max(0L, THREAD_CPU.getCurrentThreadCpuTime())
                : 0L;
    }

    private void recordWorkerCpu(long cpuStart) {
        if (cpuStart > 0L) {
            long delta = currentThreadCpuNanos() - cpuStart;
            if (delta > 0L) {
                batchCpuNanos.addAndGet(delta);
            }
        }
    }

    public Batch generate(int tileX, int tileZ, Set<Long> eligible, boolean structures) {
        int tileMinX = Math.multiplyExact(tileX, tileSize), tileMinZ = Math.multiplyExact(tileZ, tileSize);
        return generateAt(tileMinX, tileMinZ, eligible, structures);
    }

    /** Absolute chunk origin permits bounded patches anchored to the request, not a world grid. */
    public Batch generateAt(int tileMinX, int tileMinZ, Set<Long> eligible, boolean structures) {
        boolean completed = false;
        try {
            Batch batch = generateUnchecked(tileMinX, tileMinZ, eligible, structures);
            completed = true;
            return batch;
        } finally {
            if (!completed) {
                discardedCpuNanos.addAndGet(batchCpuNanos.get());
            }
            activeGridChunks.addAndGet(-gridAllocated);
            gridAllocated = 0;
            if (releaseContext) {
                grid = null;
            }
        }
    }

    InMemoryGenerator checkpoint(Runnable checkpoint) {
        this.checkpoint = checkpoint;
        return this;
    }

    private Batch generateUnchecked(int tileMinX, int tileMinZ, Set<Long> eligible, boolean structures) {
        int tileX = tileMinX, tileZ = tileMinZ;
        timing = new GenerationTiming(tileMinX, tileMinZ);
        if (eligible.isEmpty()) return new Batch(tileX, tileZ, List.of(), null, List.of(), 0L);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : eligible) {
            ChunkPos pos = new ChunkPos(key);
            if (pos.x < tileMinX || pos.x >= tileMinX + tileSize || pos.z < tileMinZ || pos.z >= tileMinZ + tileSize)
                throw new IllegalArgumentException("Eligible chunk outside tile: " + pos);
            minX = Math.min(minX, pos.x);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxZ = Math.max(maxZ, pos.z);
        }
        // Full/cropped Nether decoration comparisons produced different blocks.
        // Cropping changes that context. Keep the
        // full patch outside the tested Overworld domain; only eligible output is saved.
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            minX = tileMinX;
            minZ = tileMinZ;
            maxX = tileMinX + tileSize - 1;
            maxZ = tileMinZ + tileSize - 1;
        }
        int terrainMinX = minX - 3, terrainMinZ = minZ - 3;
        int terrainMaxX = maxX + 3, terrainMaxZ = maxZ + 3;
        gridMinX = terrainMinX - 8;
        gridMinZ = terrainMinZ - 8;
        gridMaxX = terrainMaxX + 8;
        gridMaxZ = terrainMaxZ + 8;
        gridWidth = gridMaxX - gridMinX + 1;
        int gridHeight = gridMaxZ - gridMinZ + 1;
        int totalGrid = gridWidth * gridHeight;
        grid = new ProtoChunk[totalGrid];
        List<ProtoChunk> allChunks = new ArrayList<>(totalGrid);
        try (var ignored = GenerationGuard.enter()) {
            var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            try (var phase = timing.phase("allocation", totalGrid)) {
                long cpuStart = currentThreadCpuNanos();
                try {
                    int idx = 0;
                    for (int z = gridMinZ; z <= gridMaxZ; z++) {
                        check();
                        for (int x = gridMinX; x <= gridMaxX; x++) {
                            if (cancelled.getAsBoolean()) throw new CancellationException();
                            ProtoChunk chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, level, biomeRegistry, null);
                            grid[idx++] = chunk;
                            allChunks.add(chunk);
                            gridAllocated++;
                            peakGridChunks.accumulateAndGet(activeGridChunks.incrementAndGet(), Math::max);
                        }
                    }
                } finally {
                    recordWorkerCpu(cpuStart);
                }
            }
            stage("structures", allChunks, chunk -> {
                if (structures && level.getServer().getWorldData().worldGenOptions().generateStructures()) {
                    generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                            level.structureManager(), chunk, level.getStructureManager());
                }
                // Deliberately no ServerLevel.onStructureStartsAvailable: that belongs to real chunks.
                chunk.setStatus(ChunkStatus.STRUCTURE_STARTS);
            });
            List<ProtoChunk> terrain = square(terrainMinX, terrainMinZ, terrainMaxX, terrainMaxZ);
            stage("references", terrain, chunk -> {
                var region = region(chunk, ChunkStatus.STRUCTURE_REFERENCES, -1);
                generator.createReferences(region, level.structureManager().forWorldGenRegion(region), chunk);
                chunk.setStatus(ChunkStatus.STRUCTURE_REFERENCES);
            });
            stage("biomes", terrain, chunk -> {
                var region = region(chunk, ChunkStatus.BIOMES, -1);
                await(generator.createBiomes(Runnable::run, level.getChunkSource().randomState(),
                        Blender.of(region), level.structureManager().forWorldGenRegion(region), chunk));
                chunk.setStatus(ChunkStatus.BIOMES);
            });
            stage("noise", terrain, chunk -> {
                var region = region(chunk, ChunkStatus.NOISE, 0);
                await(generator.fillFromNoise(Runnable::run, Blender.of(region),
                        level.getChunkSource().randomState(), level.structureManager().forWorldGenRegion(region), chunk));
                chunk.setStatus(ChunkStatus.NOISE);
            });
            stage("surface", terrain, chunk -> {
                var region = region(chunk, ChunkStatus.SURFACE, 0);
                generator.buildSurface(region, level.structureManager().forWorldGenRegion(region), level.getChunkSource().randomState(), chunk);
                chunk.setStatus(ChunkStatus.SURFACE);
            });
            stage("carvers", terrain, chunk -> {
                var region = region(chunk, ChunkStatus.CARVERS, 0);
                generator.applyCarvers(region, level.getSeed(), level.getChunkSource().randomState(),
                        region.getBiomeManager(), level.structureManager().forWorldGenRegion(region), chunk, GenerationStep.Carving.AIR);
                chunk.setStatus(ChunkStatus.CARVERS);
                Heightmap.primeHeightmaps(chunk, ChunkStatus.POST_FEATURES);
            });
            List<ProtoChunk> decorated = square(minX - 2, minZ - 2, maxX + 2, maxZ + 2);
            try (var phase = timing.phase("decoration", decorated.size())) {
                long phaseCpuStart = phase.startWorker();
                long threadCpuStart = currentThreadCpuNanos();
                try {
                    // Features write across chunk borders inside a 3x3 region; keep decoration sequential.
                    for (ProtoChunk chunk : decorated) {
                        check();
                        var region = region(chunk, ChunkStatus.FEATURES, 1);
                        try (var decoration = GenerationGuard.enter(region)) {
                            // Some vanilla structure entities ask getLevel().structureManager(). Route
                            // that lookup back into this private region instead of loading real chunks.
                            generator.applyBiomeDecoration(region, chunk, level.structureManager());
                        }
                        chunk.setStatus(ChunkStatus.FEATURES);
                    }
                } finally {
                    recordWorkerCpu(threadCpuStart);
                    phase.endWorker(phaseCpuStart);
                }
            }
            LevelLightEngine light;
            try (var phase = timing.phase("lighting", terrain.size())) {
                long phaseCpuStart = phase.startWorker();
                long threadCpuStart = currentThreadCpuNanos();
                try {
                    for (ProtoChunk chunk : terrain) {
                        check();
                        chunk.initializeLightSources();
                    }
                    light = createLightEngine();
                    for (ProtoChunk chunk : terrain) {
                        check();
                        for (int i = 0; i < chunk.getSections().length; i++) {
                            light.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getMinSection() + i),
                                    chunk.getSection(i).hasOnlyAir());
                        }
                        light.setLightEnabled(chunk.getPos(), true);
                        light.propagateLightSources(chunk.getPos());
                    }
                    while (light.hasLightWork()) {
                        check();
                        light.runLightUpdates();
                    }
                } finally {
                    recordWorkerCpu(threadCpuStart);
                    phase.endWorker(phaseCpuStart);
                }
            }
            List<ProtoChunk> output = new ArrayList<>(eligible.size());
            List<ChunkLightSnapshot> outputLight = new ArrayList<>(eligible.size());
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (eligible.contains(ChunkPos.asLong(x, z))) {
                        ProtoChunk chunk = chunkAt(x, z);
                        output.add(chunk);
                        outputLight.add(ChunkLightSnapshot.extract(chunk, light));
                    }
                }
            }
            // Extract compact SKY/BLOCK DataLayer snapshots for output chunks and release the
            // LevelLightEngine (and all non-output halo light data) immediately when releaseContext is true.
            if (releaseContext) {
                grid = null;
                return new Batch(tileX, tileZ, output, null, outputLight, batchCpuNanos.get());
            }
            return new Batch(tileX, tileZ, output, light, outputLight, batchCpuNanos.get());
        }
    }

    private LevelLightEngine createLightEngine() {
        return new LevelLightEngine(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                ProtoChunk[] localGrid = grid;
                if (localGrid == null || x < gridMinX || x > gridMaxX || z < gridMinZ || z > gridMaxZ) return null;
                return localGrid[(z - gridMinZ) * gridWidth + (x - gridMinX)];
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        }, true, level.dimensionType().hasSkyLight());
    }

    private void stage(String name, List<ProtoChunk> work, Consumer<ProtoChunk> action) {
        try (var phase = timing.phase(name, work.size())) {
            runStageWork(phase, work, action);
        }
    }

    private void runStageWork(GenerationTiming.Scope phase, List<ProtoChunk> work, Consumer<ProtoChunk> action) {
        int count = Math.min(parallelism, work.size());
        if (count <= 1) {
            long phaseCpuStart = phase.startWorker();
            long threadCpuStart = currentThreadCpuNanos();
            try (var guard = GenerationGuard.enter()) {
                for (int i = 0; i < work.size(); i++) {
                    check();
                    action.accept(work.get(i));
                }
            } finally {
                recordWorkerCpu(threadCpuStart);
                phase.endWorker(phaseCpuStart);
            }
            return;
        }
        var next = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>(count);
        RuntimeException rejected = null;
        for (int worker = 0; worker < count; worker++) {
            try {
                futures.add(CompletableFuture.runAsync(() -> {
                    long phaseCpuStart = phase.startWorker();
                    long threadCpuStart = currentThreadCpuNanos();
                    try (var guard = GenerationGuard.enter()) {
                        // Chunk costs vary with terrain/structures. A free worker takes the next
                        // chunk instead of waiting for a slower fixed lane at the stage barrier.
                        for (int i; (i = next.getAndIncrement()) < work.size();) {
                            check();
                            action.accept(work.get(i));
                        }
                    } finally {
                        recordWorkerCpu(threadCpuStart);
                        phase.endWorker(phaseCpuStart);
                    }
                }, stageExecutor));
            } catch (RuntimeException error) {
                rejected = error;
                break;
            }
        }
        // A submission failure must not let earlier siblings outlive the private maps.
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        if (rejected != null) throw rejected;
        check();
    }

    private WorldGenRegion region(ProtoChunk chunk, ChunkStatus status, int writeRadius) {
        var pos = chunk.getPos();
        int startOffset = (pos.z - 8 - gridMinZ) * gridWidth + (pos.x - 8 - gridMinX);
        return new PrivateRegion(level, new GridNeighborhoodList(grid, gridWidth, startOffset), status, writeRadius);
    }

    /**
     * Zero-copy 17x17 RandomAccess view over the flat ProtoChunk[] grid.
     * Avoids allocating 196 separate ChunkAccess[289] arrays (56,644 references) per tile.
     */
    static final class GridNeighborhoodList extends AbstractList<ChunkAccess> implements RandomAccess {
        private static final int SIDE = 17;
        private static final int SIZE = SIDE * SIDE;
        private final ProtoChunk[] grid;
        private final int gridWidth;
        private final int startOffset;

        GridNeighborhoodList(ProtoChunk[] grid, int gridWidth, int startOffset) {
            this.grid = grid;
            this.gridWidth = gridWidth;
            this.startOffset = startOffset;
        }

        @Override
        public ChunkAccess get(int index) {
            if (index < 0 || index >= SIZE) {
                throw new IndexOutOfBoundsException(index);
            }
            return grid[startOffset + (index / SIDE) * gridWidth + (index % SIDE)];
        }

        @Override
        public int size() {
            return SIZE;
        }
    }

    private static final class PrivateRegion extends WorldGenRegion {
        PrivateRegion(ServerLevel level, List<ChunkAccess> chunks, ChunkStatus status, int radius) {
            super(level, chunks, status, radius);
        }

        @Override
        public boolean isOldChunkAround(ChunkPos pos, int radius) {
            // Every chunk in this region is a newly allocated ProtoChunk. The vanilla implementation
            // delegates to ChunkMap/IOWorker and scans disk regions, creating empty .mca files.
            return false;
        }
    }

    private ProtoChunk chunkAt(int x, int z) {
        if (x < gridMinX || x > gridMaxX || z < gridMinZ || z > gridMaxZ)
            throw new IllegalStateException("Missing private generation neighbor " + x + "," + z);
        ProtoChunk chunk = grid[(z - gridMinZ) * gridWidth + (x - gridMinX)];
        if (chunk == null) throw new IllegalStateException("Missing private generation neighbor " + x + "," + z);
        return chunk;
    }

    private List<ProtoChunk> square(int x1, int z1, int x2, int z2) {
        List<ProtoChunk> result = new ArrayList<>((x2 - x1 + 1) * (z2 - z1 + 1));
        for (int z = z1; z <= z2; z++)
            for (int x = x1; x <= x2; x++) {
                result.add(chunkAt(x, z));
            }
        return result;
    }

    private <T> T await(CompletableFuture<T> future) {
        // Drain the vanilla stage even after cancellation, so its asynchronous work cannot outlive us.
        T result = future.join();
        if (cancelled.getAsBoolean()) throw new CancellationException();
        return result;
    }

    private void check() {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        checkpoint.run();
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }
}
