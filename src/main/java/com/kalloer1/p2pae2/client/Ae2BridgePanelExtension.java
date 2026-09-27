package com.kalloer1.p2pae2.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kalloer1.p2p.api.ChannelScreenExtension;
import com.kalloer1.p2p.api.DetailContext;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2p.client.screen.P2PTheme;
import com.kalloer1.p2p.network.ChannelActionC2SPacket;
import com.kalloer1.p2pae2.network.P2PAe2Network;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * ae2:bridge 频道详情面板（本体 1.1.0 P3 缝）。replacesDefaultPanel=true：
 * 隐藏通用速率/Tick/节流控件，右侧改画量子桥状态与端点 IN/OUT 概览。
 *
 * <p>BridgeManager 是服务端权威；1.2.0 起频道用量经新增的 {@code p2pae2:sync_bridge_channels}
 * 每秒差量推送（P2PAe2Network），状态卡据此显示 ME 网络频道数，其余状态仍按同一选取规则
 * （第一输入 + 第一输出，见 BridgeManager.syncChannel）从 Channel 成员推导，不直连服务端数据。
 * 纯客户端类：只在 CompatAE2.registerClient()（宿主 ae2 在场时）注册。
 *
 * <p>1.2.0 iOS 几何契约：独占 {@link DetailContext} 的 244x160 接管帧，按
 * 「状态卡 + 端点列表卡」两段式重绘；绘制全部走本体公开主题层 {@link P2PTheme}
 * （旧 0xFF252525 系暗色与私有 bevel 已删除），面名使用自有键 {@code p2p_compat.ae2.face.*}。
 */
