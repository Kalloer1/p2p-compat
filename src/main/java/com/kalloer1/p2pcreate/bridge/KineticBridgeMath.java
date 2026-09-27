package com.kalloer1.p2pcreate.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.kalloer1.p2p.channel.Channel;

/** Pure arithmetic used by the bridge; deliberately independent from Minecraft classes. */
public final class KineticBridgeMath {
    private KineticBridgeMath() {}

    /** Output demand with the fields needed by every P2P distribution mode. */
    public record OutputDemand(String memberKey, float requestedPower, int priority, double distanceSquared) {}

    public record Allocation(String memberKey, float power) {}

    /**
     * Mechanical power in Create's accounting units. Create scales both a network's capacity sum
     * and its stress sum by the speed each block runs at, so every value the bridge moves around
     * - demand, supply, injected stress - is already stress x rpm and needs no further conversion.
     */
    public static float power(float stress, float rpm) {
        return Math.max(0, stress) * Math.abs(rpm);
    }

    /**
     * Applies the same ordering semantics as P2P's item/fluid/energy transfers, then allocates
     * mechanical power without creating more power than the input networks supplied.
     */
    public static List<Allocation> allocate(Channel.Distribution mode, List<OutputDemand> demands,
            float availablePower, long tick) {
        if (demands.isEmpty()) return List.of();
        List<OutputDemand> ordered = new ArrayList<>(demands);
        if (availablePower <= 0)
            return ordered.stream().map(d -> new Allocation(d.memberKey(), 0)).toList();

        switch (mode) {
            case PRIORITY -> ordered.sort(Comparator.comparingInt(OutputDemand::priority).reversed());
            case NEAREST -> ordered.sort(Comparator.comparingDouble(OutputDemand::distanceSquared));
            case FARTHEST -> ordered.sort(Comparator.comparingDouble(OutputDemand::distanceSquared).reversed());
            case ROUND_ROBIN -> rotate(ordered, Math.floorMod(tick, ordered.size()));
            case SPLIT_EVENLY -> { /* preserve channel member order */ }
        }

        List<Allocation> result = new ArrayList<>(ordered.size());
        if (mode == Channel.Distribution.SPLIT_EVENLY) {
            float each = availablePower / ordered.size();
            for (OutputDemand demand : ordered)
                result.add(new Allocation(demand.memberKey(), Math.min(demand.requestedPower(), each)));
            return result;
        }

        float remaining = availablePower;
        for (OutputDemand demand : ordered) {
            float amount = Math.min(Math.max(0, demand.requestedPower()), remaining);
            result.add(new Allocation(demand.memberKey(), amount));
            remaining -= amount;
        }
        return result;
    }

    private static void rotate(List<OutputDemand> demands, int start) {
        if (start == 0) return;
        List<OutputDemand> copy = new ArrayList<>(demands);
        for (int i = 0; i < demands.size(); i++) demands.set(i, copy.get((start + i) % copy.size()));
    }
}
