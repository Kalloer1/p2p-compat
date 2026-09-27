package com.kalloer1.p2pcreate.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.kalloer1.p2p.api.ChannelScreenExtension;
import com.kalloer1.p2p.api.DetailContext;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2p.Lang;
import com.kalloer1.p2p.client.screen.ChannelScreen;
import com.kalloer1.p2p.client.screen.NameInputScreen;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create 应力频道的详情面板扩展（本体 1.1.0 P3 缝，取代旧 ChannelScreenMixin）。
 * replacesDefaultPanel=true：右侧详情区整体由本类接管，通用速率/节流控件与默认端点表由本体隐藏。
 * 纯客户端类：只在 CompatCreate.registerClient()（宿主 create 在场时）里实例化。
 */
public final class StressPanelExtension implements ChannelScreenExtension {
    private static final int BODY_TOP_OFF = 84;
    private static final int PANEL_BOTTOM_OFF = 182;

    private int memberScroll;
    private final List<ActionHit> actionHits = new ArrayList<>();
    // Core renders the selected channel every frame before any click can arrive; input
    // callbacks lack a channel parameter, so the last drawn one is captured here.
    @Nullable
    private Channel rendered;

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
        int rx = ctx.rightAreaX;
        int rw = ctx.rightAreaWidth;
        int bodyTop = ctx.topPos + BODY_TOP_OFF;
        int bodyBottom = ctx.topPos + PANEL_BOTTOM_OFF;
        if (mouseX < rx || mouseX >= rx + rw || mouseY < bodyTop || mouseY >= bodyBottom) return false;
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
                        PacketDistributor.sendToServer(new P2PCreateNetwork.SetOutputConfigPayload(
                                channel.id, member.key(), -1, P2PCreateNetwork.DIR_TOGGLE));
                    }
                    case PRIORITY -> {
                        if (channel.distribution == Channel.Distribution.PRIORITY
                                && ctx.minecraft.screen instanceof ChannelScreen screen) ctx.minecraft.setScreen(
                                new NameInputScreen(screen, Lang.t(Lang.UI_SET_PRIORITY),
                                        String.valueOf(member.priority), 2, channel.id, member.key()));
                    }
                    case DELETE -> ctx.sendMemberAction(channel.id,
                            com.kalloer1.p2p.network.ChannelActionC2SPacket.Action.REMOVE_MEMBER,
                            0, member.key());
                    case ROLE -> ctx.sendMemberAction(channel.id,
                            com.kalloer1.p2p.network.ChannelActionC2SPacket.Action.SET_ROLE,
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
        int rx = ctx.rightAreaX;
        int rw = ctx.rightAreaWidth;
        if (mouseX < rx || mouseX >= rx + rw || mouseY < ctx.topPos + BODY_TOP_OFF
                || mouseY >= ctx.topPos + PANEL_BOTTOM_OFF) return false;

        int total = sectionHeight(channel);
        int viewport = 98;
        int max = Math.max(0, total - viewport);
        memberScroll = Math.max(0, Math.min(max, memberScroll - (int) Math.signum(scrollY) * 18));
        return true;
    }

    private void drawStressPanel(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        int rx = ctx.rightAreaX;
        int rw = ctx.rightAreaWidth;
        int panelBottom = ctx.topPos + PANEL_BOTTOM_OFF;
        Font font = ctx.font;

        // Cover the region where P2P would have drawn generic rate/throttle labels and the endpoint table.
        graphics.fill(rx, ctx.topPos + 48, rx + rw, panelBottom, 0xFF252525);
        inset(graphics, rx, ctx.topPos + 48, rw, panelBottom - (ctx.topPos + 48));

        List<Member> inputs = members(channel, Role.INPUT);
        List<Member> outputs = members(channel, Role.OUTPUT);
        float inputSupply = 0;
        boolean missingInput = false;
        for (Member member : inputs) {
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

        graphics.drawString(font, Component.translatable("p2pcreate.gui.stress_status"),
                rx + 5, ctx.topPos + 52, 0xFFE7C27D, false);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.input_available",
                        formatStress(inputSupply, missingInput)),
                rx + 5, ctx.topPos + 64, 0xFFE0E0E0, false);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.output_demand",
                        formatStress(outputDemand, missingOutput)),
                rx + 116, ctx.topPos + 64, 0xFFE0E0E0, false);
        String statusKey;
        int statusColor = 0xFFFFC66D;
        if (inputs.isEmpty() || outputs.isEmpty()) statusKey = "p2pcreate.gui.status.missing_io";
        else if (missingInput || missingOutput) statusKey = "p2pcreate.gui.status.unloaded";
        else if (anyPausedOutput) statusKey = "p2pcreate.gui.status.paused";
        else { statusKey = "p2pcreate.gui.status.connected"; statusColor = 0xFF8FD18F; }
        graphics.drawString(font, Component.translatable("p2pcreate.gui.status",
                Component.translatable(statusKey)), rx + 5, ctx.topPos + 76, statusColor, false);

        int listTop = ctx.topPos + BODY_TOP_OFF;
        int listBottom = panelBottom - 1;
        graphics.fill(rx + 1, listTop, rx + rw - 1, listBottom, 0xFF202020);
        actionHits.clear();
        memberScroll = Math.max(0, Math.min(memberScroll, Math.max(0, sectionHeight(channel) - 98)));

        graphics.enableScissor(rx + 1, listTop + 1, rx + rw - 1, listBottom);
        int y = listTop - memberScroll;
        for (int index = 0; index < channel.members.size(); index++) {
            Member member = channel.members.get(index);
            if (member.role != Role.INPUT) continue;
            drawMemberRow(ctx, graphics, channel, member, index, y, false);
            y += 18;
        }
        for (int index = 0; index < channel.members.size(); index++) {
            Member member = channel.members.get(index);
            if (member.role != Role.OUTPUT) continue;
            drawMemberRow(ctx, graphics, channel, member, index, y, true);
            y += 18;
        }
        if (inputs.isEmpty() && outputs.isEmpty()) {
            graphics.drawString(font, Component.translatable("p2pcreate.gui.no_endpoints"),
                    rx + 5, listTop + 25, 0xFF8B8B8B, false);
        }
        graphics.disableScissor();
    }

    private void drawMemberRow(DetailContext ctx, GuiGraphics graphics, Channel channel, Member member,
            int memberIndex, int y, boolean output) {
        int rx = ctx.rightAreaX;
        int rw = ctx.rightAreaWidth;
        if (y + 18 <= ctx.topPos + BODY_TOP_OFF || y >= ctx.topPos + 181) return;
        graphics.fill(rx + 2, y, rx + rw - 2, y + 17, 0xFF2B2B2B);

        ItemStack icon = endpointIcon(ctx, member);
        if (!icon.isEmpty()) graphics.renderItem(icon, rx + 2, y + 1);
        button(ctx, graphics, rx + 20, y + 2, 22, 14,
                Component.literal(output ? "OUT" : "IN"), output ? 0xFF3A7AD9 : 0xFFD93A3A);
        Font font = ctx.font;
        String key = trimWidth(font, shortKey(ctx, member), output ? 48 : 80);
        graphics.drawString(font, Component.literal(key), rx + 44, y + 5, 0xFFE0E0E0, false);

        // Same predicate the server bridge uses to resolve endpoints: a row that fails it can
        // never transfer, so surface why (bound to a non-kinetic block, unloaded, …) on the row.
        Component warning = endpointWarning(ctx, member, output);

        if (!output) {
            if (warning != null) {
                graphics.drawString(font, Component.literal("!" + warning.getString()),
                        rx + 126, y + 5, 0xFFFF6D6D, false);
            } else {
                float supplied = inputSupply(ctx, member);
                String value = supplied < 0 ? "—" : formatNumber(supplied) + " SU";
                graphics.drawString(font, Component.translatable("p2pcreate.gui.supply", value),
                        rx + 126, y + 5, 0xFFE7C27D, false);
            }
            button(ctx, graphics, rx + rw - 20, y + 2, 16, 14, Component.literal("×"), 0xFF6C3030);
            actionHits.add(new ActionHit(memberIndex, ActionKind.ROLE, rx + 20, y + 2, 22, 14));
            actionHits.add(new ActionHit(memberIndex, ActionKind.DELETE, rx + rw - 20, y + 2, 16, 14));
        } else {
            var config = KineticBridgeState.getClientOutputConfig(channel.id, member,
                    KineticBridgeState.maxRotationSpeed());
            boolean marked = warning != null || config.paused();
            graphics.drawString(font, Component.literal(Math.round(config.rpm()) + " RPM"),
                    rx + 94, y + 5, marked ? 0xFF8B8B8B : 0xFFE7C27D, false);
            Component dirLabel = warning != null ? warning : config.paused()
                    ? reasonLabel(config.reason())
                    : Component.translatable(config.reverse()
                            ? "p2pcreate.gui.counter_clockwise" : "p2pcreate.gui.clockwise");
            // The direction cell doubles as the toggle control and carries the row's warning /
            // pause reason when the output cannot run.
            button(ctx, graphics, rx + 128, y + 2, 34, 14,
                    Component.literal((marked ? "!" : "") + trimWidth(font, dirLabel.getString(), marked ? 26 : 30)),
                    marked ? 0xFF6C3030 : 0xFF3D3530);
            if (channel.distribution == Channel.Distribution.PRIORITY) {
                // Keep the per-output priority editor compact so RPM, direction, and delete
                // remain available in the same row.
                button(ctx, graphics, rx + 164, y + 2, 16, 14,
                        Component.literal("P" + member.priority), 0xFF3D3530);
                actionHits.add(new ActionHit(memberIndex, ActionKind.PRIORITY, rx + 164, y + 2, 16, 14));
            }
            button(ctx, graphics, rx + 182, y + 2, 26, 14,
                    Component.translatable("p2pcreate.gui.rpm"), 0xFF3D3530);
            button(ctx, graphics, rx + rw - 15, y + 2, 13, 14, Component.literal("×"), 0xFF6C3030);
            actionHits.add(new ActionHit(memberIndex, ActionKind.ROLE, rx + 20, y + 2, 22, 14));
            actionHits.add(new ActionHit(memberIndex, ActionKind.DIRECTION, rx + 128, y + 2, 34, 14));
            actionHits.add(new ActionHit(memberIndex, ActionKind.RPM, rx + 182, y + 2, 26, 14));
            actionHits.add(new ActionHit(memberIndex, ActionKind.DELETE, rx + rw - 15, y + 2, 13, 14));
        }
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
                ItemStack pick = block.getCloneItemStack(state,
                        new BlockHitResult(Vec3.atCenterOf(target), member.face, target, false),
                        level, target, ctx.minecraft.player);
                if (pick.isEmpty()) pick = new ItemStack(block.asItem());
                BlockEntity be = level.getBlockEntity(target);
                if (be != null) {
                    CompoundTag beTag = be.saveWithFullMetadata(level.registryAccess());
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

    private int sectionHeight(Channel channel) {
        int rows = 0;
        for (Member member : channel.members) {
            if (member.role == Role.INPUT || member.role == Role.OUTPUT) rows++;
        }
        return rows * 18;
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

    private String formatStress(float value, boolean missing) {
        return missing ? "—" : formatNumber(value) + " SU";
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

    private void button(DetailContext ctx, GuiGraphics graphics, int x, int y, int width, int height,
            Component label, int color) {
        String text = label.getString();
        Font font = ctx.font;
        graphics.fill(x, y, x + width, y + height, color);
        graphics.fill(x, y, x + width, y + 1, 0xFF6E625A);
        graphics.drawString(font, label, x + Math.max(2, (width - font.width(text)) / 2),
                y + 3, 0xFFE7E0D9, false);
    }

    private void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + 1, 0xFF6E625A);
        graphics.fill(x, y, x + 1, y + height, 0xFF6E625A);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF171717);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF171717);
    }
}
