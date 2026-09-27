package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;

/**
 * Makes a wirelessly driven output block generate rotation, exactly like a Create generator does.
 * Without this, Create's periodic kinetic validation zeroes the speed again because the block's own
 * generated speed is zero, and the target train would never start.
 */
@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin {
    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, require = 1)
    private void p2pcreate$wirelessGeneration(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        // The claim index is server-only state; in singleplayer KineticBridgeState.INSTANCE is
        // shared, so client block entities must keep Create's answer.
        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        KineticBridgeState.INSTANCE.wirelessSpeed(self).ifPresent(cir::setReturnValue);
    }
}
