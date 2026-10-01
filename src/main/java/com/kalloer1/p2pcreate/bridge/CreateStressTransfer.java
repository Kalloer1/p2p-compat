package com.kalloer1.p2pcreate.bridge;

import java.util.List;
import java.util.Map;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2p.transfer.TransferCapabilityCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** P2P medium callback. It turns a channel tick into Create network contributions. */
public final class CreateStressTransfer {
    private CreateStressTransfer() {}

    public static int transfer(TransferCapabilityCache cache, Channel channel,
            Map<ResourceLocation, Level> levels, List<Member> inputs, List<Member> outputs,
            int rate, int tick) {
        if (inputs.isEmpty() || outputs.isEmpty() || rate <= 0) {
            KineticBridgeState.INSTANCE.clearLiveChannel(channel.id);
            return 0;
        }
        return KineticBridgeState.INSTANCE.applyChannel(channel, levels, inputs, outputs, rate, tick);
    }

    public static boolean isCreateEndpoint(Level level, Member member, Role expected) {
        return KineticBridgeState.INSTANCE.resolveKinetic(level, member, expected) != null;
    }
}
