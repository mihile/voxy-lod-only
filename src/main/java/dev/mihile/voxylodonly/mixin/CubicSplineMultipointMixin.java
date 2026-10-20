package dev.mihile.voxylodonly.mixin;

import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(CubicSpline.Multipoint.class)
public abstract class CubicSplineMultipointMixin<C, I extends ToFloatFunction<C>> {
    @Shadow
    @Final
    private I coordinate;

    @Shadow
    @Final
    float[] locations;

    @Shadow
    @Final
    private List<CubicSpline<C, I>> values;

    @Shadow
    @Final
    private float[] derivatives;

    @Shadow
    static <C, I extends ToFloatFunction<C>> CubicSpline.Multipoint<C, I> create(
            I coordinate, float[] locations, List<CubicSpline<C, I>> values, float[] derivatives) {
        throw new AssertionError();
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void lodOnly$fastMapAll(
            CubicSpline.CoordinateVisitor<I> visitor,
            CallbackInfoReturnable<CubicSpline<C, I>> cir) {
        I mappedCoord = visitor.visit(this.coordinate);
        int size = this.values.size();
        CubicSpline<C, I>[] mappedValues = new CubicSpline[size];
        boolean changed = mappedCoord != this.coordinate;
        for (int i = 0; i < size; i++) {
            CubicSpline<C, I> orig = this.values.get(i);
            CubicSpline<C, I> mapped = orig.mapAll(visitor);
            mappedValues[i] = mapped;
            if (mapped != orig) {
                changed = true;
            }
        }
        if (!changed) {
            cir.setReturnValue((CubicSpline<C, I>) (Object) this);
            return;
        }
        cir.setReturnValue(create(mappedCoord, this.locations, List.of(mappedValues), this.derivatives));
    }
}
