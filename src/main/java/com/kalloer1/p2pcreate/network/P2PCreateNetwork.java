package com.kalloer1.p2pcreate.network;

import java.util.ArrayList;
import java.util.List;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.ChannelManager;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2pcreate.CompatCreate;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Private payload channel owned by the addon; P2P itself remains binary-compatible and unchanged. */
public final class P2PCreateNetwork {
    private static final String VERSION = "2";

    /** Direction handling in {@link SetOutputConfigPayload#directionMode()}. */
    public static final int DIR_KEEP = 0;
    public static final int DIR_CLOCKWISE = 1;
    public static final int DIR_COUNTER = 2;
    public static final int DIR_TOGGLE = 3;

    private P2PCreateNetwork() {}

    public static void register(IEventBus bus) { bus.addListener(P2PCreateNetwork::registerPayloads); }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(SetOutputConfigPayload.TYPE, SetOutputConfigPayload.CODEC, P2PCreateNetwork::handleSetOutput);
        registrar.playToClient(SyncOutputConfigsPayload.TYPE, SyncOutputConfigsPayload.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        KineticBridgeState.replaceClientOutputConfigs(payload.entries())));
        // 1.2.0 新增：无限供给（创造马达）标记同步。旧 payload 类与 wire 格式不动。
        registrar.playToClient(SyncInfiniteSupplyPayload.TYPE, SyncInfiniteSupplyPayload.CODEC,
                SyncInfiniteSupplyPayload::handle);
    }

    public static void syncTo(ServerPlayer player) {
        KineticBridgeState state = KineticBridgeState.get(player.serverLevel());
        List<KineticBridgeState.ClientOutputConfig> entries = new ArrayList<>();
        for (Channel channel : ChannelManager.INSTANCE.getAllChannels()) {
            if (channel.type == null || !CompatCreate.STRESS_ID.equals(channel.type.id)) continue;
            for (Member member : channel.members) {
                if (member.role != Role.OUTPUT) continue;
                var config = state.getOutputConfig(channel.id, member, KineticBridgeState.maxRotationSpeed());
                String reason = state.getOutputPauseReason(channel.id, member.key());
                entries.add(new KineticBridgeState.ClientOutputConfig(channel.id, member.key(), config.rpm(),
                        config.reverse(), !reason.isEmpty(), reason));
            }
        }
        PacketDistributor.sendToPlayer(player, new SyncOutputConfigsPayload(entries));
        // 无限供给标记搭同一次同步下发（打开频道界面 / 登录 / 频道变更都会走到这里）。
        PacketDistributor.sendToPlayer(player,
                new SyncInfiniteSupplyPayload(List.copyOf(state.getInfiniteSupplyKeys())));
    }

    private static void handleSetOutput(SetOutputConfigPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                var channel = ChannelManager.INSTANCE.getChannel(payload.channelId());
                if (channel == null || !ChannelManager.INSTANCE.canEditChannel(serverPlayer, channel)) return;
                var member = channel.members.stream().filter(m -> m.key().equals(payload.memberKey())).findFirst().orElse(null);
                if (member == null || member.role != Role.OUTPUT) return;
                KineticBridgeState state = KineticBridgeState.get(serverPlayer.serverLevel());
                float max = KineticBridgeState.maxRotationSpeed();
                // Incremental merge: rpm < 0 keeps the stored value, directionMode keeps/toggles it,
                // so a rpm-only or direction-only edit never rewrites the other field from a stale cache.
                var existing = state.getOutputConfig(channel.id, member, max);
                float rpm = payload.rpm() < 0 ? existing.rpm() : payload.rpm();
                boolean reverse = switch (payload.directionMode()) {
                    case DIR_CLOCKWISE -> false;
                    case DIR_COUNTER -> true;
                    case DIR_TOGGLE -> !existing.reverse();
                    default -> existing.reverse();
                };
                state.setOutputConfig(channel.id, member.key(), rpm, reverse, max);
                syncTo(serverPlayer);
            }
        });
    }

    public record SetOutputConfigPayload(int channelId, String memberKey, float rpm, int directionMode)
            implements CustomPacketPayload {
        public static final Type<SetOutputConfigPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CompatCreate.MODID, "set_output_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetOutputConfigPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetOutputConfigPayload::channelId,
                ByteBufCodecs.STRING_UTF8, SetOutputConfigPayload::memberKey,
                ByteBufCodecs.FLOAT, SetOutputConfigPayload::rpm,
                ByteBufCodecs.VAR_INT, SetOutputConfigPayload::directionMode,
                SetOutputConfigPayload::new);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncOutputConfigsPayload(List<KineticBridgeState.ClientOutputConfig> entries)
            implements CustomPacketPayload {
        public static final Type<SyncOutputConfigsPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CompatCreate.MODID, "sync_output_configs"));
        private static final StreamCodec<RegistryFriendlyByteBuf, KineticBridgeState.ClientOutputConfig> ENTRY_CODEC =
                StreamCodec.of((buf, entry) -> {
                    buf.writeVarInt(entry.channelId());
                    buf.writeUtf(entry.memberKey());
                    buf.writeFloat(entry.rpm());
                    buf.writeBoolean(entry.reverse());
                    buf.writeBoolean(entry.paused());
                    buf.writeUtf(entry.reason());
                }, buf -> new KineticBridgeState.ClientOutputConfig(
                        buf.readVarInt(), buf.readUtf(), buf.readFloat(),
                        buf.readBoolean(), buf.readBoolean(), buf.readUtf()));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncOutputConfigsPayload> CODEC =
                ByteBufCodecs.collection(ArrayList::new, ENTRY_CODEC).map(
                        list -> new SyncOutputConfigsPayload(new ArrayList<>(list)),
                        payload -> new ArrayList<>(payload.entries()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}



