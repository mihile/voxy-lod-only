package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.VoxyLodOnly;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stop our generator before Fabric invokes VoxyServer's storage shutdown callback. */
@Mixin(value = MinecraftServer.class, priority = 2000)
public abstract class MinecraftServerMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void lodOnly$stopBeforeStorage(CallbackInfo ci) { VoxyLodOnly.stop((MinecraftServer)(Object)this); }
}
