package com.kalloer1.p2pcreate.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.ChannelManager;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2pcreate.CompatCreate;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcompat.network.CompatNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Create 侧私有消息（1.21.1 StreamCodec payload 的 Forge SimpleChannel 等价改写）：
 * 全部挂在 compat 自己的 {@code p2p_compat:main} SimpleChannel 上（见
 * {@link CompatNetwork}），不再另开通道；命名空间沿用 {@code p2pcreate}（兼容线上
 * 存档 / 与 1.21.1 整合版一致的资源 id）。字节布局与 1.21.1 版逐字节一致。
 */
public final class P2PCreateNetwork {

    /** Direction handling in {@link SetOutputConfigPayload#directionMode()}. */
    public static final int DIR_KEEP = 0;
    public static final int DIR_CLOCKWISE = 1;
    public static final int DIR_COUNTER = 2;
    public static final int DIR_TOGGLE = 3;

    private P2PCreateNetwork() {}

    /** 幂等注册 3 条消息到 compat 通道（create 宿主守卫内调用；必须双侧都执行）。 */
    public static void register() {
        CompatNetwork.registerCreateMessages();
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
        CompatNetwork.sendToPlayer(player, new SyncOutputConfigsPayload(entries));
        // 无限供给标记搭同一次同步下发（打开频道界面 / 登录 / 频道变更都会走到这里）。
        CompatNetwork.sendToPlayer(player, new SyncInfiniteSupplyPayload(List.copyOf(state.getInfiniteSupplyKeys())));
    }

    /** 服务端：无限供给（创造马达）标记的每秒差量广播（CompatCreate tick）。 */
    public static void broadcastInfiniteSupply(List<String> keys) {
        CompatNetwork.INSTANCE.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(),
                new SyncInfiniteSupplyPayload(keys));
    }

    /** public：CompatNetwork（跨包）用方法引用注册 handler。 */
    public static void handleSetOutput(SetOutputConfigPayload payload, Supplier<NetworkEvent.Context> ctx) {
        // consumerMainThread：handler 已在主线程执行（1.21.1 的 enqueueWork 语义）。
        ServerPlayer serverPlayer = ctx.get().getSender();
        if (serverPlayer == null) return;
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
        ctx.get().setPacketHandled(true);
    }

    /**
     * C2S：设置某输出的转速 / 方向。字节布局：varint channelId + utf memberKey +
     * float rpm + varint directionMode（与 1.21.1 StreamCodec.composite 一致）。
     */
    public static class SetOutputConfigPayload {
        final int channelId;
        final String memberKey;
        final float rpm;
        final int directionMode;

        public SetOutputConfigPayload(int channelId, String memberKey, float rpm, int directionMode) {
            this.channelId = channelId;
            this.memberKey = memberKey;
            this.rpm = rpm;
            this.directionMode = directionMode;
        }

        public SetOutputConfigPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readUtf(), buf.readFloat(), buf.readVarInt());
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeVarInt(channelId);
            buf.writeUtf(memberKey);
            buf.writeFloat(rpm);
            buf.writeVarInt(directionMode);
        }

        public int channelId() { return channelId; }

        public String memberKey() { return memberKey; }

        public float rpm() { return rpm; }

        public int directionMode() { return directionMode; }
    }

    /**
     * S2C：整表替换客户端输出配置镜像。字节布局：varint 数量 + 每条
     * varint channelId / utf memberKey / float rpm / bool reverse / bool paused / utf reason
     * （与 1.21.1 ByteBufCodecs.collection 的 VarInt 前缀格式一致）。
     */
    public static class SyncOutputConfigsPayload {
        final List<KineticBridgeState.ClientOutputConfig> entries;

        public SyncOutputConfigsPayload(List<KineticBridgeState.ClientOutputConfig> entries) {
            this.entries = entries;
        }

        public SyncOutputConfigsPayload(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<KineticBridgeState.ClientOutputConfig> read = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                read.add(new KineticBridgeState.ClientOutputConfig(buf.readVarInt(), buf.readUtf(),
                        buf.readFloat(), buf.readBoolean(), buf.readBoolean(), buf.readUtf()));
            }
            this.entries = read;
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeVarInt(entries.size());
            for (KineticBridgeState.ClientOutputConfig entry : entries) {
                buf.writeVarInt(entry.channelId());
                buf.writeUtf(entry.memberKey());
                buf.writeFloat(entry.rpm());
                buf.writeBoolean(entry.reverse());
                buf.writeBoolean(entry.paused());
                buf.writeUtf(entry.reason());
            }
        }

        /** 客户端接收：整表替换输出配置镜像。 */
        public void handle(Supplier<NetworkEvent.Context> ctx) {
            KineticBridgeState.replaceClientOutputConfigs(entries);
            ctx.get().setPacketHandled(true);
        }
    }
}
