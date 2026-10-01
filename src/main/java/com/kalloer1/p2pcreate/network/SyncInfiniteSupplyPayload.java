package com.kalloer1.p2pcreate.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * compat 自有 client-bound 消息（1.2.0 新增；1.20.1 Forge 形态改写）：把"哪些输入端点所在
 * Create 网络由创造马达供能（无限供给）"的全量快照推给客户端，供 {@code StressPanelExtension}
 * 显示 "∞ SU"。消息挂在 compat 通道 {@code p2p_compat:main} 上，命名空间沿用 {@code p2pcreate}。
 *
 * <p>条目为「channelId:memberKey」字符串集合；客户端整表替换，缺项即视为不再无限。
 * 发送时机：ChannelSyncedEvent 搭车（syncTo）+ 服务端每秒差量广播（CompatCreate tick）。
 * 字节布局：varint 数量 + 每条 utf（与 1.21.1 手写 CustomPacketPayload.codec 一致）。
 */
public class SyncInfiniteSupplyPayload {
    private final List<String> keys;

    public SyncInfiniteSupplyPayload(List<String> keys) {
        this.keys = keys;
    }

    public SyncInfiniteSupplyPayload(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> read = new ArrayList<>(size);
        for (int i = 0; i < size; i++) read.add(buf.readUtf());
        this.keys = read;
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarInt(keys.size());
        for (String key : keys) buf.writeUtf(key);
    }

    public List<String> keys() {
        return keys;
    }

    /** 客户端接收：整表替换 {@link KineticBridgeState} 的无限供给镜像。 */
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        KineticBridgeState.replaceClientInfiniteSupply(keys);
        ctx.get().setPacketHandled(true);
    }
}
