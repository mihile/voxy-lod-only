package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.GenerationGuard;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheGuardMixin {
    @Inject(method = "getChunk", at = @At("HEAD"))
    private void lodOnly$guardLiveLoader(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<ChunkAccess> ci) {
        GenerationGuard.reject("server chunk loader");
    }
}
