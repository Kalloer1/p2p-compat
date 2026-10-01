package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Client-side kinetic readouts. The client-side {@link com.simibubi.create.content.kinetics.KineticNetwork}
 * instances are phantoms whose currentCapacity/currentStress are never synced; the synced values live
 * on the block entity fields instead.
 *
 * <p>mixin 目标均为 Create 宿主类；成员已对 create-1.20.1-6.0.8 逐一核对
 * （capacity/stress 为 protected float，getGeneratedSpeed 为 public float）。
 * remap=false：字段为 Create 自有名，不参与 SRG 重映射。
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public interface KineticBlockEntityAccessor {
    @Accessor(value = "capacity", remap = false)
    float p2pcreate$getSyncedCapacity();

    @Accessor(value = "stress", remap = false)
    float p2pcreate$getSyncedStress();
}
