package com.kalloer1.p2pcreate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.ChannelManager;
import com.kalloer1.p2p.network.ChannelActionC2SPacket;
import com.kalloer1.p2pcreate.CompatCreate;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Member.key() embeds role-adjacent identity (dim, pos, face), so SET_ROLE, SET_FACE and
 * REMOVE_MEMBER all strand persisted output configs under keys no OUTPUT member owns.
 * Prune the channel's config set right after any of those actions is applied.
 */
@Mixin(ChannelActionC2SPacket.class)
public abstract class ChannelActionC2SPacketMixin {
    @Shadow
    private int channelId;

    @Shadow
    private ChannelActionC2SPacket.Action action;

    @Inject(method = "apply", at = @At("TAIL"), require = 1)
    private void p2pcreate$pruneOrphanOutputConfigs(ServerPlayer player, CallbackInfo ci) {
        if (action != ChannelActionC2SPacket.Action.SET_ROLE
                && action != ChannelActionC2SPacket.Action.SET_FACE
                && action != ChannelActionC2SPacket.Action.REMOVE_MEMBER) return;
        Channel channel = ChannelManager.INSTANCE.getChannel(channelId);
        if (channel == null || channel.type == null || !CompatCreate.STRESS_ID.equals(channel.type.id)) return;
        KineticBridgeState.get(player.serverLevel()).pruneChannel(channelId, channel.members);
    }
}
