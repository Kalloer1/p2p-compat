package com.kalloer1.p2pcreate.mixin;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.ChannelManager;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

@Mixin(ChannelManager.class)
public abstract class P2PChannelManagerMixin {
    @Inject(method = "deleteChannel", at = @At("HEAD"), require = 1)
    private void p2pcreate$removeChannelState(int channelId, CallbackInfo ci) {
        KineticBridgeState.INSTANCE.removeChannel(channelId);
    }

    @Inject(method = "toggleEndpoint", at = @At("TAIL"), require = 1)
    private void p2pcreate$pruneAfterToggle(Level level, ServerPlayer player, BlockPos pos, Direction face,
            int channelId, Role role, CallbackInfo ci) {
        p2pcreate$prune(player, channelId);
    }

    @Inject(method = "bindChain", at = @At("TAIL"), require = 1)
    private void p2pcreate$pruneAfterBind(Level level, ServerPlayer player, BlockPos origin, Direction face,
            int channelId, Role role, CallbackInfoReturnable<Integer> cir) {
        p2pcreate$prune(player, channelId);
    }

    @Inject(method = "unbindChain", at = @At("TAIL"), require = 1)
    private void p2pcreate$pruneAfterUnbind(Level level, ServerPlayer player, BlockPos origin, Direction face,
            int channelId, ResourceLocation dimension, CallbackInfoReturnable<Integer> cir) {
        p2pcreate$prune(player, channelId);
    }

    @Inject(method = "unbindPositions", at = @At("TAIL"), require = 1)
    private void p2pcreate$pruneAfterUnbindPositions(Level level, Channel channel, Collection<BlockPos> positions,
            ResourceLocation dimension, CallbackInfoReturnable<Integer> cir) {
        if (level instanceof ServerLevel serverLevel) {
            KineticBridgeState.get(serverLevel).pruneChannel(channel.id, channel.members);
        }
    }

    @Inject(method = "removeAirMembers", at = @At("TAIL"), require = 1)
    private void p2pcreate$pruneAfterAirCleanup(ServerPlayer player, int channelId,
            CallbackInfoReturnable<Integer> cir) {
        p2pcreate$prune(player, channelId);
    }

    // serializeFor is the single funnel for every S2C channel snapshot (pushSync, login, actions),
    // so addon state piggybacks on it instead of relying on the base mod calling our own hook.
    @Inject(method = "serializeFor", at = @At("TAIL"), require = 1)
    private void p2pcreate$syncAddonState(ServerPlayer player, CallbackInfoReturnable<CompoundTag> cir) {
        P2PCreateNetwork.syncTo(player);
    }

    private static void p2pcreate$prune(ServerPlayer player, int channelId) {
        Channel channel = ChannelManager.INSTANCE.getChannel(channelId);
        if (channel != null) KineticBridgeState.get(player.serverLevel()).pruneChannel(channelId, channel.members);
    }
}


