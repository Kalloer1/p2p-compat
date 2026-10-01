package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.KineticNetwork;

@Mixin(value = KineticNetwork.class, remap = false)
public interface KineticNetworkAccessor {
    @Accessor(value = "currentCapacity", remap = false)
    float p2pcreate$getCurrentCapacity();
}
