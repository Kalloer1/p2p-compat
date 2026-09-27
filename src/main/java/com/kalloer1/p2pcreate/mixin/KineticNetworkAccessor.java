package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.KineticNetwork;

@Mixin(KineticNetwork.class)
public interface KineticNetworkAccessor {
    @Accessor("currentCapacity")
    float p2pcreate$getCurrentCapacity();
}
