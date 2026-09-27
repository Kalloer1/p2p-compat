package com.kalloer1.p2pmekanism;

import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Filter;
import com.kalloer1.p2pmekanism.filter.GasFilterLogic;
import com.kalloer1.p2p.transfer.TransferCapabilityCache;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Mekanism 气体/化学品点对点传输。结构与主模组 {@code FluidTransfer} 同构：
 * 先 SIMULATE 摸清每个输出端的本轮接受量，再 EXECUTE 精确抽取与灌入（零往返损耗），
 * 支持 ROUND_ROBIN / PRIORITY / NEAREST / FARTHEST / SPLIT_EVENLY 全部分配模式。
 *
 * <p>气体端点的化学品过滤由附属模组注入的过滤器数据和 GUI 扩展提供。
 * 返回本轮实际移动的 mB 当量（int），供引擎驱动空闲退避。
 */
public final class MekGasTransfer {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger();
    private static long lastProbeLog = -1000;

    public static int transfer(TransferCapabilityCache cache, Channel channel, Map<ResourceLocation, Level> levels,
                               List<Member> inputs, List<Member> outputs, int budget, int globalTick) {
        if (inputs.isEmpty() || outputs.isEmpty() || budget <= 0) return 0;

        List<Cap> inCaps = resolve(cache, levels, inputs, true);
        if (inCaps.isEmpty()) { probeLog(channel, "no IChemicalHandler on any INPUT endpoint"); return 0; }
        if (!anyInputHasGas(inCaps)) { probeLog(channel, "inputs resolve but no extractable gas (simulate empty)"); return 0; }
        List<Cap> outCaps = resolve(cache, levels, outputs, false);
        if (outCaps.isEmpty()) { probeLog(channel, "no IChemicalHandler on any OUTPUT endpoint"); return 0; }
        int outSize = outCaps.size();

        // 均分模式：本轮可抽取量按输出端数量均分，同时输出到所有输出端。
        if (channel.distribution == Channel.Distribution.SPLIT_EVENLY) {
            return splitEvenly(inCaps, outCaps, budget, globalTick);
        }

        // PRIORITY: stable-sort outputs by endpoint priority once per call (highest first).
        if (channel.distribution == Channel.Distribution.PRIORITY) {
            outCaps.sort(Comparator.comparingInt((Cap c) -> c.priority).reversed());
        }

        long remaining = budget;
        int opCount = 0;
        long totalMoved = 0;
        Set<Cap> dirty = new HashSet<>();
        boolean[] outFull = new boolean[outSize];

        outer:
        for (Cap cap : inCaps) {
            // NEAREST / FARTHEST: order outputs by their distance to the current input face.
            if (channel.distribution == Channel.Distribution.NEAREST
                    || channel.distribution == Channel.Distribution.FARTHEST) {
                boolean nearest = channel.distribution == Channel.Distribution.NEAREST;
                outCaps.sort((a, b) -> {
                    double da = a.pos.distSqr(cap.pos);
                    double db = b.pos.distSqr(cap.pos);
                    return nearest ? Double.compare(da, db) : Double.compare(db, da);
                });
            }
            IChemicalHandler h = cap.handler;
            ChemicalStack available = h.extractChemical(remaining, Action.SIMULATE);
            if (available == null || available.isEmpty()) continue;
            if (!GasFilterLogic.accepts(cap.filter, available)) continue;

            // ---- Phase 1: simulate how much each open output accepts for this gas ----
            long[] willAccept = new long[outSize];
            long acceptable = 0;
            int start = switch (channel.distribution) {
                case PRIORITY, NEAREST, FARTHEST -> 0;
                default -> (globalTick + opCount) % outSize;   // ROUND_ROBIN
            };
            for (int k = 0; k < outSize; k++) {
                int idx = (start + k) % outSize;
                Cap out = outCaps.get(idx);
                if (outFull[idx]) continue;
                if (!GasFilterLogic.accepts(out.filter, available)) continue;
                ChemicalStack probe = new ChemicalStack(available.getChemical(), Math.min(remaining, available.getAmount()));
                // ⚠️ Mek 的 insertChemical 返回的是「没塞进去的剩余量」——与原版流体 fill 返回
                // 「接受量」语义相反！接受量 = probe 量 - 剩余量。（首版写反导致气体完全不动。）
                ChemicalStack remainder = out.handler.insertChemical(probe, Action.SIMULATE);
                long acc = probe.getAmount() - (remainder == null ? 0 : remainder.getAmount());
                willAccept[idx] = acc;
                if (acc <= 0 && isFull(out.handler, available)) outFull[idx] = true;
                acceptable += acc;
                if (acceptable >= available.getAmount()) break;
            }
            if (acceptable <= 0) {
                probeLog(channel, String.format("phase1: input has %d x %s but outputs accept 0 (allFull=%s)",
                        available.getAmount(), available.getChemical().getRegistryName(), outFull[0]));
                continue;
            }
            acceptable = Math.min(acceptable, Math.min(available.getAmount(), remaining));

            // ---- Phase 2: really drain ONLY the accepted amount ----
            ChemicalStack pulled = h.extractChemical(acceptable, Action.EXECUTE);
            if (pulled == null || pulled.isEmpty()) continue;

            // ---- Phase 3: really fill, in the same visiting order ----
            long left = pulled.getAmount();
            for (int k = 0; k < outSize && left > 0; k++) {
                int idx = (start + k) % outSize;
                if (willAccept[idx] <= 0) continue;
                Cap out = outCaps.get(idx);
                if (!GasFilterLogic.accepts(out.filter, pulled)) continue;
                ChemicalStack chunk = left >= pulled.getAmount() ? pulled : new ChemicalStack(pulled.getChemical(), left);
                ChemicalStack filled = out.handler.insertChemical(chunk, Action.EXECUTE);
                long accepted = chunk.getAmount() - (filled == null ? 0 : filled.getAmount());
                if (accepted > 0) {
                    dirty.add(out);
                    left -= accepted;
                }
            }
            long moved = pulled.getAmount() - left;
            if (moved > 0) {
                if (left > 0) {
                    // Defensive: capacity changed mid-tick; restore what wasn't placed.
                    cap.handler.insertChemical(new ChemicalStack(pulled.getChemical(), left), Action.EXECUTE);
                }
                dirty.add(cap);
                remaining -= moved;
                totalMoved += moved;
                opCount++;
                if (remaining <= 0) break outer;
            }
        }

        for (Cap c : dirty) c.markChanged();
        probeLog(channel, String.format("moved=%d (in=%d out=%d budget=%d)", totalMoved, inCaps.size(), outSize, budget));
        return (int) Math.min(Integer.MAX_VALUE, totalMoved);
    }

