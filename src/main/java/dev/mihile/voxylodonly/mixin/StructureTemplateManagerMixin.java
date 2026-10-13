package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.StructureCompatibility;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerMixin {
    // ModernFix overwrites loadFromResource. Use its enclosing tryLoad path
    // so the repair always runs before the template enters the shared cache.
    @Inject(method="tryLoad",at=@At("RETURN"))
    private void lodOnly$repairKnownResources(ResourceLocation id,CallbackInfoReturnable<Optional<StructureTemplate>> ci) {
        ci.getReturnValue().ifPresent(template -> StructureCompatibility.repair(id,template));
    }
}
