package com.kalloer1.p2pae2.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kalloer1.p2p.api.ChannelScreenExtension;
import com.kalloer1.p2p.api.DetailContext;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2p.network.ChannelActionC2SPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * ae2:bridge 频道详情面板（本体 1.1.0 P3 缝）。replacesDefaultPanel=true：
 * 隐藏通用速率/Tick/节流控件，右侧改画量子桥状态与端点 IN/OUT 概览。
 *
 * <p>BridgeManager 是服务端权威、无同步包，客户端面板按同一选取规则（第一输入 +
 * 第一输出，见 BridgeManager.syncChannel）从 Channel 成员推导状态，不直连服务端数据。
 * 纯客户端类：只在 CompatAE2.registerClient()（宿主 ae2 在场时）注册。
 */
public final class Ae2BridgePanelExtension implements ChannelScreenExtension {
    private static final int BODY_TOP_OFF = 84;
    private static final int PANEL_BOTTOM_OFF = 182;

    private int memberScroll;
    private final List<RowHit> hits = new ArrayList<>();
    @Nullable
    private Channel rendered;

    private record RowHit(int memberIndex, int kind, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    @Override
    public boolean replacesDefaultPanel(@Nullable Channel channel) {
        return true;
    }

    @Override
    public void drawDetails(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        rendered = channel;
        drawPanel(ctx, graphics, channel);
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
        if (button != 0) return true;
        for (RowHit hit : hits) {
            if (!hit.contains(mouseX, mouseY)) continue;
            if (hit.memberIndex() >= channel.members.size()) return true;
            Member member = channel.members.get(hit.memberIndex());
            if (hit.kind() == 0) {
                ctx.sendMemberAction(channel.id, ChannelActionC2SPacket.Action.SET_ROLE,
                        member.role == Role.INPUT ? Role.OUTPUT.ordinal() : Role.INPUT.ordinal(),
                        member.key());
            } else {
                ctx.sendMemberAction(channel.id, ChannelActionC2SPacket.Action.REMOVE_MEMBER, 0, member.key());
            }
            return true;
        }
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
        int max = Math.max(0, channel.members.size() * 18 - 98);
        memberScroll = Math.max(0, Math.min(max, memberScroll - (int) Math.signum(scrollY) * 18));
        return true;
    }

    private void drawPanel(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        int rx = ctx.rightAreaX;
        int rw = ctx.rightAreaWidth;
        int panelBottom = ctx.topPos + PANEL_BOTTOM_OFF;
        Font font = ctx.font;

        graphics.fill(rx, ctx.topPos + 48, rx + rw, panelBottom, 0xFF252525);
        inset(graphics, rx, ctx.topPos + 48, rw, panelBottom - (ctx.topPos + 48));

        int inputs = 0, outputs = 0;
        for (Member m : channel.members) {
            if (m.role == Role.INPUT) inputs++;
            else if (m.role == Role.OUTPUT) outputs++;
        }
        graphics.drawString(font, Component.translatable("p2pae2.gui.bridge_status"),
                rx + 5, ctx.topPos + 52, 0xFFE7C27D, false);
        Component status;
        int statusColor;
        if (inputs == 0 || outputs == 0) {
            status = Component.translatable("p2pae2.gui.bridge.status.missing_io");
            statusColor = 0xFFFFC66D;
        } else {
            status = Component.translatable("p2pae2.gui.bridge.status.connected");
            statusColor = 0xFF8FD18F;
        }
        graphics.drawString(font, Component.translatable("p2pae2.gui.bridge.status", status),
                rx + 5, ctx.topPos + 64, statusColor, false);
        String counts = inputs + " In / " + outputs + " Out";
        graphics.drawString(font, Component.translatable("p2pae2.gui.bridge.endpoints", counts),
                rx + 5, ctx.topPos + 76, 0xFFBFBFBF, false);
        if (inputs > 1 || outputs > 1) {
            graphics.drawString(font, Component.translatable("p2pae2.gui.bridge.partial"),
                    rx + rw - font.width(Component.translatable("p2pae2.gui.bridge.partial")) - 5,
                    ctx.topPos + 76, 0xFF8B8B8B, false);
        }

        int listTop = ctx.topPos + BODY_TOP_OFF;
        int listBottom = panelBottom - 1;
        graphics.fill(rx + 1, listTop, rx + rw - 1, listBottom, 0xFF202020);
        hits.clear();
        memberScroll = Math.max(0, Math.min(memberScroll, Math.max(0, channel.members.size() * 18 - 98)));

        graphics.enableScissor(rx + 1, listTop + 1, rx + rw - 1, listBottom);
        int y = listTop - memberScroll;
        for (int index = 0; index < channel.members.size(); index++) {
            drawRow(ctx, graphics, channel.members.get(index), index, y);
            y += 18;
        }
        if (channel.members.isEmpty()) {
            graphics.drawString(font, Component.translatable("p2pae2.gui.bridge.no_endpoints"),
                    rx + 5, listTop + 25, 0xFF8B8B8B, false);
        }
        graphics.disableScissor();
    }

    private void drawRow(DetailContext ctx, GuiGraphics graphics, Member member, int index, int y) {
        int rx = ctx.rightAreaX;
        int rw = ctx.rightAreaWidth;
        if (y + 18 <= ctx.topPos + BODY_TOP_OFF || y >= ctx.topPos + 181) return;
        graphics.fill(rx + 2, y, rx + rw - 2, y + 17, 0xFF2B2B2B);

        ItemStack icon = endpointIcon(ctx, member);
        if (!icon.isEmpty()) graphics.renderItem(icon, rx + 2, y + 1);
        boolean output = member.role == Role.OUTPUT;
        button(ctx, graphics, rx + 20, y + 2, 22, 14,
                Component.literal(output ? "OUT" : "IN"), output ? 0xFF3A7AD9 : 0xFFD93A3A);
        Font font = ctx.font;
        String coords = trimWidth(font, shortKey(ctx, member), 84);
        graphics.drawString(font, Component.literal(coords), rx + 44, y + 5, 0xFFE0E0E0, false);
        button(ctx, graphics, rx + rw - 20, y + 2, 16, 14, Component.literal("×"), 0xFF6C3030);
        hits.add(new RowHit(index, 0, rx + 20, y + 2, 22, 14));
        hits.add(new RowHit(index, 1, rx + rw - 20, y + 2, 16, 14));
    }

    private ItemStack endpointIcon(DetailContext ctx, Member member) {
        Level level = ctx.minecraft.level;
        if (level != null && level.dimension().location().equals(member.dim)
                && level.isLoaded(member.pos)) {
            Block block = level.getBlockState(member.pos).getBlock();
            if (block != Blocks.AIR && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
        }
        if (member.blockId != null) {
            Block block = BuiltInRegistries.BLOCK.get(member.blockId);
            if (block != Blocks.AIR && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
        }
        return ItemStack.EMPTY;
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

    private void button(DetailContext ctx, GuiGraphics graphics, int x, int y, int width, int height,
            Component label, int color) {
        Font font = ctx.font;
        graphics.fill(x, y, x + width, y + height, color);
        graphics.fill(x, y, x + width, y + 1, 0xFF6E625A);
        graphics.drawString(font, label, x + Math.max(2, (width - font.width(label)) / 2),
                y + 3, 0xFFE7E0D9, false);
    }

    private void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + 1, 0xFF6E625A);
        graphics.fill(x, y, x + 1, y + height, 0xFF6E625A);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF171717);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF171717);
    }
}