    /** 每秒至多一条的阶段探针，定位传输静默失败用。 */
    private static void probeLog(Channel channel, String msg) {
        long now = System.currentTimeMillis();
        if (now - lastProbeLog < 1000) return;
        lastProbeLog = now;
        LOG.info("[p2pmekanism] gas channel #{}: {}", channel.id, msg);
    }

    // ============================ 均分模式 (SPLIT_EVENLY) ============================

    /** 气体均分：与流体版同构——逐输入端取本轮可抽取量，按配额同时推向所有输出端。 */
    private static int splitEvenly(List<Cap> inCaps, List<Cap> outCaps, int budget, int globalTick) {
        int outSize = outCaps.size();
        if (outSize == 0 || budget <= 0) return 0;

        long budgetLeft = budget;
        long totalMoved = 0;
        Set<Cap> dirty = new HashSet<>();
        int offset = (int) Math.floorMod(globalTick, (long) outSize);

        for (Cap src : inCaps) {
            if (budgetLeft <= 0) break;
            ChemicalStack avail = src.handler.extractChemical(budgetLeft, Action.SIMULATE);
            if (avail == null || avail.isEmpty()) continue;
            if (!GasFilterLogic.accepts(src.filter, avail)) continue;

            long pool = avail.getAmount();
            boolean[] closed = new boolean[outSize];
            while (pool > 0) {
                int open = 0;
                for (boolean c : closed) if (!c) open++;
                if (open == 0) break;
                int openLeft = open;
                boolean progressed = false;
                for (int k = 0; k < outSize && pool > 0; k++) {
                    int idx = (offset + k) % outSize;
                    if (closed[idx]) continue;
                    long want = Math.min(pool, (pool + openLeft - 1) / openLeft);
                    openLeft--;
                    long moved = pushEven(src, outCaps.get(idx), avail, want, dirty);
                    if (moved > 0) { pool -= moved; budgetLeft -= moved; totalMoved += moved; progressed = true; }
                    else closed[idx] = true;
                }
                if (!progressed) break;
            }
            dirty.add(src);
        }

        for (Cap c : dirty) c.markChanged();
        return (int) Math.min(Integer.MAX_VALUE, totalMoved);
    }

