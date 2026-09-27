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
import com.kalloer1.p2pcreate.network.SyncInfiniteSupplyPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Create compat: registered by the unified p2p_compat entry only when the Create mod is loaded. */
public final class CompatCreate {
    public static final String MODID = "p2pcreate";
    public static final String STRESS_ID = "create:stress";
    public static final Medium CREATE_STRESS = new Medium(
            STRESS_ID, 0xFFB46B35, "p2p.medium.create_stress", 5, CreateStressTransfer::transfer);

    private CompatCreate() {}

    public static void register(IEventBus modEventBus) {
        Mediums.register(CREATE_STRESS);
        P2PCreateNetwork.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(CompatCreate::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CompatCreate::onServerTick);
        // 本体 1.1.0 事件契约（替代旧 P2PChannelManagerMixin / ChannelActionC2SPacketMixin）
        NeoForge.EVENT_BUS.addListener(CompatCreate::onChannelDeleted);
        NeoForge.EVENT_BUS.addListener(CompatCreate::onMembersChanged);
        NeoForge.EVENT_BUS.addListener(CompatCreate::onChannelSynced);
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
    private static void onServerTick(ServerTickEvent.Post event) {
        KineticBridgeState.INSTANCE.sweep(event.getServer());
        syncInfiniteSupply(event);
    }

    /**
     * 无限供给（创造马达）标记的每秒差量广播：桥判定集合变化时才发包，保持打开中的
     * 状态卡数值新鲜（ChannelSyncedEvent 只在快照时触发，覆盖不了运行中的变化）。
     */
    private static void syncInfiniteSupply(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        Set<String> current = KineticBridgeState.INSTANCE.getInfiniteSupplyKeys();
        if (current.equals(lastInfiniteSync)) return;
        lastInfiniteSync = current;
        // 集合清空也要下发一次（清掉客户端残留标记）。
        PacketDistributor.sendToAllPlayers(new SyncInfiniteSupplyPayload(List.copyOf(current)));
    }

    private static Set<String> lastInfiniteSync = Set.of();

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
