package com.kalloer1.p2pcreate.network;

import java.util.ArrayList;
import java.util.List;

import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 新增的 compat 自有 client-bound payload（1.2.0）：把"哪些输入端点所在 Create 网络由
 * 创造马达供能（无限供给）"的全量快照推给客户端，供 {@code StressPanelExtension} 显示
 * "∞ SU"。命名空间沿用 {@code p2pcreate}；既有 payload（SetOutputConfig /
 * SyncOutputConfigs）的类与 wire 格式不变，这里只是并排多注册一个新类型。
 *
 * <p>条目为「channelId:memberKey」字符串集合；客户端整表替换，缺项即视为不再无限。
 * 发送时机：ChannelSyncedEvent 搭车（syncTo）+ 服务端每秒差量广播（CompatCreate tick）。
 */
public record SyncInfiniteSupplyPayload(List<String> keys) implements CustomPacketPayload {
    public static final Type<SyncInfiniteSupplyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("p2pcreate", "sync_infinite_supply"));
    // 手写 size 前缀编解码：ByteBufCodecs.collection 对裸 STRING_UTF8 推不出
    // RegistryFriendlyByteBuf 的 B，直接走 CustomPacketPayload.codec 最省事。
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncInfiniteSupplyPayload> CODEC =
            CustomPacketPayload.codec(SyncInfiniteSupplyPayload::write, SyncInfiniteSupplyPayload::read);

    private static void write(SyncInfiniteSupplyPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(payload.keys().size());
        for (String key : payload.keys()) buf.writeUtf(key);
    }

    private static SyncInfiniteSupplyPayload read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> keys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) keys.add(buf.readUtf());
        return new SyncInfiniteSupplyPayload(keys);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 客户端接收：整表替换 {@link KineticBridgeState} 的无限供给镜像。 */
    public static void handle(SyncInfiniteSupplyPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> KineticBridgeState.replaceClientInfiniteSupply(payload.keys()));
    }
}
