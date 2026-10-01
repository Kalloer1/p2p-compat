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
    // remap=false：目标为 Create 自有方法名（不参与 SRG 重映射，reobf 前后同名），
    // Mixin AP 的 MC searge 映射表里没有它，开着 remap 反而会报 "Unable to locate mapping"。
    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void p2pcreate$wirelessGeneration(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        // The claim index is server-only state; in singleplayer KineticBridgeState.INSTANCE is
        // shared, so client block entities must keep Create's answer.
        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        KineticBridgeState.INSTANCE.wirelessSpeed(self).ifPresent(cir::setReturnValue);
    }
}
