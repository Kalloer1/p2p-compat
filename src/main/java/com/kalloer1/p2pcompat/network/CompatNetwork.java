package com.kalloer1.p2pcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * p2p_compat 自己的网络通道（Forge 1.20.1 SimpleChannel 形态，与本体 ModMessages 同构写法）。
 *
 * <p>通道 {@code p2p_compat:main}，协议版本 "1"。注册两组私有消息：
 * AE2 的频道用量同步包（{@code P2PAe2Network.SyncBridgeChannelsMessage}）与 Create 的
 * 应力桥三包（{@code P2PCreateNetwork} 的 SetOutputConfig / SyncOutputConfigs /
 * {@code SyncInfiniteSupplyPayload}）。宿主模组缺失时不注册对应消息，通道存在但无
 * 该模块流量，完全无害。
 */
public final class CompatNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("p2p_compat", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;
    private static int id() { return packetId++; }

    private static boolean registered = false;
    private static boolean createRegistered = false;

    private CompatNetwork() {}

    /**
     * 幂等注册 AE2 消息。在宿主 ae2 守卫内调用；必须双侧都执行——ae2 在客户端与服务端
     * 同时在场（mods.toml optional 依赖双侧判定），注册时机为 mod 构造期，早于任何连接建立。
     */
    public static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        INSTANCE.messageBuilder(com.kalloer1.p2pae2.network.P2PAe2Network.SyncBridgeChannelsMessage.class, id())
                .encoder(com.kalloer1.p2pae2.network.P2PAe2Network.SyncBridgeChannelsMessage::toBytes)
                .decoder(com.kalloer1.p2pae2.network.P2PAe2Network.SyncBridgeChannelsMessage::new)
                .consumerMainThread(com.kalloer1.p2pae2.network.P2PAe2Network.SyncBridgeChannelsMessage::handle)
                .add();
    }

    /**
     * 幂等注册 Create 应力桥的 3 条消息（C2S SetOutputConfig + S2C SyncOutputConfigs /
     * SyncInfiniteSupply）。在宿主 create 守卫内调用（P2PCreateNetwork.register → 这里），
     * 注册时机为 mod 构造期，早于任何连接建立。
     */
    public static synchronized void registerCreateMessages() {
        ensureRegistered();
        if (createRegistered) return;
        createRegistered = true;
        INSTANCE.messageBuilder(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SetOutputConfigPayload.class, id())
                .encoder(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SetOutputConfigPayload::toBytes)
                .decoder(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SetOutputConfigPayload::new)
                .consumerMainThread(com.kalloer1.p2pcreate.network.P2PCreateNetwork::handleSetOutput)
                .add();
        INSTANCE.messageBuilder(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SyncOutputConfigsPayload.class, id())
                .encoder(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SyncOutputConfigsPayload::toBytes)
                .decoder(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SyncOutputConfigsPayload::new)
                .consumerMainThread(com.kalloer1.p2pcreate.network.P2PCreateNetwork.SyncOutputConfigsPayload::handle)
                .add();
        INSTANCE.messageBuilder(com.kalloer1.p2pcreate.network.SyncInfiniteSupplyPayload.class, id())
                .encoder(com.kalloer1.p2pcreate.network.SyncInfiniteSupplyPayload::toBytes)
                .decoder(com.kalloer1.p2pcreate.network.SyncInfiniteSupplyPayload::new)
                .consumerMainThread(com.kalloer1.p2pcreate.network.SyncInfiniteSupplyPayload::handle)
                .add();
    }

    /** Convenience: send any registered message to the server. */
    public static <T> void sendToServer(T packet) {
        INSTANCE.sendToServer(packet);
    }

    /** Convenience: send any registered message to one player (server side). */
    public static <T> void sendToPlayer(ServerPlayer player, T packet) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
