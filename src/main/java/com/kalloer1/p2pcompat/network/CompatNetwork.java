package com.kalloer1.p2pcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * p2p_compat 自己的网络通道（Forge 1.20.1 SimpleChannel 形态，与本体 ModMessages 同构写法）。
 *
 * <p>通道 {@code p2p_compat:main}，协议版本 "1"。本阶段只注册 AE2 的频道用量同步包
 * （{@code com.kalloer1.p2pae2.network.P2PAe2Network.SyncBridgeChannelsMessage}，仅
 * client-bound）；后续模块需要私有包时在这里追加注册。宿主模组缺失时不注册任何消息，
 * 通道存在但无流量，完全无害。
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

    private CompatNetwork() {}

    /**
     * 幂等注册全部消息。在宿主模组守卫内调用（当前只有 ae2 模块有私有包）；
     * 必须双侧都执行——ae2 在客户端与服务端同时在场（mods.toml optional 依赖双侧判定），
     * 注册时机为 mod 构造期，早于任何连接建立。
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

    /** Convenience: send any registered message to the server. */
    public static <T> void sendToServer(T packet) {
        INSTANCE.sendToServer(packet);
    }

    /** Convenience: send any registered message to one player (server side). */
    public static <T> void sendToPlayer(ServerPlayer player, T packet) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
