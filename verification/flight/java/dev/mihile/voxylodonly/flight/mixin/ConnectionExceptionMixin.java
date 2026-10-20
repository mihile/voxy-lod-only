package dev.mihile.voxylodonly.flight.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionExceptionMixin {
    @Inject(method="exceptionCaught",at=@At("HEAD"))
    private void flight$diagnose(ChannelHandlerContext context,Throwable error,CallbackInfo ci) {
        org.slf4j.LoggerFactory.getLogger("flight_verification").error("FLIGHT_CONNECTION_ERROR",error);
    }
}
