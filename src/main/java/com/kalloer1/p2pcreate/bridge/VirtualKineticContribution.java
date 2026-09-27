package com.kalloer1.p2pcreate.bridge;

import java.util.Objects;

/**
 * Immutable contribution of one P2P output to one Create kinetic network: the mechanical power
 * (stress x rpm, ie Create's own accounting unit) the bridge injects as capacity there, and the
 * speed its wireless generator imposes.
 */
public record VirtualKineticContribution(
        int channelId,
        String memberKey,
        long networkId,
        float suppliedCapacity,
        float targetSpeed) {

    public VirtualKineticContribution {
        Objects.requireNonNull(memberKey, "memberKey");
        if (!Float.isFinite(suppliedCapacity) || suppliedCapacity < 0) suppliedCapacity = 0;
        if (!Float.isFinite(targetSpeed)) targetSpeed = 0;
    }
}
