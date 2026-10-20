package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.NoiseChunkCache;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DensityFunctions.HolderHolder.class)
public abstract class HolderHolderMixin {
    @Shadow
    @Final
    private Holder<DensityFunction> function;

    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void lodOnly$memoizeHolderMapAll(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        if (visitor instanceof NoiseChunkCache.MemoizingVisitor memo) {
            DensityFunction self = (DensityFunction) (Object) this;
            DensityFunction cached = memo.getCached(self);
            if (cached != null) {
                cir.setReturnValue(cached);
                return;
            }
            DensityFunction mappedInner = memo.map(this.function.value());
            DensityFunction result = memo.apply(new DensityFunctions.HolderHolder(new Holder.Direct<>(mappedInner)));
            memo.putCached(self, result);
            cir.setReturnValue(result);
        }
    }
}
