package dev.mihile.voxylodonly;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scoped caches that eliminate redundant DensityFunction DAG re-traversals inside a single
 * NoiseChunk construction and memoize repeated identical structure height queries.
 */
public final class NoiseChunkCache {
    public static final AtomicLong memoizedNodes = new AtomicLong();
    public static final AtomicLong heightCacheHits = new AtomicLong();
    public static final AtomicLong heightCacheMisses = new AtomicLong();

    private static final ThreadLocal<HeightCache> HEIGHT_CACHE = ThreadLocal.withInitial(HeightCache::new);

    private NoiseChunkCache() {}

    public static HeightCache heightCache() {
        return HEIGHT_CACHE.get();
    }

    /**
     * Per-NoiseChunk visitor wrapper. Lives strictly for the duration of a single NoiseChunk
     * constructor invocation on a single thread and is never shared across chunks or workers.
     */
    public static final class MemoizingVisitor implements DensityFunction.Visitor {
        private DensityFunction.Visitor delegate;
        private final IdentityHashMap<DensityFunction, DensityFunction> mapped = new IdentityHashMap<>(64);
        private final IdentityHashMap<DensityFunctions.Spline.Coordinate, DensityFunctions.Spline.Coordinate> coordinates =
                new IdentityHashMap<>(32);

        public MemoizingVisitor(DensityFunction.Visitor delegate) {
            this.delegate = delegate;
        }

        public MemoizingVisitor withDelegate(DensityFunction.Visitor nextDelegate) {
            this.delegate = nextDelegate;
            return this;
        }

        public DensityFunction getCached(DensityFunction input) {
            DensityFunction hit = mapped.get(input);
            if (hit != null) {
                memoizedNodes.incrementAndGet();
            }
            return hit;
        }

        public void putCached(DensityFunction input, DensityFunction result) {
            mapped.put(input, result);
            mapped.put(result, result);
        }

        public DensityFunction map(DensityFunction input) {
            DensityFunction hit = mapped.get(input);
            if (hit != null) {
                memoizedNodes.incrementAndGet();
                return hit;
            }
            DensityFunction result = input.mapAll(this);
            mapped.put(input, result);
            mapped.put(result, result);
            return result;
        }

        public DensityFunctions.Spline.Coordinate mapCoordinate(DensityFunctions.Spline.Coordinate coord) {
            DensityFunctions.Spline.Coordinate hit = coordinates.get(coord);
            if (hit != null) {
                memoizedNodes.incrementAndGet();
                return hit;
            }
            DensityFunction inner = coord.function().value();
            DensityFunction mappedInner = map(inner);
            DensityFunctions.Spline.Coordinate result = mappedInner == inner && coord.function() instanceof Holder.Direct<DensityFunction>
                    ? coord
                    : new DensityFunctions.Spline.Coordinate(new Holder.Direct<>(mappedInner));
            coordinates.put(coord, result);
            return result;
        }

        @Override
        public DensityFunction apply(DensityFunction function) {
            DensityFunction result = delegate.apply(function);
            mapped.put(result, result);
            return result;
        }

        @Override
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
            return delegate.visitNoise(noiseHolder);
        }
        public void clear() {
            mapped.clear();
            coordinates.clear();
        }
    }

    /**
     * Direct-mapped per-thread cache of 512 height entries.
     * Stores only primitive arrays and WeakReferences so generator/RandomState instances cannot leak.
     */
    public static final class HeightCache {
        private static final int SIZE = 512;
        private static final int MASK = SIZE - 1;
        private static final int MAX_EPOCH = (1 << 27) - 1;

        private WeakReference<Object> lastGenerator = new WeakReference<>(null);
        private WeakReference<Object> lastRandomState = new WeakReference<>(null);
        private int epoch = 1;
        private final long[] posKeys = new long[SIZE];
        private final long[] metaKeys = new long[SIZE];
        private final int[] heights = new int[SIZE];

        public void clear() {
            lastGenerator = new WeakReference<>(null);
            lastRandomState = new WeakReference<>(null);
            Arrays.fill(metaKeys, 0L);
            epoch = 1;
        }

        public int getOrCompute(
                Object generator,
                Object randomState,
                int minBuildHeight,
                int height,
                int typeOrdinal,
                int x,
                int z,
                java.util.function.IntSupplier compute) {
            if ((short) minBuildHeight != minBuildHeight || (short) height != height || (typeOrdinal & ~0xF) != 0) {
                return compute.getAsInt();
            }
            if (lastGenerator.get() != generator || lastRandomState.get() != randomState) {
                lastGenerator = new WeakReference<>(generator);
                lastRandomState = new WeakReference<>(randomState);
                if (++epoch >= MAX_EPOCH) {
                    Arrays.fill(metaKeys, 0L);
                    epoch = 1;
                }
            }
            long posKey = ChunkPos.asLong(x, z);
            long metaKey = ((long) epoch << 36)
                    | ((long) (typeOrdinal & 0xF) << 32)
                    | ((long) (minBuildHeight & 0xFFFF) << 16)
                    | (height & 0xFFFFL);
            int slot = mixSlot(posKey ^ (typeOrdinal * 0x9E3779B97F4A7C15L));
            if (posKeys[slot] == posKey && metaKeys[slot] == metaKey) {
                heightCacheHits.incrementAndGet();
                return heights[slot];
            }
            heightCacheMisses.incrementAndGet();
            int computed = compute.getAsInt();
            posKeys[slot] = posKey;
            metaKeys[slot] = metaKey;
            heights[slot] = computed;
            return computed;
        }

        private static int mixSlot(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            return (int) k & MASK;
        }
    }
}
