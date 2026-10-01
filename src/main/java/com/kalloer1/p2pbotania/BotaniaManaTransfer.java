package com.kalloer1.p2pbotania;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.transfer.TransferCapabilityCache;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import vazkii.botania.api.mana.ManaReceiver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Botania 魔力点对点传输。INPUT 端点=魔力池（抽取），OUTPUT 端点=魔力池（注入）。
 * 走 {@code vazkii.botania.api.mana.ManaReceiver} 接口（方块实体直接实现）：
 * 抽取用 {@code receiveMana(-n)}（负值注入即扣除），注入用 {@code receiveMana(+n)}，
 * 满池用 {@code isFull()} 判定。魔力无"种类"，单整数语义。
 *
 * <p>支持全部分配模式（输出端排序/均分逻辑与主模组传输例程一致）。
 * 注意：ManaReceiver 不暴露池子容量上限，注入按 {@code isFull()} 分批推进直到
 * 收满或本轮预算耗尽。
 */
public final class BotaniaManaTransfer {
    private BotaniaManaTransfer() {}

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
        boolean[] outFull = new boolean[outSize];

        for (Cap cap : inCaps) {
            if (remaining <= 0) break;
            ManaReceiver r = cap.receiver;
            int avail = r.getCurrentMana();
            if (avail <= 0) continue;

            // 输出端排序（ROUND_ROBIN 从 tick 偏移开始轮转，NEAREST/FARTHEST 按距离）
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
            int left = toPull;
            for (int k = 0; k < outSize && left > 0; k++) {
                int idx = (start + k) % outSize;
                if (outFull[idx]) continue;
                Cap out = outCaps.get(idx);
                if (out.receiver.isFull()) { outFull[idx] = true; continue; }
                int give = Math.min(left, Math.max(1, remaining / outSize));
                out.receiver.receiveMana(give);
                left -= give;
                dirty.add(out);
            }
            int moved = toPull - left;
            if (moved > 0) {
                r.receiveMana(-moved);
                dirty.add(cap);
                remaining -= moved;
                totalMoved += moved;
            }
        }

        for (Cap c : dirty) c.markChanged();
        return totalMoved;
    }

    /** 魔力均分：逐输入端抽取，按"剩余待发量 / 未满输出端数"配额同时注入。 */
    private static int splitEvenly(List<Cap> inCaps, List<Cap> outCaps, int budget) {
        int outSize = outCaps.size();
        if (outSize == 0 || budget <= 0) return 0;

        int budgetLeft = budget;
        int totalMoved = 0;
        Set<Cap> dirty = new HashSet<>();

        for (Cap src : inCaps) {
            if (budgetLeft <= 0) break;
            int avail = src.receiver.getCurrentMana();
            if (avail <= 0) continue;
            int pool = Math.min(avail, budgetLeft);
            int moved = 0;
            while (pool > 0) {
                boolean progressed = false;
                for (Cap out : outCaps) {
                    if (pool <= 0) break;
                    if (out.receiver.isFull()) continue;
                    int give = Math.min(pool, Math.max(1, pool / outSize));
                    out.receiver.receiveMana(give);
                    pool -= give;
                    budgetLeft -= give;
                    totalMoved += give;
                    moved += give;
                    dirty.add(out);
                    progressed = true;
                }
                if (!progressed) break;
            }
            if (moved > 0) {
                src.receiver.receiveMana(-moved);
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
            if (be instanceof ManaReceiver r) {
                out.add(new Cap(r, level, m.pos, m.priority));
            }
        }
        return out;
    }

    private static final class Cap {
        final ManaReceiver receiver;
        final Level level;
        final BlockPos pos;
        final int priority;
        Cap(ManaReceiver receiver, Level level, BlockPos pos, int priority) {
            this.receiver = receiver; this.level = level; this.pos = pos; this.priority = priority;
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
