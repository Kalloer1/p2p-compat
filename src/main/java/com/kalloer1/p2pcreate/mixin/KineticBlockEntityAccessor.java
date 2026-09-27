package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Client-side kinetic readouts. The client-side {@link com.simibubi.create.content.kinetics.KineticNetwork}
 * instances are phantoms whose currentCapacity/currentStress are never synced; the synced values live
 * on the block entity fields instead.
 */
@Mixin(KineticBlockEntity.class)
public interface KineticBlockEntityAccessor {
    @Accessor("capacity")
    float p2pcreate$getSyncedCapacity();

    @Accessor("stress")
    float p2pcreate$getSyncedStress();
}
