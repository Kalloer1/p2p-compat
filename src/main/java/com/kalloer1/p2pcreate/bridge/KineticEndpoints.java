package com.kalloer1.p2pcreate.bridge;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Resolves a Member to the Create kinetic block entity it drives or reads.
 *
 * <p>Binding always records the clicked block, so endpoints may point at the P2P tunnel itself
 * while the machine sits on the face next to it. Candidates are therefore scanned in order:
 * the bound position, its clicked-face neighbour, then the remaining six neighbours. The first
 * kinetic block entity passing the role requirement wins. Server and client share this order so
 * the stress panel's per-row warnings match what the bridge actually resolves.
 */
public final class KineticEndpoints {
    private static final Direction[] SCAN_ORDER = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    private KineticEndpoints() {}

    /** Deduplicated probe positions: self, clicked-face neighbour, then fixed-order neighbours. */
    public static List<BlockPos> candidatePositions(Member member) {
        return candidatePositions(member.pos, member.face);
    }

    public static List<BlockPos> candidatePositions(BlockPos pos, Direction face) {
        List<BlockPos> result = new ArrayList<>(7);
        result.add(pos);
        result.add(pos.relative(face));
        for (Direction direction : SCAN_ORDER) {
            BlockPos neighbour = pos.relative(direction);
            if (!result.contains(neighbour)) result.add(neighbour);
        }
        return result;
    }

    /** First candidate that is a loaded kinetic block entity, ignoring role validity; for client UI. */
    @Nullable
    public static KineticBlockEntity firstKinetic(@Nullable Level level, Member member) {
        if (level == null) return null;
        for (BlockPos pos : candidatePositions(member)) {
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) return be;
        }
        return null;
    }

    /** Server-side resolution used by the bridge: kinetic block entity fitting the endpoint role. */
    @Nullable
    public static KineticBlockEntity resolve(@Nullable Level level, Member member, Role expected) {
        if (level == null || level.isClientSide || member.role != expected) return null;
        for (BlockPos pos : candidatePositions(member)) {
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof KineticBlockEntity be)) continue;
            // A wireless output pretends to be a generator, so role checks have to look through
            // that override rather than trust isSource().
            boolean wireless = KineticBridgeState.isWireless(be);
            if (expected == Role.INPUT && (wireless || (!be.isSource()
                    && (!be.hasNetwork() || be.getOrCreateNetwork().sources.isEmpty())))) continue;
            if (expected == Role.OUTPUT && be.isSource() && !wireless) continue;
            return be;
        }
        return null;
    }
}
