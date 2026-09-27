package com.kalloer1.p2pae2.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * AE2 侧私有 client-bound payload（1.2.0 新增）：把桥所合并 ME 网络的频道用量
 * （已用 / 总量）推给客户端，供 {@code Ae2BridgePanelExtension} 状态卡显示
 * "频道：已用 X / 总 Y（剩 Z）"。命名空间 {@code p2pae2}，注册写法参照
 * P2PCreateNetwork；服务端取数见 BridgeManager.syncChannelStats。
 *
 * <p>maxChannels 为负数（约定 -1）表示无限（服务器 ChannelMode=INFINITE）；
 * 客户端无数据（未同步过）时按 channelId 查不到，状态卡省略该行。
 */
public final class P2PAe2Network {
    private static final String VERSION = "1";
    private static final String NAMESPACE = "p2pae2";

    /** 客户端镜像：channelId -> 频道用量。 */
    private static final Map<Integer, ChannelStat> CLIENT_STATS = new HashMap<>();

    private P2PAe2Network() {}

    public static void register(IEventBus bus) { bus.addListener(P2PAe2Network::registerPayloads); }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(SyncBridgeChannelsPayload.TYPE, SyncBridgeChannelsPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    CLIENT_STATS.clear();
                    for (ChannelStat stat : payload.stats()) CLIENT_STATS.put(stat.channelId(), stat);
                }));
    }

    /** 客户端：状态卡取数；该频道从未同步过时返回 null（面板省略频道行）。 */
    @Nullable
    public static ChannelStat clientStat(int channelId) { return CLIENT_STATS.get(channelId); }

    /** 服务端：全量快照广播（BridgeManager 每秒对齐一次，仅在快照变化时发送）。 */
    public static void broadcast(MinecraftServer server, List<ChannelStat> stats) {
        PacketDistributor.sendToAllPlayers(new SyncBridgeChannelsPayload(new ArrayList<>(stats)));
    }

    /** 服务端：按玩家单发当前快照（进服/快照同步时补齐，静止网络不触发差量广播）。 */
    public static void sendTo(ServerPlayer player, List<ChannelStat> stats) {
        PacketDistributor.sendToPlayer(player, new SyncBridgeChannelsPayload(new ArrayList<>(stats)));
    }

    /** 单条桥的频道用量快照。maxChannels < 0 ⇒ 无限。 */
    public record ChannelStat(int channelId, int usedChannels, int maxChannels) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ChannelStat> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ChannelStat::channelId,
                ByteBufCodecs.VAR_INT, ChannelStat::usedChannels,
                ByteBufCodecs.VAR_INT, ChannelStat::maxChannels,
                ChannelStat::new);
    }

    public record SyncBridgeChannelsPayload(List<ChannelStat> stats) implements CustomPacketPayload {
        public static final Type<SyncBridgeChannelsPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(NAMESPACE, "sync_bridge_channels"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncBridgeChannelsPayload> CODEC =
                ByteBufCodecs.collection(ArrayList::new, ChannelStat.CODEC).map(
                        list -> new SyncBridgeChannelsPayload(new ArrayList<>(list)),
                        payload -> new ArrayList<>(payload.stats()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
