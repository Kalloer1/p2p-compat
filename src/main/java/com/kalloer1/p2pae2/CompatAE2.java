package com.kalloer1.p2pae2;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.api.ChannelScreenExtensions;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.medium.Mediums;
import com.kalloer1.p2pae2.client.Ae2BridgePanelExtension;
import com.kalloer1.p2pae2.network.P2PAe2Network;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * AE2 compat（ME 频道桥介质）：仅在 ae2 模组加载时由统一入口 p2p_compat 注册。
 * 介质 transfer 为 null，桥连接由 {@link BridgeManager} 低频轮询与事件维护。
 */
public final class CompatAE2 {
    /** 介质 id：ae2:bridge（"bridge" 避免与 AE2 自己的 channel 概念撞词）。 */
    public static final String MEDIUM_ID = "ae2:bridge";
    /** AE2 品牌青（interface/terminal 色调）。 */
    public static final int BRIDGE_COLOR = 0xFF62BDBD;

    private CompatAE2() {}

    public static void register(IEventBus modEventBus) {
        Mediums.register(new Medium(MEDIUM_ID, BRIDGE_COLOR, "p2p.ae2.bridge", 5, null));
        // 频道用量同步（p2pae2:sync_bridge_channels，仅 client-bound）
        P2PAe2Network.register(modEventBus);
        // BridgeManager 使用 @SubscribeEvent 监听 ServerTickEvent，注册到 NeoForge 事件总线
        NeoForge.EVENT_BUS.register(BridgeManager.INSTANCE);
        P2P.LOGGER.info("[p2pae2] medium registered: {} (ME Channel Bridge, no per-tick transfer)", MEDIUM_ID);
    }

    /** Client half: only called on the client, with ae2 loaded (P2PCompat side guard). */
    public static void registerClient() {
        ChannelScreenExtensions.register(MEDIUM_ID, new Ae2BridgePanelExtension());
    }
}
