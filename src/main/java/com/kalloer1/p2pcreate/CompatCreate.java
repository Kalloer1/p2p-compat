package com.kalloer1.p2pcreate;

import java.util.List;
import java.util.Set;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.api.ChannelDeletedEvent;
import com.kalloer1.p2p.api.ChannelMembersChangedEvent;
import com.kalloer1.p2p.api.ChannelScreenExtensions;
import com.kalloer1.p2p.api.ChannelSyncedEvent;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.medium.Mediums;
import com.kalloer1.p2pcreate.bridge.CreateStressTransfer;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.client.StressPanelExtension;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

/** Create compat: registered by the unified p2p_compat entry only when the Create mod is loaded. */
public final class CompatCreate {
    public static final String MODID = "p2pcreate";
    public static final String STRESS_ID = "create:stress";
    public static final Medium CREATE_STRESS = new Medium(
            STRESS_ID, 0xFFB46B35, "p2p.medium.create_stress", 5, CreateStressTransfer::transfer);

    private static Set<String> lastInfiniteSync = Set.of();

    private CompatCreate() {}

    /** Forge 1.20.1 形态：无 mod event bus 参数（介质注册与 SimpleChannel 都是静态调用）。 */
    public static void register() {
        Mediums.register(CREATE_STRESS);
        P2PCreateNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(CompatCreate::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(CompatCreate::onServerTick);
        // 本体 1.1.0 事件契约（替代旧 P2PChannelManagerMixin / ChannelActionC2SPacketMixin）
        MinecraftForge.EVENT_BUS.addListener(CompatCreate::onChannelDeleted);
        MinecraftForge.EVENT_BUS.addListener(CompatCreate::onMembersChanged);
        MinecraftForge.EVENT_BUS.addListener(CompatCreate::onChannelSynced);
        P2P.LOGGER.info("[p2pcreate] registered Create stress medium and wireless kinetic bridge");
    }

    /** Client half: only called on the client (FMLEnvironment.dist==Dist.CLIENT) with create loaded. */
    public static void registerClient() {
        ChannelScreenExtensions.register(STRESS_ID, new StressPanelExtension());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        KineticBridgeState.get(event.getServer().overworld());
    }

    /** Wireless outputs are generators the bridge rents out; take them apart once nobody pays. */
    private static void onServerTick(TickEvent.ServerTickEvent event) {
        // 1.21.1 ServerTickEvent.Post 等价：Forge 1.20.1 用 ServerTickEvent + Phase.END 过滤。
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        KineticBridgeState.INSTANCE.sweep(server);
        syncInfiniteSupply(server);
    }

    /**
     * 无限供给（创造马达）标记的每秒差量广播：桥判定集合变化时才发包，保持打开中的
     * 状态卡数值新鲜（ChannelSyncedEvent 只在快照时触发，覆盖不了运行中的变化）。
     */
    private static void syncInfiniteSupply(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        Set<String> current = KineticBridgeState.INSTANCE.getInfiniteSupplyKeys();
        if (current.equals(lastInfiniteSync)) return;
        lastInfiniteSync = current;
        // 集合清空也要下发一次（清掉客户端残留标记）。
        P2PCreateNetwork.broadcastInfiniteSupply(List.copyOf(current));
    }

    private static void onChannelDeleted(ChannelDeletedEvent event) {
        KineticBridgeState.INSTANCE.removeChannel(event.channel.id);
    }

    /** Members changed: drop persisted output configs stranded under no-longer-owned member keys (idempotent). */
    private static void onMembersChanged(ChannelMembersChangedEvent event) {
        Channel channel = event.channel;
        if (channel.type == null || !STRESS_ID.equals(channel.type.id)) return;
        Level level = event.level != null ? event.level
                : (event.actor != null ? event.actor.serverLevel() : null);
        if (!(level instanceof ServerLevel serverLevel)) return;
        KineticBridgeState.get(serverLevel).pruneChannel(channel.id, channel.members);
    }

    // Every S2C channel snapshot funnels through the base mod's serializeFor; piggyback the
    // addon's own output-config sync on it (replaces the old serializeFor TAIL injection).
    private static void onChannelSynced(ChannelSyncedEvent event) {
        P2PCreateNetwork.syncTo(event.player);
    }
}
