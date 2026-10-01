package com.kalloer1.p2pmekanism;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.transfer.TransferCapabilityCache;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mekanism 热量点对点传输。热量是**连续量**（焦耳，double），没有"种类"也没有"物品堆"，
 * 原版的导热规则是：热量只从高温流向低温，流量 ∝ 温差 ÷ 热阻。
 *
 * <p>p2p 的模型：INPUT 端点是热源（燃料烧炉/热发生器等），OUTPUT 端点是受热设备。
 * 每 tick 按配对计算 {@code q = min(budget, (T_hot − T_cold) × CONDUCTION)}，
 * 从输入端 {@code handleHeat(-q)}、向输出端 {@code handleHeat(+q)}——温差收敛到 0 时
 * 自然停止，不会把设备烧过热（与 Mek 自己的导热电缆同一条物理规则）。
 *
 * <p>支持全部分配模式（输出端的排序/均分逻辑与主模组传输例程一致）。
 * 返回本轮实际搬运的热量（int 焦耳取整），供引擎驱动空闲退避。
 */
public final class MekHeatTransfer {
    /** 导热系数（J/t 每开尔文温差）：值越大传得越快；与 Mek 导热电缆同数量级。 */
    private static final double CONDUCTION = 10.0;

    private MekHeatTransfer() {}

    public static int transfer(TransferCapabilityCache cache, Channel channel, Map<ResourceLocation, Level> levels,
                               List<Member> inputs, List<Member> outputs, int budget, int globalTick) {
        if (inputs.isEmpty() || outputs.isEmpty() || budget <= 0) return 0;

        List<Cap> inCaps = resolve(cache, levels, inputs);
        if (inCaps.isEmpty()) return 0;
        List<Cap> outCaps = resolve(cache, levels, outputs);
        if (outCaps.isEmpty()) return 0;

        if (channel.distribution == Channel.Distribution.PRIORITY) {
            outCaps.sort(Comparator.comparingInt((Cap c) -> c.priority).reversed());
        }

        int outSize = outCaps.size();
        long remaining = budget;
        long totalMoved = 0;
        Set<Cap> dirty = new HashSet<>();

        for (Cap cap : inCaps) {
            if (remaining <= 0) break;
            double tIn = avgTemperature(cap.handler);
            if (tIn <= 0) continue;

            // 输出端排序（ROUND_ROBIN 从 tick 偏移开始轮转，NEAREST/FARTHEST 按距离）
            int start = switch (channel.distribution) {
                case PRIORITY, NEAREST, FARTHEST -> 0;
                default -> globalTick % outSize;
            };
            Integer[] order = new Integer[outSize];
            for (int k = 0; k < outSize; k++) order[k] = (start + k) % outSize;
            if (channel.distribution == Channel.Distribution.NEAREST
                    || channel.distribution == Channel.Distribution.FARTHEST) {
                boolean nearest = channel.distribution == Channel.Distribution.NEAREST;
                outCaps.sort((a, b) -> {
                    double da = a.pos.distSqr(cap.pos);
                    double db = b.pos.distSqr(cap.pos);
                    return nearest ? Double.compare(da, db) : Double.compare(db, da);
                });
            }

            for (int idx : order) {
                if (remaining <= 0) break;
                Cap out = outCaps.get(idx);
                double tOut = avgTemperature(out.handler);
                if (tIn <= tOut) continue;   // 热量只从高温流向低温
                double q = Math.min((double) remaining, (tIn - tOut) * CONDUCTION);
                if (q <= 0) continue;
                // SPLIT_EVENLY：本轮预算按输出端数量均分
                if (channel.distribution == Channel.Distribution.SPLIT_EVENLY) {
                    q = Math.min(q, (double) budget / outSize);
                }
                cap.handler.handleHeat(-q);
                out.handler.handleHeat(q);
                dirty.add(cap);
                dirty.add(out);
                remaining -= (long) q;
                totalMoved += (long) q;
            }
        }

        for (Cap c : dirty) c.markChanged();
        return (int) Math.min(Integer.MAX_VALUE, totalMoved);
    }

    /** 容量加权平均温度（K）：与 Mek 热量面板读数同语义。 */
    private static double avgTemperature(IHeatHandler h) {
        double heat = 0, capacity = 0;
        int n = h.getHeatCapacitorCount();
        for (int i = 0; i < n; i++) {
            double c = h.getHeatCapacity(i);
            heat += h.getTemperature(i) * c;
            capacity += c;
        }
        return capacity <= 0 ? 0 : heat / capacity;
    }

    private static List<Cap> resolve(TransferCapabilityCache cache, Map<ResourceLocation, Level> levels,
                                     List<Member> members) {
        List<Cap> out = new ArrayList<>();
        for (Member m : members) {
            Level level = levels.get(m.dim);
            if (level == null) continue;
            IHeatHandler h = cache.get(level, m.pos, m.face, Capabilities.HEAT_HANDLER);
            if (h == null) continue;
            out.add(new Cap(h, level, m.pos, m.priority));
        }
        return out;
    }

    private static final class Cap {
        final IHeatHandler handler;
        final Level level;
        final BlockPos pos;
        final int priority;
        Cap(IHeatHandler handler, Level level, BlockPos pos, int priority) {
            this.handler = handler; this.level = level; this.pos = pos; this.priority = priority;
        }
        void markChanged() {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) be.setChanged();
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Cap c)) return false;
            return pos.equals(c.pos) && level == c.level;
        }
        @Override
        public int hashCode() {
            return pos.hashCode() * 31 + System.identityHashCode(level);
        }
    }
}
