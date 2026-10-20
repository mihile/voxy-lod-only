package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.NoiseChunkCache;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DensityFunctions.Spline.Coordinate.class)
public abstract class SplineCoordinateMixin {
    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void lodOnly$memoizeCoordinateMapAll(
            DensityFunction.Visitor visitor,
            CallbackInfoReturnable<DensityFunctions.Spline.Coordinate> cir) {
        if (visitor instanceof NoiseChunkCache.MemoizingVisitor memo) {
            cir.setReturnValue(memo.mapCoordinate((DensityFunctions.Spline.Coordinate) (Object) this));
        }
    }
}
