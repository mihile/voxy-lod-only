package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.GenerationGuard;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.concurrent.Executor;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseExecutorMixin {
    // 1.20.1 ignores createBiomes' executor and sends doFill to Util.backgroundExecutor.
    // Only private generation runs inline on its already bounded, guarded stage lane.
    @ModifyArg(method = {"createBiomes", "fillFromNoise"}, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            remap = false), index = 1)
    private Executor lodOnly$ownedNoiseExecutor(Executor original) {
        return GenerationGuard.active() ? Runnable::run : original;
    }
}