public final class Ae2BridgePanelExtension implements ChannelScreenExtension {
    /**
     * 状态卡高（标题 / 端点计数 / 频道用量 / 分隔线 / 状态 pill）。
     * 1.2.0 从 56 加高到 64 容纳新增的频道行，列表卡相应下移压缩。
     */
    private static final int STATUS_H = 64;
    /** 两段卡片间距。 */
    private static final int GAP = 4;
    /** 列表卡内的分区头高。 */
    private static final int HEAD_H = 14;
    /** 列表卡内的脚注高（承载“仅第一对参与桥接”提示）。 */
    private static final int FOOT_H = 12;
    private static final int ROW_H = DetailContext.ROW_H;

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
        if (!inFrame(ctx, mouseX, mouseY)) return false;
        // Every control inside the replaced panel is consumed: the generic role/face/delete
        // handlers underneath must never react, including right- and middle-clicks.
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
        if (!inFrame(ctx, mouseX, mouseY)) return false;
        memberScroll = clampScroll(memberScroll - (int) Math.signum(scrollY) * ROW_H, channel);
        return true;
    }

    private boolean inFrame(DetailContext ctx, double mouseX, double mouseY) {
        return mouseX >= ctx.frameX() && mouseX < ctx.frameX() + ctx.frameWidth()
                && mouseY >= ctx.frameTop() && mouseY < ctx.frameBottom();
    }

    private void drawPanel(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        hits.clear();
        drawStatusCard(ctx, graphics, channel);
        drawEndpointsCard(ctx, graphics, channel);
    }

    private void drawStatusCard(DetailContext ctx, GuiGraphics graphics, Channel channel) {
        int fx = ctx.frameX();
        int fw = ctx.frameWidth();
        int fy = ctx.frameTop();
        Font font = ctx.font;

        int inputs = 0, outputs = 0;
        for (Member m : channel.members) {
            if (m.role == Role.INPUT) inputs++;
            else if (m.role == Role.OUTPUT) outputs++;
        }

        P2PTheme.card(graphics, fx, fy, fw, STATUS_H);
        P2PTheme.drawStringBold(graphics, font, Component.translatable("p2pae2.gui.bridge_status"),
                fx + P2PTheme.PAD, fy + 6, P2PTheme.TEXT_PRIMARY);

        graphics.drawString(font, Component.translatable("p2pae2.gui.bridge.endpoints",
                        inputs + " In / " + outputs + " Out"),
                fx + P2PTheme.PAD, fy + 19, P2PTheme.TEXT_PRIMARY, false);

        // 频道用量（服务端随 p2pae2:sync_bridge_channels 每秒推送；未同步过则省略该行）。
        P2PAe2Network.ChannelStat stat = P2PAe2Network.clientStat(channel.id);
        if (stat != null) {
            Component channels = stat.maxChannels() < 0
                    ? Component.translatable("p2pae2.gui.bridge.channels.infinite", stat.usedChannels())
                    : Component.translatable("p2pae2.gui.bridge.channels",
                            stat.usedChannels(), stat.maxChannels(),
                            Math.max(0, stat.maxChannels() - stat.usedChannels()));
            graphics.drawString(font, channels, fx + P2PTheme.PAD, fy + 30, P2PTheme.TEXT_SECOND, false);
        }

        P2PTheme.separator(graphics, fx, fy + 40, fw);

        boolean missingIo = inputs == 0 || outputs == 0;
        Component status = Component.translatable(missingIo
                ? "p2pae2.gui.bridge.status.missing_io" : "p2pae2.gui.bridge.status.connected");
        int chipColor = missingIo ? P2PTheme.WARNING : P2PTheme.SUCCESS;
        int chipW = Math.min(font.width(status) + 10, fw - 2 * P2PTheme.PAD);
        P2PTheme.chip(graphics, fx + (fw - chipW) / 2, fy + 46, chipW, P2PTheme.PILL_H, chipColor);
        P2PTheme.drawCentered(graphics, font,
                Component.literal(trimWidth(font, status.getString(), chipW - 8)),
                fx + (fw - chipW) / 2, fy + 48, chipW, 0xFFFFFFFF);
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
                Component.translatable("p2pae2.gui.bridge.endpoints", channel.members.size()),
                fx + P2PTheme.PAD, listTop + 3, P2PTheme.TEXT_PRIMARY);

        memberScroll = clampScroll(memberScroll, channel);
        graphics.enableScissor(fx + 1, rowsTop, fx + fw - 1, rowsBottom);
        int y = rowsTop - memberScroll;
        for (int index = 0; index < channel.members.size(); index++) {
            drawRow(ctx, graphics, channel.members.get(index), index, y, rowsTop, rowsBottom);
            y += ROW_H;
        }
        if (channel.members.isEmpty()) {
            graphics.drawString(font, Component.translatable("p2pae2.gui.bridge.no_endpoints"),
                    fx + P2PTheme.PAD, rowsTop + 6, P2PTheme.TEXT_SECOND, false);
        }
        graphics.disableScissor();

        Component foot = Component.translatable("p2pae2.gui.bridge.partial");
        graphics.drawString(font, foot, fx + fw - P2PTheme.PAD - font.width(foot), rowsBottom + 2,
                P2PTheme.TEXT_TERT, false);
    }

    private void drawRow(DetailContext ctx, GuiGraphics graphics, Member member, int index,
            int y, int rowsTop, int rowsBottom) {
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

        boolean output = member.role == Role.OUTPUT;
        int badgeX = x0 + 18;
        P2PTheme.roleBadge(graphics, badgeX, y + 3, !output);
        P2PTheme.drawCentered(graphics, font, Component.literal(output ? "OUT" : "IN"),
                badgeX, y + 5, P2PTheme.BADGE_W, 0xFFFFFFFF);
        hits.add(new RowHit(index, 0, badgeX, y + 2, P2PTheme.BADGE_W, ROW_H - 4));

        String coords = trimWidth(font, shortKey(ctx, member), right - (x0 + 44) - P2PTheme.CIRCLE - 4);
        graphics.drawString(font, Component.literal(coords), x0 + 44, y + 5, P2PTheme.TEXT_PRIMARY, false);

        P2PTheme.deleteCross(graphics, right - 14, y + 2, false);
        hits.add(new RowHit(index, 1, right - 14, y + 2, P2PTheme.CIRCLE, P2PTheme.CIRCLE));
    }

    private int clampScroll(int scroll, Channel channel) {
        int viewport = DetailContext.FRAME_H - STATUS_H - GAP - HEAD_H - FOOT_H;
        return Math.max(0, Math.min(Math.max(0, channel.members.size() * ROW_H - viewport), scroll));
    }

    private ItemStack endpointIcon(DetailContext ctx, Member member) {
        Level level = ctx.minecraft.level;
        if (level != null && level.dimension().location().equals(member.dim)
                && level.isLoaded(member.pos)) {
            Block block = level.getBlockState(member.pos).getBlock();
            if (block != Blocks.AIR && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
        }
        if (member.blockId != null) {
            // BuiltInRegistries.BLOCK is defaulted: unknown ids return AIR rather than null.
            Block block = BuiltInRegistries.BLOCK.get(member.blockId);
            if (block != Blocks.AIR && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
            // AE2 的 part/cable 端点绑定在共享的 cable_bus 方块上（无物品形态，槽井会空）：
            // 先试与 blockId 同 id 的物品（AE2 的控制器/量子桥等物品 id 与方块 id 一致），
            // 再退到 ME 控制器物品兜底，保证非空端点行不出现空井。
            Item byId = BuiltInRegistries.ITEM.get(member.blockId);
            if (byId != Items.AIR) return new ItemStack(byId);
            if ("ae2".equals(member.blockId.getNamespace())) {
                Item fallback = BuiltInRegistries.ITEM.get(
                        ResourceLocation.fromNamespaceAndPath("ae2", "controller"));
                if (fallback != Items.AIR) return new ItemStack(fallback);
            }
        }
        return ItemStack.EMPTY;
    }

    private String shortKey(DetailContext ctx, Member member) {
        Level level = ctx.minecraft.level;
        boolean sameDim = level != null && level.dimension().location().equals(member.dim);
        String face = Component.translatable(
                "p2p_compat.ae2.face." + member.face.name().toLowerCase(java.util.Locale.ROOT)).getString();
        String coords = member.pos.getX() + "," + member.pos.getY() + "," + member.pos.getZ() + "·" + face;
        return sameDim ? coords : member.dim.getPath() + "|" + coords;
    }

    private String trimWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        int cut = value.length();
        while (cut > 1 && font.width(value.substring(0, cut - 1) + "…") > maxWidth) cut--;
        return value.substring(0, Math.max(1, cut - 1)) + "…";
    }
}
