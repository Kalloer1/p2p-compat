package com.kalloer1.p2pae2.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.kalloer1.p2pcompat.network.CompatNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * AE2 侧私有 client-bound 消息（1.2.0 新增，1.20.1 Forge 形态改写）：把桥所合并 ME 网络
 * 的频道用量（已用 / 总量）推给客户端，供 {@code Ae2BridgePanelExtension} 状态卡显示
 * "频道：已用 X / 总 Y（剩 Z）"。消息挂在 compat 自己的 {@code p2p_compat:main}
 * SimpleChannel 上（见 {@link CompatNetwork#ensureRegistered}），命名空间与消息类仍留在
 * {@code com.kalloer1.p2pae2.network}，与 1.21.1 整合版包结构一致。
 *
 * <p>maxChannels 为负数（约定 -1）表示无限（服务器 ChannelMode=INFINITE）；
 * 客户端无数据（未同步过）时按 channelId 查不到，状态卡省略该行。
 */
public final class P2PAe2Network {

    /** 客户端镜像：channelId -> 频道用量。 */
    private static final Map<Integer, ChannelStat> CLIENT_STATS = new HashMap<>();

    private P2PAe2Network() {}

    /** 幂等注册到 compat 通道（双侧；在 mod 构造期、宿主 ae2 守卫内调用）。 */
    public static void register() {
        CompatNetwork.ensureRegistered();
    }

    /** 客户端：状态卡取数；该频道从未同步过时返回 null（面板省略频道行）。 */
    @Nullable
    public static ChannelStat clientStat(int channelId) { return CLIENT_STATS.get(channelId); }

    /** 服务端：全量快照广播（BridgeManager 每秒对齐一次，仅在快照变化时发送）。 */
    public static void broadcast(MinecraftServer server, List<ChannelStat> stats) {
        CompatNetwork.INSTANCE.send(PacketDistributor.ALL.noArg(), new SyncBridgeChannelsMessage(new ArrayList<>(stats)));
    }

    /** 服务端：按玩家单发当前快照（进服/快照同步时补齐，静止网络不触发差量广播）。 */
    public static void sendTo(ServerPlayer player, List<ChannelStat> stats) {
        CompatNetwork.sendToPlayer(player, new SyncBridgeChannelsMessage(new ArrayList<>(stats)));
    }

    /** 单条桥的频道用量快照。maxChannels < 0 ⇒ 无限。 */
    public record ChannelStat(int channelId, int usedChannels, int maxChannels) {
    }

    /**
     * 差量广播消息（1.21.1 StreamCodec payload 的 Forge SimpleChannel 等价物）。
     * 字节布局：varint 数量 + 每条 varint × 3（channelId / usedChannels / maxChannels），
     * 与 1.21.1 版 VAR_INT StreamCodec 一致。
     */
    public static class SyncBridgeChannelsMessage {
        final List<ChannelStat> stats;

        public SyncBridgeChannelsMessage(List<ChannelStat> stats) {
            this.stats = stats;
        }

        public SyncBridgeChannelsMessage(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<ChannelStat> read = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                read.add(new ChannelStat(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            this.stats = read;
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeVarInt(stats.size());
            for (ChannelStat stat : stats) {
                buf.writeVarInt(stat.channelId());
                buf.writeVarInt(stat.usedChannels());
                buf.writeVarInt(stat.maxChannels());
            }
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            CLIENT_STATS.clear();
            for (ChannelStat stat : stats) CLIENT_STATS.put(stat.channelId(), stat);
            ctx.get().setPacketHandled(true);
        }
    }
}
