package com.kalloer1.p2pmekanism.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kalloer1.p2p.api.FilterPageContext;
import com.kalloer1.p2p.api.FilterPageExtension;
import com.kalloer1.p2p.channel.Filter;
import com.kalloer1.p2p.client.screen.P2PFlatButton;
import com.kalloer1.p2p.client.screen.P2PTheme;
import com.kalloer1.p2pmekanism.CompatMekanism;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 气体/化学品过滤页（本体 1.4.0 扩展缝，移植自 1.21.1 整合版同款页面）。
 *
 * <p>数据侧不给 {@code Filter} 挂运行时接口，而是把化学品模板写进
 * {@code Filter.addonData["mekanism:gas"]}（namespaced NBT，随既有 SET_FILTER 透传）。
 * 纯客户端单例：只在 CompatMekanism.registerClient()（宿主 mekanism 在场时）注册；
 * {@link MekanismJeiPlugin} / {@link MekanismEmiPlugin} 借本类的当前编辑器状态提供幽灵拖入。
 *
 * <p>1.20.1（Mekanism 10.4）适配点：
 * <ul>
 *   <li>气体罐物品的「附带化学品」读取走 Forge item capability
 *       {@code Capabilities.GAS_HANDLER}（10.7 是 data components，10.4 没有）；</li>
 *   <li>化学品注册表用 {@code MekanismAPI.gasRegistry()}（10.7 是
 *       {@code MekanismAPI.CHEMICAL_REGISTRY}）；</li>
 *   <li>{@code GasStack} 没有 {@code copyWithAmount}，用 {@code new GasStack(type, n)}。</li>
 * </ul>
 */
public final class GasFilterPage implements FilterPageExtension {
    public static final GasFilterPage INSTANCE = new GasFilterPage();
    /** addonData 键与介质 id 同命名空间；旧数据不存在，无兼容负担。 */
    public static final String ADDON_KEY = CompatMekanism.GAS_ID;

    private final List<ResourceLocation> chemicalIds = new ArrayList<>();
    private final List<GasStack> chemicalStacks = new ArrayList<>();
    private Filter.Mode mode = Filter.Mode.WHITELIST;
    @Nullable
    private FilterPageContext ctx;

    private GasFilterPage() {}

    // ============================================================ extension hooks

    @Override
    public void onOpen(FilterPageContext ctx, @Nullable Filter existing) {
        this.ctx = ctx;
        chemicalIds.clear();
        chemicalStacks.clear();
        mode = Filter.Mode.WHITELIST;
        if (existing == null) return;
        CompoundTag gas = existing.addonData.get(ADDON_KEY);
        if (gas == null) return;
        if ("BLACKLIST".equals(gas.getString("mode"))) mode = Filter.Mode.BLACKLIST;
        for (Tag entry : gas.getList("chemicals", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null) addId(id);
        }
    }

    @Override
    public void onInit(FilterPageContext ctx) {
        // 规格 §4：模式钮与本体「模式」行同行同规格（P2PFlatButton 默认 pill 样式），
        // 几何由本体给出，不再写死 markerGridArea 相对偏移。resize 会重建控件列表，无需自行清理。
        Rect2i row = ctx.modeRowArea();
        P2PFlatButton modeButton = new P2PFlatButton(Button.builder(
                Component.translatable(modeLabel()), button -> {
                    mode = mode == Filter.Mode.WHITELIST ? Filter.Mode.BLACKLIST : Filter.Mode.WHITELIST;
                    button.setMessage(Component.translatable(modeLabel()));
                }).bounds(row.getX(), row.getY(), row.getWidth(), row.getHeight()));
        ctx.addWidget(modeButton);
    }

    @Override
    public boolean finish(FilterPageContext ctx) {
        Filter filter = Filter.fluidFilter(mode, List.of());
        CompoundTag gas = new CompoundTag();
        ListTag chemicals = new ListTag();
        for (ResourceLocation id : chemicalIds) chemicals.add(StringTag.valueOf(id.toString()));
        gas.put("chemicals", chemicals);
        gas.putString("mode", mode.name());
        filter.addonData.put(ADDON_KEY, gas);
        ctx.commitFilter(filter.serializeNBT());
        ctx.close();
        ctx = null;
        return true;
    }

