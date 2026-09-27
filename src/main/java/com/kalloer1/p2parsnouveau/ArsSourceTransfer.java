package com.kalloer1.p2parsnouveau;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.transfer.TransferCapabilityCache;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
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
 * 新生魔艺魔源（source）点对点传输。INPUT 端点=源质罐/供能方块（抽取），
 * OUTPUT 端点=源质罐/供能方块（注入）。走 {@code ISourceTile}：
 * 抽取 {@code removeSource(n)}（返回实际扣除量），注入 {@code addSource(n)}
 * （返回未塞入的剩余量），上限 {@code getMaxSource()}。source 单整数语义。
 *
 * <p>支持全部分配模式。source 接近即时量（类似能量），空闲退避上限 5t。
 */
public final class ArsSourceTransfer {
    private ArsSourceTransfer() {}

    public static int transfer(TransferCapabilityCache cache, Channel channel, Map<ResourceLocation, Level> levels,
                               List<Member> inputs, List<Member> outputs, int budget, int globalTick) {
        if (inputs.isEmpty() || outputs.isEmpty() || budget <= 0) return 0;

        List<Cap> inCaps = resolve(cache, levels, inputs);
        if (inCaps.isEmpty()) return 0;
        List<Cap> outCaps = resolve(cache, levels, outputs);
        if (outCaps.isEmpty()) return 0;
        int outSize = outCaps.size();

        if (channel.distribution == Channel.Distribution.SPLIT_EVENLY) {
            return splitEvenly(inCaps, outCaps, budget);
        }

        if (channel.distribution == Channel.Distribution.PRIORITY) {
            outCaps.sort(Comparator.comparingInt((Cap c) -> c.priority).reversed());
        }

        int remaining = budget;
        int totalMoved = 0;
        Set<Cap> dirty = new HashSet<>();

        for (Cap cap : inCaps) {
            if (remaining <= 0) break;
            ISourceTile src = cap.tile;
            int avail = src.getSource();
            if (avail <= 0) continue;

            int start = switch (channel.distribution) {
                case PRIORITY, NEAREST, FARTHEST -> 0;
                default -> globalTick % outSize;
            };
            if (channel.distribution == Channel.Distribution.NEAREST
                    || channel.distribution == Channel.Distribution.FARTHEST) {
                boolean nearest = channel.distribution == Channel.Distribution.NEAREST;
                outCaps.sort((a, b) -> {
                    double da = a.pos.distSqr(cap.pos);
                    double db = b.pos.distSqr(cap.pos);
                    return nearest ? Double.compare(da, db) : Double.compare(db, da);
                });
            }

            int toPull = Math.min(Math.min(remaining, avail), budget);
            int pulled = src.removeSource(toPull);
            if (pulled <= 0) continue;

            // 按访问顺序注入输出端，addSource 返回未塞入量则继续下一端
            int left = pulled;
            for (int k = 0; k < outSize && left > 0; k++) {
                int idx = (start + k) % outSize;
                Cap out = outCaps.get(idx);
                if (!out.tile.canAcceptSource()) continue;
                int space = out.tile.getMaxSource() - out.tile.getSource();
                if (space <= 0) continue;
                int give = Math.min(left, space);
                int leftover = out.tile.addSource(give);
                int accepted = give - leftover;
                if (accepted > 0) {
                    dirty.add(out);
                    left -= accepted;
                }
            }
            if (left > 0) {
                // 容量在扣除之后变化：把没注进去的部分退回源
                src.addSource(left);
            }
            int moved = pulled - left;
            if (moved > 0) {
                dirty.add(cap);
                remaining -= moved;
                totalMoved += moved;
            }
        }

        for (Cap c : dirty) c.markChanged();
        return totalMoved;
    }

    /** 魔源均分：逐输入端抽取，按"剩余待发量 / 可接收输出端数"配额同时注入。 */
    private static int splitEvenly(List<Cap> inCaps, List<Cap> outCaps, int budget) {
        int outSize = outCaps.size();
        if (outSize == 0 || budget <= 0) return 0;

        int budgetLeft = budget;
        int totalMoved = 0;
        Set<Cap> dirty = new HashSet<>();

        for (Cap src : inCaps) {
            if (budgetLeft <= 0) break;
            int avail = src.tile.getSource();
            if (avail <= 0) continue;
            int pool = Math.min(avail, budgetLeft);
            int moved = 0;
            while (pool > 0) {
                boolean progressed = false;
                for (Cap out : outCaps) {
                    if (pool <= 0) break;
                    if (!out.tile.canAcceptSource()) continue;
                    int space = out.tile.getMaxSource() - out.tile.getSource();
                    if (space <= 0) continue;
                    int give = Math.min(Math.min(pool, space), Math.max(1, pool / outSize));
                    int leftover = out.tile.addSource(give);
                    int accepted = give - leftover;
                    if (accepted <= 0) continue;
                    pool -= accepted;
                    budgetLeft -= accepted;
                    totalMoved += accepted;
                    moved += accepted;
                    dirty.add(out);
                    progressed = true;
                }
                if (!progressed) break;
            }
            if (moved > 0) {
                src.tile.removeSource(moved);
                dirty.add(src);
            }
        }

        for (Cap c : dirty) c.markChanged();
        return totalMoved;
    }

    private static List<Cap> resolve(TransferCapabilityCache cache, Map<ResourceLocation, Level> levels,
                                     List<Member> members) {
        List<Cap> out = new ArrayList<>();
        for (Member m : members) {
            Level level = levels.get(m.dim);
            if (level == null) continue;
            BlockEntity be = level.getBlockEntity(m.pos);
            if (be instanceof ISourceTile t) {
                out.add(new Cap(t, level, m.pos, m.priority));
            }
        }
        return out;
    }

    private static final class Cap {
        final ISourceTile tile;
        final Level level;
        final BlockPos pos;
        final int priority;
        Cap(ISourceTile tile, Level level, BlockPos pos, int priority) {
            this.tile = tile; this.level = level; this.pos = pos; this.priority = priority;
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
