package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.NoiseChunkCache;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Unique
    private NoiseChunkCache.MemoizingVisitor lodOnly$visitor;

    @Shadow
    protected abstract DensityFunction wrapNew(DensityFunction function);

    @Inject(method = "wrap", at = @At("HEAD"), cancellable = true)
    private void lodOnly$fastWrapNonMarker(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        // Only DensityFunctions.Marker allocates stateful per-NoiseChunk wrappers in wrapNew.
        // All other nodes return existing fields or the function itself without needing HashMap.computeIfAbsent.
        if (!(function instanceof DensityFunctions.MarkerOrMarked)) {
            cir.setReturnValue(this.wrapNew(function));
        }
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"
            ),
            index = 0
    )
    private DensityFunction.Visitor lodOnly$wrapRouterVisitor(DensityFunction.Visitor original) {
        var visitor = new NoiseChunkCache.MemoizingVisitor(original);
        this.lodOnly$visitor = visitor;
        return visitor;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"
            ),
            index = 0
    )
    private DensityFunction.Visitor lodOnly$wrapFinalDensityVisitor(DensityFunction.Visitor original) {
        var visitor = this.lodOnly$visitor;
        this.lodOnly$visitor = null;
        return visitor != null ? visitor.withDelegate(original) : original;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodOnly$clearVisitor(CallbackInfo ci) {
        this.lodOnly$visitor = null;
    }
}
