package com.kalloer1.p2pae2;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.pathing.ChannelMode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingService;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.api.ChannelSyncedEvent;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.ChannelManager;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2pae2.network.P2PAe2Network;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AE2 频道桥连接管理器（服务端权威）。
 *
 * <p>本体 1.1.0 已提供频道事件钩子（ChannelDeletedEvent / ChannelMembersChangedEvent /
 * ChannelSyncedEvent），但事件只覆盖玩家操作，覆盖不了区块加卸载等拓扑静默变化；本类
 * 因此仍以低频轮询对齐快照：每 20 tick（1 秒）遍历一次 {@code ae2:bridge} 频道，把期望
 * 连接集与实际连接集对齐——建新、拆旧、端点变化时重建（幂等，天然兼容事件驱动的缓存失效）。
 * 见 ADR-0003。
 *
 * <p>一条桥 = 一条远程 {@code IGridConnection}（GridHelper.createConnection，非世界内
 * 连接，跨维度天然支持）。建立后 AE2 网格自管（节点 destroy 时自动断开），本类只负责
 * 让连接集合与 p2p 频道拓扑保持一致，并执行 ADR-0003 的约束：
 * 两端不能都是 ME 控制器（否则 AE2 的 ControllerValidator 判定 CONTROLLER_CONFLICT，
 * 整个网格锁死）。
 */
public final class BridgeManager {
    public static final BridgeManager INSTANCE = new BridgeManager();
    private static final int SYNC_INTERVAL = 20;   // ticks（1 秒）

    /** channelId -> 活跃桥。 */
    private final Map<Integer, ActiveBridge> active = new HashMap<>();
    /** 上次广播的频道用量快照（差量广播用）。 */
    private List<P2PAe2Network.ChannelStat> lastChannelStats = List.of();

    private MinecraftServer server;
    private int tick;

