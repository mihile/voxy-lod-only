package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.GenerationGuard;
import dev.mihile.voxylodonly.NoiseChunkCache;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseExecutorMixin {
    @Shadow
    protected abstract OptionalInt iterateNoiseColumn(
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            int x,
            int z,
            MutableObject<NoiseColumn> columnObject,
            Predicate<BlockState> blockStatePredicate);

    // 1.20.1 ignores createBiomes' executor and sends doFill to Util.backgroundExecutor.
    // Only private generation runs inline on its already bounded, guarded stage lane.
    @ModifyArg(method = {"createBiomes", "fillFromNoise"}, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            remap = false), index = 1)
    private Executor lodOnly$ownedNoiseExecutor(Executor original) {
        return GenerationGuard.active() ? Runnable::run : original;
    }

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void lodOnly$memoizeBaseHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof NoiseBasedChunkGenerator generator
                && generator.getClass() == NoiseBasedChunkGenerator.class
                && type != null
                && heightAccessor != null
                && randomState != null) {
            int minY = heightAccessor.getMinBuildHeight();
            int height = heightAccessor.getHeight();
            int result = NoiseChunkCache.heightCache().getOrCompute(
                    generator,
                    randomState,
                    minY,
                    height,
                    type.ordinal(),
                    x,
                    z,
                    () -> this.iterateNoiseColumn(heightAccessor, randomState, x, z, null, type.isOpaque()).orElse(minY)
            );
            cir.setReturnValue(result);
        }
    }
}