    /** 从 {@code src} 抽取至多 {@code want} mB 当量并灌入 {@code out}，返回实际转移量。 */
    private static long pushEven(Cap src, Cap out, ChemicalStack template, long want, Set<Cap> dirty) {
        if (want <= 0) return 0;
        if (!GasFilterLogic.accepts(src.filter, template) || !GasFilterLogic.accepts(out.filter, template)) return 0;
        ChemicalStack probe = new ChemicalStack(template.getChemical(), want);
        ChemicalStack canAccept = out.handler.insertChemical(probe, Action.SIMULATE);
        long room = probe.getAmount() - (canAccept == null ? 0 : canAccept.getAmount());
        if (room <= 0) return 0;

        ChemicalStack drained = src.handler.extractChemical(room, Action.EXECUTE);
        if (drained == null || drained.isEmpty()) return 0;
        ChemicalStack back = out.handler.insertChemical(drained, Action.EXECUTE);
        long filled = drained.getAmount() - (back == null ? 0 : back.getAmount());
        if (filled < drained.getAmount()) {
            // 容量在模拟之后变化：把没灌进去的部分退回源，避免丢气体
            src.handler.insertChemical(new ChemicalStack(drained.getChemical(), drained.getAmount() - filled), Action.EXECUTE);
        }
        if (filled > 0) dirty.add(out);
        return filled;
    }

    /** True when any input handler can extract at least 1 mB 当量 (simulate-only cheap gate). */
    private static boolean anyInputHasGas(List<Cap> inCaps) {
        for (Cap cap : inCaps) {
            ChemicalStack s = cap.handler.extractChemical(1, Action.SIMULATE);
            if (s != null && !s.isEmpty() && GasFilterLogic.accepts(cap.filter, s)) return true;
        }
        return false;
    }

    /** True when the handler cannot accept any more of {@code stack}: no usable empty tank, no mergeable tank. */
    private static boolean isFull(IChemicalHandler h, ChemicalStack stack) {
        for (int i = 0; i < h.getChemicalTanks(); i++) {
            ChemicalStack s = h.getChemicalInTank(i);
            if (s.isEmpty() && h.getChemicalTankCapacity(i) > 0) return false;
            if (s.getChemical() == stack.getChemical() && s.getAmount() < h.getChemicalTankCapacity(i)) return false;
        }
        return true;
    }

    private static List<Cap> resolve(TransferCapabilityCache cache, Map<ResourceLocation, Level> levels,
                                     List<Member> members, boolean input) {
        List<Cap> out = new ArrayList<>();
        for (Member m : members) {
            Level level = levels.get(m.dim);
            if (level == null) continue;
            IChemicalHandler h = cache.get(level, m.pos, m.face, Capabilities.CHEMICAL.block());
            if (h == null) continue;
            Filter filter = input ? m.extractFilter : m.insertFilter;
            out.add(new Cap(h, filter, level, m.pos, m.priority));
        }
        return out;
    }

    private static final class Cap {
        final IChemicalHandler handler;
        final Filter filter;
        final Level level;
        final BlockPos pos;
        final int priority;
        Cap(IChemicalHandler handler, Filter filter, Level level, BlockPos pos, int priority) {
            this.handler = handler; this.filter = filter; this.level = level; this.pos = pos; this.priority = priority;
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