    @Override
    public boolean slotClicked(FilterPageContext ctx, Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (slot == null) return false;
        if (slot.container == ctx.ghostContainer()) {
            if (!ctx.isItemPage()) return false;
            int index = slot.getSlotIndex();
            if (index < chemicalIds.size()) {
                chemicalIds.remove(index);
                chemicalStacks.remove(index);
                ctx.syncGhost();
            }
            return true;
        }
        if (!ctx.isItemPage()) return false;
        return addFromItemStack(slot.getItem());
    }

    @Override
    public void renderOverlay(FilterPageContext ctx, GuiGraphics graphics, int mouseX, int mouseY) {
        Rect2i area = ctx.markerGridArea();
        int pitch = ctx.markerPitch();
        int count = Math.min(chemicalStacks.size(), 27);
        for (int i = 0; i < count; i++) {
            int x = area.getX() + (i % 9) * pitch + 1;
            int y = area.getY() + (i / 9) * pitch + 1;
            GasStack stack = chemicalStacks.get(i);
            ResourceLocation id = stack.getTypeRegistryName();
            int color = 0xFF404040 | (id == null ? 0 : Math.abs(id.hashCode()) % 0x808080);
            // 规格 §4：16×16 圆角 2 化学品色块（色派生规则不变）。
            P2PTheme.drawRoundRect(graphics, x, y, 16, 16, P2PTheme.R_SLOT, color);
        }
        if (mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight()) {
            int index = ((mouseY - area.getY()) / pitch) * 9 + ((mouseX - area.getX()) / pitch);
            if (index >= 0 && index < chemicalStacks.size()) {
                graphics.renderTooltip(Minecraft.getInstance().font,
                        chemicalStacks.get(index).getTextComponent(), mouseX, mouseY);
            }
        }
    }

    // ============================================================ JEI / EMI support

    /** True when the gas page is the active editor screen (item sub-page); used by the ghost handlers. */
    public boolean isActiveOn(Object screen) {
        FilterPageContext c = ctx;
        return c != null && c.minecraft().screen == screen && c.isItemPage();
    }

    public Rect2i markerArea() {
        FilterPageContext c = ctx;
        return c == null ? new Rect2i(0, 0, 0, 0) : c.markerGridArea();
    }

    public void addChemical(GasStack stack) {
        FilterPageContext c = ctx;
        if (c == null || stack == null || stack.isEmpty()) return;
        ResourceLocation id = stack.getTypeRegistryName();
        if (id == null || chemicalIds.size() >= 27 || chemicalIds.contains(id)) return;
        chemicalIds.add(id);
        chemicalStacks.add(new GasStack(stack.getType(), 1000));
        c.syncGhost();
    }

    /**
     * 从物品堆读取通用机械气体罐的附着化学品（Forge item capability
     * {@code Capabilities.GAS_HANDLER}）并逐个加入过滤。点击容器槽位、EMI/JEI 幽灵拖拽
     * 三路复用；返回是否有新介质被加入。
     */
    public boolean addFromItemStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        IGasHandler attached = stack.getCapability(Capabilities.GAS_HANDLER).orElse(null);
        if (attached == null) return false;
        boolean added = false;
        for (int tank = 0; tank < attached.getTanks(); tank++) {
            GasStack stored = attached.getChemicalInTank(tank);
            if (stored == null || stored.isEmpty()) continue;
            int before = chemicalIds.size();
            addChemical(stored);
            if (chemicalIds.size() > before) added = true;
        }
        return added;
    }

    private void addId(ResourceLocation id) {
        if (chemicalIds.size() >= 27 || chemicalIds.contains(id)) return;
        Gas chemical = MekanismAPI.gasRegistry().getValue(id);
        if (chemical == null || chemical.isEmptyType()) return;
        chemicalIds.add(id);
        chemicalStacks.add(new GasStack(chemical, 1000));
    }

    private String modeLabel() {
        return mode == Filter.Mode.WHITELIST ? "p2p.mekanism.filter.whitelist" : "p2p.mekanism.filter.blacklist";
    }
}
