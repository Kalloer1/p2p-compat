package com.kalloer1.p2pcreate.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.ChannelManager;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2pcreate.mixin.KineticNetworkAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-side saved state and live contribution index. Persisted keys are channel id + Member.key().
 * The live index is rebuilt every bridge tick, so unloaded/removed endpoints expire safely.
 *
 * <p>Create only ever gives a kinetic block a network when that block generates rotation: unpowered
 * gear trains keep {@code network == null} forever. A wireless output therefore cannot join a target
 * train, it has to become its generator for as long as the channel feeds it. That is what the claim
 * map below does - see {@link #drive} and {@link WirelessSource}.
 *
 * <p>1.20.1 适配点：{@code SavedData} 用无参 {@link #save(CompoundTag)} / 静态
 * {@link #load(CompoundTag)} 与 {@code computeIfAbsent(Function, Supplier, name)}（1.20.5 引入
 * SavedData.Factory / HolderLookup.Provider 形态）；{@code ResourceLocation} 用构造器。
 */
public final class KineticBridgeState extends SavedData {
    public static final String DATA_NAME = "p2pcreate_kinetic_bridge";
    public static volatile KineticBridgeState INSTANCE = new KineticBridgeState();

    /** Grace period before an output that stopped being fed is taken apart again. */
    private static final int CLAIM_EXPIRY_TICKS = 40;

    /** Create 创造马达的块注册 id：比 instanceof 更稳，也是"可豁免的真实动力源"的判定依据。 */
    private static final ResourceLocation CREATIVE_MOTOR_ID = new ResourceLocation("create", "creative_motor");
    /**
     * 无限供给（输入网络含创造马达）时的可用功率替身。不能直接传 Float.MAX_VALUE：
     * 分配器里会做减法和整除，取 MAX_VALUE/8 保证运算不溢出成 NaN/Infinity。
     */
    private static final float INFINITE_POWER = Float.MAX_VALUE / 8f;

    private static final Map<String, ClientOutputView> CLIENT_OUTPUTS = new HashMap<>();
    /** 客户端镜像：哪些「channelId:memberKey」输入端点所在网络由创造马达供能（无限供给）。 */
    private static final Set<String> CLIENT_INFINITE_SUPPLY = new HashSet<>();
    private final Map<String, OutputConfig> outputs = new HashMap<>();
    private final Map<Long, WirelessSource> claims = new HashMap<>();
    private final Map<String, String> pauseReasons = new HashMap<>();
    /** 服务端：本 tick 内判定为无限供给的「channelId:memberKey」输入端点（随桥 tick 重建）。 */
    private final Set<String> infiniteSupply = new HashSet<>();
    private final Map<Long, List<VirtualKineticContribution>> live = new HashMap<>();
    private final Map<Long, Float> inputStress = new HashMap<>();
    private final Map<Long, Float> outputSpeed = new HashMap<>();
    private final Map<String, Long> conflictNotices = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;

    public record OutputConfig(float rpm, boolean reverse) {
        public OutputConfig {
            if (!Float.isFinite(rpm)) rpm = 16;
            rpm = Math.max(1, Math.abs(rpm));
        }

        public float signedSpeed() { return reverse ? -rpm : rpm; }
    }

    /** Client mirror of an output: configured rpm/direction plus the server-reported pause reason. */
    public record ClientOutputView(float rpm, boolean reverse, boolean paused, String reason) {
        public ClientOutputView {
            if (!Float.isFinite(rpm)) rpm = 16;
            rpm = Math.max(1, Math.abs(rpm));
            reason = reason == null ? "" : reason;
        }

        public float signedSpeed() { return reverse ? -rpm : rpm; }
    }

    /**
     * One kinetic block the bridge currently drives as a wireless generator. The claim is what makes
     * {@code getGeneratedSpeed()} non-zero for that block, which is how the rotation survives Create's
     * periodic kinetic validation; dropping the claim stops the train again.
     */
    static final class WirelessSource {
        final int channelId;
        final String memberKey;
        final ResourceLocation dim;
        final BlockPos pos;
        float speed;
        long lastSeen;
        boolean released;

        WirelessSource(int channelId, String memberKey, ResourceLocation dim, BlockPos pos,
                float speed, long lastSeen) {
            this.channelId = channelId;
            this.memberKey = memberKey;
            this.dim = dim;
            this.pos = pos;
            this.speed = speed;
            this.lastSeen = lastSeen;
        }

        boolean owns(int channelId, String memberKey) {
            return this.channelId == channelId && this.memberKey.equals(memberKey);
        }

        boolean stale(long now) {
            return released || now - lastSeen >= CLAIM_EXPIRY_TICKS;
        }
    }

    public KineticBridgeState() {}

    public static KineticBridgeState load(CompoundTag tag) {
        KineticBridgeState state = new KineticBridgeState();
        ListTag list = tag.getList("Outputs", Tag.TAG_COMPOUND);
        for (Tag entry : list) {
            CompoundTag t = (CompoundTag) entry;
            state.outputs.put(t.getString("Key"), new OutputConfig(t.getFloat("Rpm"), t.getBoolean("Reverse")));
        }
        for (Tag entry : tag.getList("Wireless", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            if (!t.contains("Pos") || !t.contains("Dim")) continue;
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("Dim"));
            if (dim == null) continue;
            BlockPos pos = BlockPos.of(t.getLong("Pos"));
            state.claims.put(pos.asLong(), new WirelessSource(t.getInt("Channel"), t.getString("Member"),
                    dim, pos, t.getFloat("Speed"), t.getLong("Seen")));
        }
        return state;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        outputs.forEach((key, config) -> {
            CompoundTag t = new CompoundTag();
            t.putString("Key", key);
            t.putFloat("Rpm", config.rpm());
            t.putBoolean("Reverse", config.reverse());
            list.add(t);
        });
        tag.put("Outputs", list);

        ListTag wireless = new ListTag();
        claims.forEach((key, claim) -> {
            if (claim.released) return;
            CompoundTag t = new CompoundTag();
            t.putLong("Pos", key);
            t.putString("Dim", claim.dim.toString());
            t.putInt("Channel", claim.channelId);
            t.putString("Member", claim.memberKey);
            t.putFloat("Speed", claim.speed);
            t.putLong("Seen", claim.lastSeen);
            wireless.add(t);
        });
        tag.put("Wireless", wireless);
        return tag;
    }

    public static KineticBridgeState get(ServerLevel level) {
        KineticBridgeState state = level.getServer().overworld().getDataStorage().computeIfAbsent(
                KineticBridgeState::load, KineticBridgeState::new, DATA_NAME);
        INSTANCE = state;
        return state;
    }

    public synchronized OutputConfig getOutputConfig(int channelId, Member member, float maxRpm) {
        String key = outputKey(channelId, member);
        OutputConfig value = outputs.getOrDefault(key, new OutputConfig(Math.min(16, maxRpm), false));
        return clamp(value, maxRpm);
    }

    public static synchronized ClientOutputView getClientOutputConfig(int channelId, Member member, float maxRpm) {
        ClientOutputView value = CLIENT_OUTPUTS.getOrDefault(outputKey(channelId, member),
                new ClientOutputView(Math.min(16, maxRpm), false, false, ""));
        return clampView(value, maxRpm);
    }

    public static synchronized void setClientOutputConfig(int channelId, String memberKey, float rpm, boolean reverse,
            float maxRpm) {
        String key = channelId + ":" + memberKey;
        ClientOutputView previous = CLIENT_OUTPUTS.get(key);
        boolean paused = previous != null && previous.paused();
        String reason = previous == null ? "" : previous.reason();
        CLIENT_OUTPUTS.put(key, clampView(new ClientOutputView(rpm, reverse, paused, reason), maxRpm));
    }

    public static synchronized void replaceClientOutputConfigs(Collection<ClientOutputConfig> entries) {
        CLIENT_OUTPUTS.clear();
        for (ClientOutputConfig entry : entries) {
            CLIENT_OUTPUTS.put(entry.key(), new ClientOutputView(entry.rpm(), entry.reverse(),
                    entry.paused(), entry.reason()));
        }
    }

    /** 客户端：该输入端点所在网络是否为创造马达无限供给（面板显示 "∞ SU" 用）。 */
    public static boolean isClientInfiniteSupply(int channelId, String memberKey) {
        return CLIENT_INFINITE_SUPPLY.contains(channelId + ":" + memberKey);
    }

    /** 客户端：整表替换无限供给镜像（服务端全量快照驱动，兼容缺项）。 */
    public static void replaceClientInfiniteSupply(Collection<String> keys) {
        CLIENT_INFINITE_SUPPLY.clear();
        CLIENT_INFINITE_SUPPLY.addAll(keys);
    }

    /** 服务端：当前判定为无限供给的全部端点键快照（用于搭车同步）。 */
    public synchronized Set<String> getInfiniteSupplyKeys() {
        return new HashSet<>(infiniteSupply);
    }

    public synchronized void setOutputConfig(int channelId, String memberKey, float rpm, boolean reverse, float maxRpm) {
        outputs.put(channelId + ":" + memberKey,
                clamp(new OutputConfig(rpm, reverse), maxRpm));
        setDirty();
    }

    public synchronized void removeChannel(int channelId) {
        clearLiveChannel(channelId);
        String prefix = channelId + ":";
        outputs.keySet().removeIf(k -> k.startsWith(prefix));
        conflictNotices.keySet().removeIf(k -> k.startsWith(prefix));
        claims.values().forEach(claim -> { if (claim.channelId == channelId) claim.released = true; });
        pauseReasons.keySet().removeIf(k -> k.startsWith(prefix));
        setDirty();
    }

    public synchronized void pruneChannel(int channelId, Collection<Member> members) {
        clearLiveChannel(channelId);
        String prefix = channelId + ":";
        Set<String> active = new HashSet<>();
        for (Member member : members) {
            if (member.role == Role.OUTPUT) active.add(prefix + member.key());
        }
        if (outputs.keySet().removeIf(key -> key.startsWith(prefix) && !active.contains(key))) setDirty();
        claims.values().forEach(claim -> {
            if (claim.channelId == channelId && !active.contains(prefix + claim.memberKey)) claim.released = true;
        });
        pauseReasons.keySet().removeIf(key -> key.startsWith(prefix) && !active.contains(key));
    }

    /**
     * Remove the live contribution of one channel without touching other channels that share the
     * same Create network. This is used when P2P deletes or changes a channel before the next
     * transfer callback; contributions are otherwise rebuilt on the next callback tick.
     */
    public synchronized void clearLiveChannel(int channelId) {
        live.values().forEach(entries -> entries.removeIf(c -> c.channelId() == channelId));
        live.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        outputSpeed.clear();
        infiniteSupply.removeIf(k -> k.startsWith(channelId + ":"));
        for (List<VirtualKineticContribution> entries : live.values()) {
            for (VirtualKineticContribution contribution : entries) {
                outputSpeed.putIfAbsent(contribution.networkId(), contribution.targetSpeed());
            }
        }
    }

    private void releaseClaim(int channelId, String memberKey) {
        claims.values().forEach(claim -> {
            if (claim.channelId == channelId && claim.memberKey.equals(memberKey)) claim.released = true;
        });
    }

    private static OutputConfig clamp(OutputConfig config, float maxRpm) {
        float max = Math.max(1, maxRpm);
        return new OutputConfig(Math.min(max, config.rpm()), config.reverse());
    }

    private static ClientOutputView clampView(ClientOutputView view, float maxRpm) {
        float max = Math.max(1, maxRpm);
        return new ClientOutputView(Math.min(max, view.rpm()), view.reverse(), view.paused(), view.reason());
    }

    private static String outputKey(int channelId, Member member) { return channelId + ":" + member.key(); }

    /** Called by the P2P medium callback. */
    public synchronized int applyChannel(Channel channel, Map<ResourceLocation, Level> levels,
            List<Member> inputs, List<Member> outputsMembers, int rate, int tick) {
        long now = gameTime(levels);
        if (lastTick != tick) {
            live.clear();
            inputStress.clear();
            outputSpeed.clear();
            lastTick = tick;
        }
        String prefix = channel.id + ":";
        pauseReasons.keySet().removeIf(k -> k.startsWith(prefix));
        infiniteSupply.removeIf(k -> k.startsWith(prefix));

        List<InputEndpoint> validInputs = new ArrayList<>();
        for (Member member : inputs) {
            KineticBlockEntity be = resolveKinetic(levels.get(member.dim), member, Role.INPUT);
            if (be != null && be.hasNetwork() && Math.abs(be.getSpeed()) > 0)
                validInputs.add(new InputEndpoint(be, member));
        }
        List<OutputEndpoint> targets = new ArrayList<>();
        for (Member member : outputsMembers) {
            KineticBlockEntity be = resolveKinetic(levels.get(member.dim), member, Role.OUTPUT);
            if (be != null)
                targets.add(new OutputEndpoint(be, member, getOutputConfig(channel.id, member, maxRotationSpeed())));
        }
        // 输入网络含创造马达 ⇒ 该网络视为无限供给。放在端点早退之前：输出端全部
        // 暂停/缺失时客户端面板也要能显示 "∞ SU"。
        Set<Long> infiniteNetworks = new HashSet<>();
        for (InputEndpoint input : validInputs) {
            KineticNetwork network = input.be.getOrCreateNetwork();
            if (networkHasCreativeMotor(network)) {
                infiniteNetworks.add(network.id == null ? 0L : network.id);
                infiniteSupply.add(prefix + input.member.key());
            }
        }
        if (validInputs.isEmpty() || targets.isEmpty()) {
            claims.values().forEach(claim -> { if (claim.channelId == channel.id) claim.released = true; });
            if (tick % 20 == 0)
                P2P.LOGGER.info("[p2pcreate] channel #{} endpoint resolution: inputs {}->{} outputs {}->{} "
                                + "(an input needs a spinning Create network with a real source, an output is any "
                                + "loaded Create kinetic block)",
                        channel.id, inputs.size(), validInputs.size(), outputsMembers.size(), targets.size());
            return 0;
        }
        // Deterministic per-network winner: the first output on a Create network sets its speed,
        // so order by priority (then requested rpm) instead of by member-list order.
        targets.sort(Comparator.comparingInt((OutputEndpoint o) -> o.member.priority).reversed()
                .thenComparingDouble(o -> -Math.abs(o.config.signedSpeed())));

        List<Drive> drives = new ArrayList<>();
        List<KineticBridgeMath.OutputDemand> demands = new ArrayList<>();
        for (OutputEndpoint output : targets) {
            KineticBlockEntity be = output.be;
            float signedSpeed = output.config.signedSpeed();
            String reason = conflictAt(be, channel.id, output.member.key(), signedSpeed);
            if (!reason.isEmpty()) {
                release(channel.id, output.member.key(), levels.get(output.member.dim), be);
                pauseReasons.put(prefix + output.member.key(), reason);
                notifyConflict(channel, reason, levels, tick);
                continue;
            }
            drive(be, levels.get(output.member.dim), channel, output.member, signedSpeed, now);
            KineticNetwork network = be.getOrCreateNetwork();
            long networkId = network.id == null ? be.getBlockPos().asLong() : network.id;
            outputSpeed.putIfAbsent(networkId, signedSpeed);
            float impact = 0;
            for (Float value : network.members.values()) impact += Math.max(0, value);
            double distance = validInputs.stream()
                    .mapToDouble(input -> input.member.pos.distSqr(output.member.pos))
                    .min().orElse(0);
            drives.add(new Drive(output.member.key(), network, networkId, signedSpeed));
            demands.add(new KineticBridgeMath.OutputDemand(output.member.key(),
                    KineticBridgeMath.power(impact, signedSpeed), output.member.priority, distance));
        }
        if (drives.isEmpty()) return 0;

        float availablePower = 0;
        boolean infiniteSupplyNetwork = false;
        Set<Long> countedNetworks = new HashSet<>();
        for (InputEndpoint input : validInputs) {
            KineticNetwork network = input.be.getOrCreateNetwork();
            long networkId = network.id == null ? 0 : network.id;
            // Create's capacity sum is already scaled by each generator's speed, so a network
            // contributes its stored capacity once - multiplying by rpm again would inflate it.
            if (countedNetworks.add(networkId)) {
                if (infiniteNetworks.contains(networkId)) {
                    // 创造马达网络：容量上限不再参与分配，所有输出按需取满。
                    infiniteSupplyNetwork = true;
                } else {
                    availablePower += Math.max(0,
                            ((KineticNetworkAccessor) network).p2pcreate$getCurrentCapacity());
                }
            }
        }
        if (infiniteSupplyNetwork) availablePower = INFINITE_POWER;
        float requestedPower = 0;
        for (KineticBridgeMath.OutputDemand demand : demands) requestedPower += demand.requestedPower();
        if (availablePower <= 0 && !infiniteSupplyNetwork && tick % 20 == 0) {
            StringBuilder detail = new StringBuilder();
            for (InputEndpoint input : validInputs) {
                KineticNetwork n = input.be.getOrCreateNetwork();
                detail.append(' ').append(((KineticNetworkAccessor) n).p2pcreate$getCurrentCapacity())
                        .append("x").append(input.be.getSpeed());
            }
            P2P.LOGGER.info("[p2pcreate] channel #{}: input networks provide no power (capacity x speed:{}).",
                    channel.id, detail);
        }
        List<KineticBridgeMath.Allocation> allocations =
                KineticBridgeMath.allocate(channel.distribution, demands, availablePower, tick);
        Map<String, Float> allocatedByKey = new HashMap<>();
        for (KineticBridgeMath.Allocation allocation : allocations) allocatedByKey.put(allocation.memberKey(), allocation.power());

        for (Drive drive : drives) {
            float allocated = allocatedByKey.getOrDefault(drive.memberKey(), 0f);
            live.computeIfAbsent(drive.networkId(), ignored -> new ArrayList<>()).add(new VirtualKineticContribution(
                    channel.id, drive.memberKey(), drive.networkId(), allocated, drive.signedSpeed()));
        }
        for (InputEndpoint input : validInputs) {
            KineticNetwork network = input.be.getOrCreateNetwork();
            long networkId = network.id == null ? 0 : network.id;
            float demanded = requestedPower / Math.max(1, validInputs.size());
            inputStress.merge(networkId, demanded, Float::sum);
        }
        for (Drive drive : drives) drive.network().updateNetwork();
        for (InputEndpoint input : validInputs) input.be.getOrCreateNetwork().updateNetwork();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, requestedPower));
    }

    /**
     * Why this output cannot drive its target right now, or "" when it can. A target that already
     * has a genuine generator stays with it, and one network can only spin at one speed, so the
     * first claim on it wins.
     */
    private String conflictAt(KineticBlockEntity be, int channelId, String memberKey, float signedSpeed) {
        WirelessSource own = claims.get(be.getBlockPos().asLong());
        if (own != null && !own.owns(channelId, memberKey)) return "shared";
        if (!be.hasNetwork()) return "";
        KineticNetwork network = be.getOrCreateNetwork();
        for (KineticBlockEntity source : network.sources.keySet()) {
            if (!isPresent(source)) continue;
            WirelessSource claim = claims.get(source.getBlockPos().asLong());
            if (claim == null) {
                // 创造马达是"可豁免"的真实动力源：输出与之共存不判 real_source（无限供给场景）。
                // shared（另一个 P2P 输出在驱动）与 speed_conflict 的暂停语义不受影响。
                if (isCreativeMotor(source)) continue;
                return "real_source";
            }
            if (!claim.owns(channelId, memberKey)) return "shared";
        }
        Float claimed = outputSpeed.get(network.id == null ? 0L : network.id);
        if (claimed == null) return "";
        return Math.abs(claimed - signedSpeed) > 0.001f ? "speed_conflict" : "shared";
    }

    /** Still the block entity at its own position, ie not replaced or removed without a network update. */
    private static boolean isPresent(KineticBlockEntity be) {
        Level level = be.getLevel();
        return level != null && level.getBlockEntity(be.getBlockPos()) == be;
    }

    /** 创造马达判定：块注册 id == create:creative_motor（比 BE 类型判断更稳）。 */
    private static boolean isCreativeMotor(KineticBlockEntity be) {
        return CREATIVE_MOTOR_ID.equals(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()));
    }

    /** 输入网络的真实动力源里是否有创造马达（有 ⇒ 无限供给）。 */
    private static boolean networkHasCreativeMotor(KineticNetwork network) {
        for (KineticBlockEntity source : network.sources.keySet()) {
            if (isPresent(source) && isCreativeMotor(source)) return true;
        }
        return false;
    }

    /** Turn the resolved output block into the generator of its own train. */
    private void drive(KineticBlockEntity be, @Nullable Level level, Channel channel, Member member,
            float signedSpeed, long now) {
        BlockPos pos = be.getBlockPos();
        long key = pos.asLong();
        WirelessSource claim = claims.get(key);
        if (claim == null) {
            claims.put(key, new WirelessSource(channel.id, member.key(), member.dim, pos, signedSpeed, now));
            setDirty();
        } else {
            claim.speed = signedSpeed;
            claim.lastSeen = now;
            claim.released = false;
        }
        if (level == null || level.isClientSide) return;

        float previous = be.getTheoreticalSpeed();
        if (be.hasNetwork() && Math.abs(previous - signedSpeed) <= 0.001f) return;
        // Mirrors GeneratingKineticBlockEntity.applyNewSpeed: a generator that changes speed first
        // orphans its train, otherwise the propagator sees the same network at two speeds and
        // destroys the block.
        if (Math.abs(previous) > 1e-4f) be.detachKinetics();
        be.setSpeed(signedSpeed);
        if (!be.hasNetwork()) be.setNetwork(key);
        be.attachKinetics();
        be.onSpeedChanged(previous);
        be.sendData();
    }

    /** Give a block back to Create: stop driving it unless a real generator took it over meanwhile. */
    private void release(int channelId, String memberKey, @Nullable Level level, KineticBlockEntity be) {
        WirelessSource own = claims.get(be.getBlockPos().asLong());
        if (own == null || !own.owns(channelId, memberKey)) return;
        claims.remove(be.getBlockPos().asLong());
        setDirty();
        stopDriving(level, be);
    }

    /**
     * Undo {@link #drive}. A train that meanwhile found a genuine generator keeps running, so the
     * block is only taken apart while every source on its network is one of our claims.
     */
    private void stopDriving(@Nullable Level level, KineticBlockEntity be) {
        if (level == null || level.isClientSide) return;
        if (be.hasNetwork()) {
            for (KineticBlockEntity source : be.getOrCreateNetwork().sources.keySet()) {
                if (source == be || !isPresent(source)) continue;
                if (!claims.containsKey(source.getBlockPos().asLong())) return;
            }
        }
        float previous = be.getTheoreticalSpeed();
        if (previous == 0 && !be.hasNetwork()) return;
        if (previous != 0) be.detachKinetics();
        be.setSpeed(0);
        be.setNetwork(null);
        be.onSpeedChanged(previous);
        be.sendData();
    }

    /**
     * Take apart claims whose channel stopped feeding them. Called every server tick, because a
     * channel that goes quiet (unloaded, deleted endpoint, redstone gate) never runs its callback
     * again and would otherwise keep its target trains spinning forever.
     */
    public synchronized void sweep(MinecraftServer server) {
        if (claims.isEmpty()) return;
        long now = server.overworld().getGameTime();
        if (now <= 0) return;
        List<WirelessSource> expired = new ArrayList<>();
        Iterator<WirelessSource> iterator = claims.values().iterator();
        while (iterator.hasNext()) {
            WirelessSource claim = iterator.next();
            if (claim.stale(now)) {
                iterator.remove();
                expired.add(claim);
            }
        }
        if (expired.isEmpty()) return;
        setDirty();
        for (WirelessSource claim : expired) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, claim.dim));
            if (level == null || !level.isLoaded(claim.pos)) continue;
            if (level.getBlockEntity(claim.pos) instanceof KineticBlockEntity be) stopDriving(level, be);
        }
    }

    private static long gameTime(Map<ResourceLocation, Level> levels) {
        for (Level level : levels.values()) if (level != null) return level.getGameTime();
        return 0;
    }

    public synchronized float additionalStress(long networkId) { return inputStress.getOrDefault(networkId, 0f); }

    public synchronized float additionalCapacity(long networkId) {
        float value = 0;
        for (VirtualKineticContribution c : live.getOrDefault(networkId, List.of())) value += c.suppliedCapacity();
        return value;
    }

    /** The rpm this block currently generates for a wireless output, if any. */
    public synchronized Optional<Float> wirelessSpeed(@Nullable KineticBlockEntity be) {
        if (be == null) return Optional.empty();
        return wirelessSpeed(be.getLevel(), be.getBlockPos());
    }

    public static boolean isWireless(@Nullable KineticBlockEntity be) {
        return be != null && INSTANCE.wirelessSpeed(be.getLevel(), be.getBlockPos()).isPresent();
    }

    private Optional<Float> wirelessSpeed(@Nullable Level level, BlockPos pos) {
        WirelessSource claim = claims.get(pos.asLong());
        if (claim == null || claim.released) return Optional.empty();
        if (level != null && !claim.dim.equals(level.dimension().location())) return Optional.empty();
        return Optional.of(claim.speed);
    }

    /** Pause reason for one output from the latest live bridge tick; empty when it runs normally. */
    public synchronized String getOutputPauseReason(int channelId, String memberKey) {
        String reason = pauseReasons.get(channelId + ":" + memberKey);
        return reason == null ? "" : reason;
    }

    private void notifyConflict(Channel channel, String reason, Map<ResourceLocation, Level> levels, long tick) {
        String key = channel.id + ":" + reason;
        long previous = conflictNotices.getOrDefault(key, Long.MIN_VALUE);
        if (tick - previous < 40) return;
        conflictNotices.put(key, tick);

        ServerLevel serverLevel = levels.values().stream()
                .filter(ServerLevel.class::isInstance)
                .map(ServerLevel.class::cast)
                .findFirst().orElse(null);
        if (serverLevel == null || serverLevel.getServer() == null) return;
        String messageKey = switch (reason) {
            case "real_source" -> "p2pcreate.chat.conflict_real_source";
            case "speed_conflict" -> "p2pcreate.chat.conflict_speed";
            case "shared" -> "p2pcreate.chat.conflict_shared";
            default -> "p2pcreate.chat.output_conflict";
        };
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (ChannelManager.INSTANCE.canEditChannel(player, channel)) {
                player.displayClientMessage(Component.translatable(messageKey, channel.name), true);
            }
        }
    }

    @Nullable
    public KineticBlockEntity resolveKinetic(@Nullable Level level, Member member, Role expected) {
        return KineticEndpoints.resolve(level, member, expected);
    }

    public static float maxRotationSpeed() {
        try {
            Class<?> cfg = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs");
            Object server = cfg.getMethod("server").invoke(null);
            Object kinetics = server.getClass().getField("kinetics").get(server);
            Object max = kinetics.getClass().getField("maxRotationSpeed").get(kinetics);
            Object value = max.getClass().getMethod("get").invoke(max);
            return Math.max(1, ((Number) value).floatValue());
        } catch (ReflectiveOperationException ignored) {
            return 256;
        }
    }

    public record ClientOutputConfig(int channelId, String memberKey, float rpm, boolean reverse,
            boolean paused, String reason) {
        public String key() { return channelId + ":" + memberKey; }
    }

    private record InputEndpoint(KineticBlockEntity be, Member member) {}
    private record OutputEndpoint(KineticBlockEntity be, Member member, OutputConfig config) {}
    private record Drive(String memberKey, KineticNetwork network, long networkId, float signedSpeed) {}
}
