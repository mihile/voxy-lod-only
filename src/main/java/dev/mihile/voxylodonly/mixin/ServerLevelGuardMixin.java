package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.GenerationGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelGuardMixin {
    @Shadow @Final private net.minecraft.world.level.StructureManager structureManager;
    @Inject(method = "structureManager", at = @At("HEAD"), cancellable = true)
    private void lodOnly$privateStructureLookup(CallbackInfoReturnable<net.minecraft.world.level.StructureManager> ci) {
        var region = GenerationGuard.region();
        if (region != null) ci.setReturnValue(structureManager.forWorldGenRegion(region));
    }
    @Inject(method = "addEntity", at = @At("HEAD"))
    private void lodOnly$guardEntity(Entity entity, CallbackInfoReturnable<Boolean> ci) {
        GenerationGuard.reject("entity spawn");
    }
    @Inject(method = "onBlockStateChange", at = @At("HEAD"), cancellable = true)
    private void lodOnly$noRealPoi(BlockPos pos, BlockState before, BlockState after, CallbackInfo ci) {
        // WorldGenRegion itself calls this hook. Private village beds/workstations must never enter live POI storage.
        if (GenerationGuard.active()) ci.cancel();
    }
}
