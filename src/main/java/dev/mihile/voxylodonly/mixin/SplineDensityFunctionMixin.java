package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.NoiseChunkCache;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DensityFunctions.Spline.class)
public abstract class SplineDensityFunctionMixin {
    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void lodOnly$memoizeSplineHead(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        if (visitor instanceof NoiseChunkCache.MemoizingVisitor memo) {
            DensityFunction cached = memo.getCached((DensityFunction) (Object) this);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        }
    }

    @Inject(method = "mapAll", at = @At("RETURN"))
    private void lodOnly$memoizeSplineReturn(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        if (visitor instanceof NoiseChunkCache.MemoizingVisitor memo) {
            memo.putCached((DensityFunction) (Object) this, cir.getReturnValue());
        }
    }
}
