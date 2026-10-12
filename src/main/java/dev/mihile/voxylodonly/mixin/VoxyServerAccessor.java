package dev.mihile.voxylodonly.mixin;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.server.ServerLodEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Voxyserver.class, remap = false)
public interface VoxyServerAccessor {
    @Accessor("lodEngine") ServerLodEngine lodOnly$getEngine();
}
