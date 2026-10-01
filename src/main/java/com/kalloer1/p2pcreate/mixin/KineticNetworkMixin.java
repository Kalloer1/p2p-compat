package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;

@Mixin(KineticNetwork.class)
public abstract class KineticNetworkMixin {
    // remap=false：目标为 Create 自有方法名（不参与 SRG 重映射，reobf 前后同名）。
    @Inject(method = "calculateCapacity", at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void p2pcreate$addVirtualCapacity(CallbackInfoReturnable<Float> cir) {
        KineticNetwork self = (KineticNetwork) (Object) this;
        if (self.id != null) cir.setReturnValue(cir.getReturnValueF() + KineticBridgeState.INSTANCE.additionalCapacity(self.id));
    }

    @Inject(method = "calculateStress", at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void p2pcreate$addWirelessStress(CallbackInfoReturnable<Float> cir) {
        KineticNetwork self = (KineticNetwork) (Object) this;
        if (self.id != null) cir.setReturnValue(cir.getReturnValueF() + KineticBridgeState.INSTANCE.additionalStress(self.id));
    }
}
