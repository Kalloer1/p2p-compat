package com.kalloer1.p2pcreate;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.medium.Mediums;
import com.kalloer1.p2pcreate.bridge.CreateStressTransfer;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
        P2P.LOGGER.info("[p2pcreate] registered Create stress medium and wireless kinetic bridge");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        KineticBridgeState.get(event.getServer().overworld());
    }

    /** Wireless outputs are generators the bridge rents out; take them apart once nobody pays. */
    private static void onServerTick(ServerTickEvent.Post event) {
        KineticBridgeState.INSTANCE.sweep(event.getServer());
    }
}