    private BridgeManager() {}

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        this.server = ServerLifecycleHooks.getCurrentServer();
        if (this.server == null) return;
        if (++tick % SYNC_INTERVAL != 0) return;
        syncAll();
    }

    private void syncAll() {
        Set<Integer> seen = new HashSet<>();
        for (Channel ch : ChannelManager.INSTANCE.getAllChannels()) {
            if (ch.type == null || !CompatAE2.MEDIUM_ID.equals(ch.type.id)) continue;
            seen.add(ch.id);
            syncChannel(ch);
        }
        // 频道已删除 / 介质已切换：拆掉遗留连接
        Iterator<Map.Entry<Integer, ActiveBridge>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ActiveBridge> e = it.next();
            if (!seen.contains(e.getKey())) {
                e.getValue().teardown();
                it.remove();
            }
        }
        syncChannelStats();
    }

    /**
     * 每秒对齐一次桥所合并 ME 网络的频道用量并差量广播。桥是一条远程 {@code IGridConnection}，
     * 两端网格在 AE2 里合并成一个 IGrid，所以从任一端节点（优先输入端）的
     * {@code getGrid().getPathingService()} 取网络级数据：
     * <ul>
     *   <li>已用：{@link IPathingService#getUsedChannels()}（网络内 REQUIRE_CHANNEL 节点的已用频道数）</li>
     *   <li>总量（控制器在线）：控制器数 × 32 × 模式系数 —— 控制器节点带
     *       {@code GridFlags.DENSE_CAPACITY}，对应 {@code GridNode.getMaxChannels()} 的
     *       32 × cableCapacityFactor（即每个控制器 32 频道的来源）</li>
     *   <li>总量（无控制器 ad-hoc）：{@link ChannelMode#getAdHocNetworkChannels()}（默认 8）</li>
     *   <li>ChannelMode=INFINITE：max = -1，客户端显示 ∞</li>
     * </ul>
     */
    private void syncChannelStats() {
        List<P2PAe2Network.ChannelStat> stats = new ArrayList<>();
        for (ActiveBridge ab : active.values()) {
            IGridNode node = ab.inNode != null ? ab.inNode : ab.outNode;
            IGrid grid = node != null ? node.getGrid() : null;
            if (grid == null || grid.isEmpty()) continue;
            IPathingService pathing = grid.getPathingService();
            ChannelMode mode = pathing.getChannelMode();
            int used = pathing.getUsedChannels();
            int max;
            if (mode == ChannelMode.INFINITE) {
                max = -1;
            } else if (pathing.getControllerState() == ControllerState.CONTROLLER_ONLINE) {
                int controllers = grid.getMachines(ControllerBlockEntity.class).size();
                max = controllers * 32 * mode.getCableCapacityFactor();
            } else {
                max = mode.getAdHocNetworkChannels();
            }
            stats.add(new P2PAe2Network.ChannelStat(ab.channelId, used, max));
        }
        if (stats.equals(lastChannelStats)) return;
        stats.sort(java.util.Comparator.comparingInt(P2PAe2Network.ChannelStat::channelId));
        lastChannelStats = stats;
        if (server != null) P2PAe2Network.broadcast(server, stats);
    }

    /**
     * 进服/打开界面时的按玩家补发：差量广播只在快照变化时发，静止网络的用量
     * 不会主动到达新登录的客户端，导致频道行一直省略。ChannelSyncedEvent 在
     * 登录与每次快照时触发，借它把当前全量快照单发给该玩家。
     */
    @SubscribeEvent
    public void onChannelSynced(ChannelSyncedEvent event) {
        if (lastChannelStats.isEmpty()) return;
        P2PAe2Network.sendTo(event.player, lastChannelStats);
    }

    private void syncChannel(Channel ch) {
        Member in = null, out = null;
        for (Member m : ch.members) {
            if (m.role == Role.INPUT && in == null) in = m;
            else if (m.role == Role.OUTPUT && out == null) out = m;
        }
        ActiveBridge ab = active.get(ch.id);
        if (in == null || out == null) {
            // 端点不足 1:1：不该有连接
            if (ab != null) {
                ab.teardown();
                active.remove(ch.id);
            }
            return;
        }
        if (ab != null && ab.inKey.equals(in.key()) && ab.outKey.equals(out.key())) {
            return;   // 拓扑未变化，保持现有连接
        }
        // 新频道或端点变化：拆旧建新
        if (ab != null) ab.teardown();
        ActiveBridge nb = new ActiveBridge(ch.id, in, out);
        if (nb.connect()) {
            active.put(ch.id, nb);
        } else {
            nb.teardown();
            active.remove(ch.id);
        }
    }

    /** 单个频道桥：一条远程 IGridConnection（1 输入 + 1 输出）。 */
    private static final class ActiveBridge {
        final int channelId;
        final Member in, out;
        final String inKey, outKey;
        IGridNode inNode, outNode;
        IGridConnection conn;

        ActiveBridge(int channelId, Member in, Member out) {
            this.channelId = channelId;
            this.in = in;
            this.out = out;
            this.inKey = in.key();
            this.outKey = out.key();
        }

        /** 解析两端节点并建立远程连接。失败返回 false（非 AE2 方块 / 区块未加载 / 双控制器 / 已存在连接）。 */
        boolean connect() {
            Level inLevel = levelOf(in.dim);
            Level outLevel = levelOf(out.dim);
            if (inLevel == null || outLevel == null) return false;
            inNode = resolveNode(inLevel, in);
            outNode = resolveNode(outLevel, out);
            if (inNode == null || outNode == null) return false;
            if (inNode == outNode) {
                P2P.LOGGER.warn("[p2pae2] bridge #{}: both endpoints resolve to the same node; rejected", channelId);
                return false;
            }
            // ADR-0003 硬限制：两端不能都是控制器（AE2 控制器冲突规则，无法绕过）
            if (inNode.getOwner() instanceof ControllerBlockEntity
                    && outNode.getOwner() instanceof ControllerBlockEntity) {
                P2P.LOGGER.warn("[p2pae2] bridge #{}: both endpoints are ME controllers; rejected "
                        + "(AE2 controller conflict rule)", channelId);
                return false;
            }
            try {
                conn = GridHelper.createConnection(inNode, outNode);
                P2P.LOGGER.info("[p2pae2] bridge #{} connected: {} <-> {}", channelId, inKey, outKey);
                return true;
            } catch (Exception e) {
                P2P.LOGGER.warn("[p2pae2] bridge #{} connect failed: {}", channelId, e.toString());
                return false;
            }
        }

        void teardown() {
            if (conn != null) {
                try {
                    conn.destroy();
                } catch (Exception ignored) {
                    // 节点已随方块销毁时连接可能已被 AE2 清掉，destroy 幂等失败可忽略
                }
                conn = null;
            }
            inNode = null;
            outNode = null;
        }
    }

    private static Level levelOf(ResourceLocation dim) {
        MinecraftServer s = INSTANCE.server;
        if (s == null) return null;
        return s.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
    }

    /**
     * 解析端点节点。普通 AE2 方块（控制器/接口/终端等，实现 {@link IGridConnectedBlockEntity}）
     * 取主节点（不要求 in-world exposed，孤立设备也能拿到节点并入网格）；线缆（CableBus）
     * 等按绑定面取侧节点。区块未加载或方块不是 AE2 节点宿主时返回 null。
     */
    private static IGridNode resolveNode(Level level, Member m) {
        BlockPos pos = m.pos;
        if (!level.isLoaded(pos)) return null;
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host == null) return null;
        if (host instanceof IGridConnectedBlockEntity g) {
            return g.getGridNode();
        }
        return host.getGridNode(m.face);
    }
}
