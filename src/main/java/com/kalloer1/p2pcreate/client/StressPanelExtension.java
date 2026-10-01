package com.kalloer1.p2pcreate.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.kalloer1.p2p.Lang;
import com.kalloer1.p2p.api.ChannelScreenExtension;
import com.kalloer1.p2p.api.DetailContext;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2p.client.screen.ChannelScreen;
import com.kalloer1.p2p.client.screen.NameInputScreen;
import com.kalloer1.p2p.client.screen.P2PTheme;
import com.kalloer1.p2p.network.ChannelActionC2SPacket;
import com.kalloer1.p2pcompat.network.CompatNetwork;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.bridge.KineticEndpoints;
import com.kalloer1.p2pcreate.mixin.KineticBlockEntityAccessor;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Create 应力频道的详情面板扩展（本体 1.1.0 P3 缝，取代旧 ChannelScreenMixin）。
 * replacesDefaultPanel=true：本体在接管态不绘制任何右侧内容，本类独占 {@link DetailContext}
 * 给出的 244x160 帧（1.2.0 几何契约，规格 §4），按「状态卡 + 端点列表卡」两段式重绘。
 * 纯客户端类：只在 CompatCreate.registerClient()（宿主 create 在场时）里实例化。
 *
 * <p>绘制全部走本体公开的主题层 {@link P2PTheme}（旧私有 bevel 与 0xFF252525 系硬编码色已删除）；
 * 应力单位取自 {@code p2p.unit.create_stress}，不再硬编码 " SU"。
 *
 * <p>1.20.1 适配点：{@code Block#getCloneItemStack} 无 (state, hit, level, pos, player) 重载，
 * 改用 (BlockGetter, BlockPos, BlockState) 形态；{@code BlockEntity#saveWithFullMetadata()}
 * 无 registryAccess 参数；发包走 compat 通道（{@link CompatNetwork#sendToServer}）。
 */
public final class StressPanelExtension implements ChannelScreenExtension {
    /** 状态卡高（标题 / 两列读数 / 分隔线 / 状态 pill）。 */
    private static final int STATUS_H = 56;
    /** 两段卡片间距（规格 §2 的 8pt 网格在 160 帧内按 4 细分，保证列表卡底边与帧底对齐）。 */
    private static final int GAP = 4;
    /** 列表卡内的分区头高。 */
    private static final int HEAD_H = 14;
    /** 列表卡内的脚注高。 */
    private static final int FOOT_H = 12;
    private static final int ROW_H = DetailContext.ROW_H;

    private int memberScroll;
    private final List<ActionHit> actionHits = new ArrayList<>();
    // Core renders the selected channel every frame before any click can arrive; input
    // callbacks lack a channel parameter, so the last drawn one is captured here.
    @Nullable
    private Channel rendered;

    private record ActionHit(int memberIndex, ActionKind kind, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private enum ActionKind { RPM, DIRECTION, PRIORITY, DELETE, ROLE }

    @Override
    public boolean replacesDefaultPanel(@Nullable Channel channel) {
        return true;
    }

    @Override
    public void drawDetails(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        rendered = channel;
        drawStressPanel(ctx, graphics, channel);
    }

    @Override
    public boolean mouseClicked(DetailContext ctx, double mouseX, double mouseY, int button) {
        Channel channel = rendered;
        if (channel == null) return false;
        if (!inFrame(ctx, mouseX, mouseY)) return false;
        // Every button inside the stress table is consumed: the generic role/face/delete
        // controls hidden underneath must never react to right- or middle-clicks.
        if (button != 0) return true;

        for (ActionHit hit : actionHits) {
            if (!hit.contains(mouseX, mouseY)) continue;
            Member member = memberAt(channel, hit.memberIndex());
            if (member != null) {
                switch (hit.kind()) {
                    case RPM -> {
                        var config = KineticBridgeState.getClientOutputConfig(channel.id, member,
                                KineticBridgeState.maxRotationSpeed());
                        if (ctx.minecraft.screen instanceof ChannelScreen screen) ctx.minecraft.setScreen(
                                new RpmInputScreen(screen, channel.id, member.key(), config.rpm(),
                                        config.reverse(), KineticBridgeState.maxRotationSpeed()));
                    }
                    case DIRECTION -> {
                        var config = KineticBridgeState.getClientOutputConfig(channel.id, member,
                                KineticBridgeState.maxRotationSpeed());
                        KineticBridgeState.setClientOutputConfig(channel.id, member.key(), config.rpm(),
                                !config.reverse(), KineticBridgeState.maxRotationSpeed());
                        // rpm < 0 + DIR_TOGGLE: the server flips direction and keeps its own rpm,
                        // so a stale client cache can never overwrite the other field.
                        CompatNetwork.sendToServer(new P2PCreateNetwork.SetOutputConfigPayload(
                                channel.id, member.key(), -1, P2PCreateNetwork.DIR_TOGGLE));
                    }
                    case PRIORITY -> {
                        if (channel.distribution == Channel.Distribution.PRIORITY
                                && ctx.minecraft.screen instanceof ChannelScreen screen) ctx.minecraft.setScreen(
                                new NameInputScreen(screen, Lang.t(Lang.UI_SET_PRIORITY),
                                        String.valueOf(member.priority), 2, channel.id, member.key()));
                    }
                    case DELETE -> ctx.sendMemberAction(channel.id,
                            ChannelActionC2SPacket.Action.REMOVE_MEMBER, 0, member.key());
                    case ROLE -> ctx.sendMemberAction(channel.id,
                            ChannelActionC2SPacket.Action.SET_ROLE,
                            member.role == Role.INPUT ? Role.OUTPUT.ordinal() : Role.INPUT.ordinal(),
                            member.key());
                }
            }
            return true;
        }

        // Consume clicks inside the stress table so the generic filter/priority/face controls
        // underneath the painted replacement cannot be activated accidentally.
        return true;
    }

    @Override
    public boolean mouseScrolled(DetailContext ctx, double mouseX, double mouseY, double scrollX, double scrollY) {
        Channel channel = rendered;
        if (channel == null) return false;
        if (!inFrame(ctx, mouseX, mouseY)) return false;
        memberScroll = clampScroll(memberScroll - (int) Math.signum(scrollY) * ROW_H, channel, ctx);
        return true;
    }

    private boolean inFrame(DetailContext ctx, double mouseX, double mouseY) {
        return mouseX >= ctx.frameX() && mouseX < ctx.frameX() + ctx.frameWidth()
                && mouseY >= ctx.frameTop() && mouseY < ctx.frameBottom();
    }

    private void drawStressPanel(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        actionHits.clear();
        drawStatusCard(ctx, graphics, channel);
        drawEndpointsCard(ctx, graphics, channel);
    }

    private void drawStatusCard(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        int fx = ctx.frameX();
        int fw = ctx.frameWidth();
        int fy = ctx.frameTop();
        Font font = ctx.font;

        List<Member> inputs = members(channel, Role.INPUT);
        List<Member> outputs = members(channel, Role.OUTPUT);
        float inputSupply = 0;
        boolean missingInput = false;
        boolean infiniteInput = false;
        for (Member member : inputs) {
            // 创造马达供能的输入网络：服务端标记为无限供给，汇总显示 "∞ SU"。
            if (KineticBridgeState.isClientInfiniteSupply(channel.id, member.key())) {
                infiniteInput = true;
                continue;
            }
            float value = inputSupply(ctx, member);
            if (value < 0) missingInput = true;
            else inputSupply += value;
        }
        float outputDemand = 0;
        boolean missingOutput = false;
        boolean anyPausedOutput = false;
        for (Member member : outputs) {
            float value = outputDemand(ctx, member);
            if (value < 0) missingOutput = true;
            else outputDemand += value;
            if (KineticBridgeState.getClientOutputConfig(channel.id, member,
                    KineticBridgeState.maxRotationSpeed()).paused()) anyPausedOutput = true;
        }

        P2PTheme.card(graphics, fx, fy, fw, STATUS_H);
        P2PTheme.drawStringBold(graphics, font, Component.translatable("p2pcreate.gui.stress_status"),
                fx + P2PTheme.PAD, fy + 6, P2PTheme.TEXT_PRIMARY);

        graphics.drawString(font, Component.translatable("p2pcreate.gui.input_available",
                        formatStress(inputSupply, missingInput, infiniteInput)),
                fx + P2PTheme.PAD, fy + 19, P2PTheme.TEXT_PRIMARY, false);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.output_demand",
                        formatStress(outputDemand, missingOutput)),
                fx + 128, fy + 19, P2PTheme.TEXT_PRIMARY, false);

        P2PTheme.separator(graphics, fx, fy + 32, fw);

        String statusKey;
        int statusColor;
        if (inputs.isEmpty() || outputs.isEmpty()) { statusKey = "p2pcreate.gui.status.missing_io"; statusColor = P2PTheme.WARNING; }
        else if (missingInput || missingOutput) { statusKey = "p2pcreate.gui.status.unloaded"; statusColor = P2PTheme.WARNING; }
        else if (anyPausedOutput) { statusKey = "p2pcreate.gui.status.paused"; statusColor = P2PTheme.WARNING; }
        else { statusKey = "p2pcreate.gui.status.connected"; statusColor = P2PTheme.SUCCESS; }
        Component status = Component.translatable("p2pcreate.gui.status", Component.translatable(statusKey));
        int sw = font.width(status) + 10;
        P2PTheme.chip(graphics, fx + (fw - sw) / 2, fy + 38, sw, P2PTheme.PILL_H, statusColor);
        P2PTheme.drawCentered(graphics, font, status, fx + (fw - sw) / 2, fy + 40, sw, 0xFFFFFFFF);
    }

    private void drawEndpointsCard(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        int fx = ctx.frameX();
        int fw = ctx.frameWidth();
        int listTop = ctx.frameTop() + STATUS_H + GAP;
        int listBottom = ctx.frameBottom();
        Font font = ctx.font;

        P2PTheme.card(graphics, fx, listTop, fw, listBottom - listTop);
        int rowsTop = listTop + HEAD_H;
        int rowsBottom = listBottom - FOOT_H;
        P2PTheme.drawStringBold(graphics, font,
                Component.translatable("p2pcreate.gui.endpoints", channel.members.size()),
                fx + P2PTheme.PAD, listTop + 3, P2PTheme.TEXT_PRIMARY);

        memberScroll = clampScroll(memberScroll, channel, ctx);
        graphics.enableScissor(fx + 1, rowsTop, fx + fw - 1, rowsBottom);
        int y = rowsTop - memberScroll;
        for (int index = 0; index < channel.members.size(); index++) {
            Member member = channel.members.get(index);
            if (member.role != Role.INPUT) continue;
            drawMemberRow(ctx, graphics, channel, member, index, y, rowsTop, rowsBottom, false);
            y += ROW_H;
        }
        for (int index = 0; index < channel.members.size(); index++) {
            Member member = channel.members.get(index);
            if (member.role != Role.OUTPUT) continue;
            drawMemberRow(ctx, graphics, channel, member, index, y, rowsTop, rowsBottom, true);
            y += ROW_H;
        }
        if (channel.members.isEmpty()) {
            graphics.drawString(font, Component.translatable("p2pcreate.gui.no_endpoints"),
                    fx + P2PTheme.PAD, rowsTop + 6, P2PTheme.TEXT_SECOND, false);
        }
        graphics.disableScissor();
        graphics.drawString(font, Component.translatable("p2pcreate.gui.rpm_hint"),
                fx + P2PTheme.PAD, rowsBottom + 2, P2PTheme.TEXT_TERT, false);
    }

    private void drawMemberRow(DetailContext ctx, GuiGraphics graphics, Channel channel, Member member,
            int memberIndex, int y, int rowsTop, int rowsBottom, boolean output) {
        int fx = ctx.frameX();
        Font font = ctx.font;
        if (y + ROW_H <= rowsTop || y >= rowsBottom) return;
        graphics.fill(fx + P2PTheme.PAD, y + ROW_H, fx + ctx.frameWidth() - P2PTheme.PAD, y + ROW_H + 1,
                P2PTheme.SEPARATOR);

        int x0 = fx + P2PTheme.PAD;
        int right = x0 + 228;   // 帧内右边界（244 - 2*8）
        P2PTheme.drawRoundRect(graphics, x0, y + 1, P2PTheme.WELL, P2PTheme.WELL, P2PTheme.R_SLOT,
                P2PTheme.FIELD_BG);
        ItemStack icon = endpointIcon(ctx, member);
        if (!icon.isEmpty()) graphics.renderItem(icon, x0, y + 1);

        int badgeX = x0 + 18;
        P2PTheme.roleBadge(graphics, badgeX, y + 3, !output);
        P2PTheme.drawCentered(graphics, font, Component.literal(output ? "OUT" : "IN"),
                badgeX, y + 5, P2PTheme.BADGE_W, 0xFFFFFFFF);
        actionHits.add(new ActionHit(memberIndex, ActionKind.ROLE, badgeX, y + 2, P2PTheme.BADGE_W, ROW_H - 4));

        String key = trimWidth(font, shortKey(ctx, member), 44);
        graphics.drawString(font, Component.literal(key), x0 + 44, y + 5, P2PTheme.TEXT_PRIMARY, false);

        // Same predicate the server bridge uses to resolve endpoints: a row that fails it can
        // never transfer, so surface why (bound to a non-kinetic block, unloaded, …) on the row.
        Component warning = endpointWarning(ctx, member, output);

        if (!output) {
            if (warning != null) {
                drawRightAligned(graphics, font, Component.literal("!" + warning.getString()),
                        right - 22, y + 5, P2PTheme.DANGER);
            } else if (KineticBridgeState.isClientInfiniteSupply(channel.id, member.key())) {
                drawRightAligned(graphics, font,
                        Component.translatable("p2pcreate.gui.supply", formatStress(0, false, true)),
                        right - 22, y + 5, P2PTheme.WARNING);
            } else {
                float supplied = inputSupply(ctx, member);
                drawRightAligned(graphics, font,
                        Component.translatable("p2pcreate.gui.supply", formatStress(supplied, supplied < 0)),
                        right - 22, y + 5, P2PTheme.WARNING);
            }
        } else {
            var config = KineticBridgeState.getClientOutputConfig(channel.id, member,
                    KineticBridgeState.maxRotationSpeed());
            boolean marked = warning != null || config.paused();
            graphics.drawString(font, Component.literal(Math.round(config.rpm()) + " " + unit()),
                    x0 + 90, y + 5, marked ? P2PTheme.TEXT_TERT : P2PTheme.WARNING, false);
            Component dirLabel = warning != null ? warning : config.paused()
                    ? reasonLabel(config.reason())
                    : Component.translatable(config.reverse()
                            ? "p2pcreate.gui.counter_clockwise" : "p2pcreate.gui.clockwise");
            // The direction cell doubles as the toggle control and carries the row's warning /
            // pause reason when the output cannot run. 44px（可用 40）放得下 zh 三字/英文短词，
            // 配合 lang 缩短后的原因文案（被驱动/冲突/暂停、Local/Clash/In use/Halt）不再截断。
            int dirW = 44;
            P2PTheme.pillBackground(graphics, x0 + 124, y + 2, dirW, P2PTheme.PILL_H, false, true);
            P2PTheme.drawCentered(graphics, font,
                    Component.literal(trimWidth(font, (marked ? "!" : "") + dirLabel.getString(), dirW - 4)),
                    x0 + 124, y + 4, dirW, marked ? P2PTheme.WARNING : P2PTheme.TEXT_PRIMARY);
            actionHits.add(new ActionHit(memberIndex, ActionKind.DIRECTION, x0 + 124, y + 2, dirW, P2PTheme.PILL_H));
            if (channel.distribution == Channel.Distribution.PRIORITY) {
                // Keep the per-output priority editor compact so RPM, direction, and delete
                // remain available in the same row.
                int pW = 16;
                P2PTheme.pillBackground(graphics, x0 + 168, y + 2, pW, P2PTheme.PILL_H, false, true);
                P2PTheme.drawCentered(graphics, font, Component.literal("P" + member.priority),
                        x0 + 168, y + 4, pW, P2PTheme.TEXT_PRIMARY);
                actionHits.add(new ActionHit(memberIndex, ActionKind.PRIORITY, x0 + 168, y + 2, pW, P2PTheme.PILL_H));
            }
            int rpmW = 26;
            P2PTheme.chip(graphics, x0 + 186, y + 2, rpmW, P2PTheme.PILL_H, P2PTheme.TINT);
            P2PTheme.drawCentered(graphics, font, Component.translatable("p2pcreate.gui.rpm"),
                    x0 + 186, y + 4, rpmW, 0xFFFFFFFF);
            actionHits.add(new ActionHit(memberIndex, ActionKind.RPM, x0 + 186, y + 2, rpmW, P2PTheme.PILL_H));
        }
        P2PTheme.deleteCross(graphics, right - 14, y + 2, false);
        actionHits.add(new ActionHit(memberIndex, ActionKind.DELETE, right - 14, y + 2, P2PTheme.CIRCLE,
                P2PTheme.CIRCLE));
    }

    private void drawRightAligned(GuiGraphics graphics, Font font, Component text, int rightEdge, int y, int color) {
        graphics.drawString(font, text, rightEdge - font.width(text), y, color, false);
    }

    /** Mirrors the base screen's icon resolution: live pick-stack (with BE NBT) first, bound blockId fallback. */
    private ItemStack endpointIcon(DetailContext ctx, Member member) {
        Level level = ctx.minecraft.level;
        if (level != null && level.dimension().location().equals(member.dim)) {
            // When the clicked block is only a carrier (e.g. the tunnel) and the kinetic machine is
            // on an adjacent face, show what the endpoint actually drives instead of the carrier.
            KineticBlockEntity resolved = KineticEndpoints.firstKinetic(level, member);
            BlockPos target = resolved != null ? resolved.getBlockPos() : member.pos;
            BlockState state = level.getBlockState(target);
            Block block = state.getBlock();
            if (block != Blocks.AIR && block.asItem() != Items.AIR) {
                // 1.20.1：无 (state, hit, level, pos, player) 重载，用 (BlockGetter, BlockPos, BlockState)。
                ItemStack pick = block.getCloneItemStack(level, target, state);
                if (pick.isEmpty()) pick = new ItemStack(block.asItem());
                BlockEntity be = level.getBlockEntity(target);
                if (be != null) {
                    CompoundTag beTag = be.saveWithFullMetadata();
                    beTag.remove("id");
                    BlockItem.setBlockEntityData(pick, be.getType(), beTag);
                }
                return pick;
            }
        }
        if (member.blockId != null) {
            // BuiltInRegistries.BLOCK is defaulted: unknown ids return AIR rather than null.
            Block block = BuiltInRegistries.BLOCK.get(member.blockId);
            if (block != Blocks.AIR && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
        }
        return ItemStack.EMPTY;
    }

    /** Why this endpoint cannot participate in the bridge right now; null when it resolves fine. */
    @Nullable
    private Component endpointWarning(DetailContext ctx, Member member, boolean output) {
        Level level = ctx.minecraft.level;
        if (level == null || !level.dimension().location().equals(member.dim))
            return Component.translatable("p2pcreate.gui.row.unloaded");
        KineticBlockEntity be = KineticEndpoints.firstKinetic(level, member);
        if (be == null) {
            boolean anyLoaded = false;
            for (var pos : KineticEndpoints.candidatePositions(member)) {
                if (level.isLoaded(pos)) { anyLoaded = true; break; }
            }
            return Component.translatable(anyLoaded ? "p2pcreate.gui.row.not_kinetic" : "p2pcreate.gui.row.unloaded");
        }
        if (!output && !be.hasNetwork()) return Component.translatable("p2pcreate.gui.row.no_network");
        if (!output && Math.abs(be.getSpeed()) < 0.001f) return Component.translatable("p2pcreate.gui.row.idle");
        return null;
    }

    private int clampScroll(int scroll, Channel channel, DetailContext ctx) {
        int viewport = ctx.frameBottom() - ctx.frameTop() - STATUS_H - GAP - HEAD_H - FOOT_H;
        return Math.max(0, Math.min(Math.max(0, sectionHeight(channel) - viewport), scroll));
    }

    private int sectionHeight(Channel channel) {
        int rows = 0;
        for (Member member : channel.members) {
            if (member.role == Role.INPUT || member.role == Role.OUTPUT) rows++;
        }
        return rows * ROW_H;
    }

    private float inputSupply(DetailContext ctx, Member member) {
        KineticBlockEntity be = resolveMember(ctx, member);
        if (be == null || !be.hasNetwork()) return -1;
        // Read the values Create syncs onto the block entity; client-side KineticNetwork objects
        // are phantoms whose currentCapacity is never populated.
        return Math.max(0, ((KineticBlockEntityAccessor) (Object) be).p2pcreate$getSyncedCapacity());
    }

    private float outputDemand(DetailContext ctx, Member member) {
        KineticBlockEntity be = resolveMember(ctx, member);
        if (be == null) return -1;
        // An unpowered train has no network yet, which is exactly the case a wireless output
        // creates: read the synced stress and let the bridge form the network.
        return Math.max(0, ((KineticBlockEntityAccessor) (Object) be).p2pcreate$getSyncedStress());
    }

    /** Client mirror of the bridge's endpoint resolution (shared candidate order in KineticEndpoints). */
    @Nullable
    private KineticBlockEntity resolveMember(DetailContext ctx, Member member) {
        Level level = ctx.minecraft.level;
        if (level == null || !level.dimension().location().equals(member.dim)) return null;
        return KineticEndpoints.firstKinetic(level, member);
    }

    /** 应力单位（{@code p2p.unit.create_stress}）；没有配键时退化为空串，只显数值。 */
    private String unit() {
        String unit = Lang.unitOf("create:stress");
        return unit.isEmpty() ? "" : " " + unit;
    }

    private String formatStress(float value, boolean missing) {
        return formatStress(value, missing, false);
    }

    /** infinite=true（创造马达无限供给）时显示 "∞ SU"，不再展示幽灵网络的同步容量。 */
    private String formatStress(float value, boolean missing, boolean infinite) {
        if (infinite) return "∞" + unit();
        return missing ? "—" : formatNumber(value) + unit();
    }

    private String formatNumber(float value) {
        if (!Float.isFinite(value)) return "—";
        if (value >= 1000000) return String.format("%.1fM", value / 1000000f);
        if (value >= 1000) return String.format("%.1fk", value / 1000f);
        return String.valueOf(Math.round(value));
    }

    private String shortKey(DetailContext ctx, Member member) {
        Level level = ctx.minecraft.level;
        boolean sameDim = level != null && level.dimension().location().equals(member.dim);
        String face = Component.translatable(
                "p2pcreate.gui.face." + member.face.name().toLowerCase(java.util.Locale.ROOT)).getString();
        String coords = member.pos.getX() + "," + member.pos.getY() + "," + member.pos.getZ() + "·" + face;
        return sameDim ? coords : member.dim.getPath() + "|" + coords;
    }

    private String trimWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        int cut = value.length();
        while (cut > 1 && font.width(value.substring(0, cut - 1) + "…") > maxWidth) cut--;
        return value.substring(0, Math.max(1, cut - 1)) + "…";
    }

    private Component reasonLabel(String reason) {
        // Fixed key set; unknown reasons fall back to the generic paused label instead of
        // leaking a server-provided string into a translation key.
        return switch (reason) {
            case "real_source" -> Component.translatable("p2pcreate.gui.reason.real_source");
            case "speed_conflict" -> Component.translatable("p2pcreate.gui.reason.speed_conflict");
            case "shared" -> Component.translatable("p2pcreate.gui.reason.shared");
            default -> Component.translatable("p2pcreate.gui.reason.paused");
        };
    }

    @Nullable
    private static Member memberAt(@Nullable Channel channel, int index) {
        if (channel == null) return null;
        return index >= 0 && index < channel.members.size() ? channel.members.get(index) : null;
    }

    private static List<Member> members(Channel channel, Role role) {
        List<Member> result = new ArrayList<>();
        for (Member member : channel.members) if (member.role == role) result.add(member);
        return result;
    }
}
