package com.kalloer1.p2pmekanism.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kalloer1.p2p.api.FilterPageContext;
import com.kalloer1.p2p.api.FilterPageExtension;
import com.kalloer1.p2p.channel.Filter;
import com.kalloer1.p2pmekanism.CompatMekanism;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
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

/**
 * 气体/化学品过滤页（本体 1.1.0 P4 缝，取代旧 FilterEditorMixin + FilterMixin）。
 *
 * <p>数据侧不再给 {@code Filter} 挂运行时接口，而是把化学品模板写进
 * {@code Filter.addonData["mekanism:gas"]}（namespaced NBT，随既有 SET_FILTER 透传）。
 * 纯客户端单例：只在 CompatMekanism.registerClient()（宿主 mekanism 在场时）注册；
 * {@link MekanismJeiPlugin} 借本类的当前编辑器状态提供 JEI 幽灵拖入。
 */
public final class GasFilterPage implements FilterPageExtension {
    public static final GasFilterPage INSTANCE = new GasFilterPage();
    /** addonData 键与介质 id 同命名空间（core-api-changes §4.4；旧数据不存在，无兼容负担）。 */
    public static final String ADDON_KEY = CompatMekanism.GAS_ID;

    private final List<ResourceLocation> chemicalIds = new ArrayList<>();
    private final List<ChemicalStack> chemicalStacks = new ArrayList<>();
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
        Rect2i area = ctx.markerGridArea();
        // 本体 init() 每次 resize 重进并 clearControls 重建，扩展控件随之重建（幂等约定）。
        Button modeButton = Button.builder(Component.translatable(modeLabel()), button -> {
            mode = mode == Filter.Mode.WHITELIST ? Filter.Mode.BLACKLIST : Filter.Mode.WHITELIST;
            button.setMessage(Component.translatable(modeLabel()));
        }).bounds(area.getX(), area.getY() + 102, 158, 16).build();
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
        ctx.commitFilter(filter.serializeNBT(ctx.minecraft().level.registryAccess()));
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
        mekanism.common.attachments.containers.chemical.AttachedChemicals attached =
                slot.getItem().get(mekanism.common.registries.MekanismDataComponents.ATTACHED_CHEMICALS.get());
        if (attached == null) return false;
        boolean added = false;
        for (ChemicalStack stored : attached.containers()) {
            if (stored == null || stored.isEmpty()) continue;
            int before = chemicalIds.size();
            addChemical(stored);
            if (chemicalIds.size() > before) added = true;
        }
        return added;
    }

    @Override
    public void renderOverlay(FilterPageContext ctx, GuiGraphics graphics, int mouseX, int mouseY) {
        Rect2i area = ctx.markerGridArea();
        int count = Math.min(chemicalStacks.size(), 27);
        for (int i = 0; i < count; i++) {
            int x = area.getX() + (i % 9) * 18 + 1;
            int y = area.getY() + (i / 9) * 18 + 1;
            ChemicalStack stack = chemicalStacks.get(i);
            ResourceLocation id = stack.getTypeRegistryName();
            int color = 0xFF404040 | (id == null ? 0 : Math.abs(id.hashCode()) % 0x808080);
            graphics.fill(x, y, x + 16, y + 16, color);
            graphics.fill(x, y, x + 16, y + 1, 0x40FFFFFF);
            graphics.fill(x, y + 15, x + 16, y + 16, 0x40000000);
        }
        if (mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight()) {
            int index = ((mouseY - area.getY()) / 18) * 9 + ((mouseX - area.getX()) / 18);
            if (index >= 0 && index < chemicalStacks.size()) {
                graphics.renderTooltip(Minecraft.getInstance().font,
                        chemicalStacks.get(index).getTextComponent(), mouseX, mouseY);
            }
        }
    }

    // ============================================================ JEI support

    /** True when the gas page is the active editor screen (item sub-page); used by the JEI ghost handler. */
    public boolean isActiveOn(Object screen) {
        FilterPageContext c = ctx;
        return c != null && c.minecraft().screen == screen && c.isItemPage();
    }

    public Rect2i markerArea() {
        FilterPageContext c = ctx;
        return c == null ? new Rect2i(0, 0, 0, 0) : c.markerGridArea();
    }

    public void addChemical(ChemicalStack stack) {
        FilterPageContext c = ctx;
        if (c == null || stack == null || stack.isEmpty()) return;
        ResourceLocation id = stack.getTypeRegistryName();
        if (id == null || chemicalIds.size() >= 27 || chemicalIds.contains(id)) return;
        chemicalIds.add(id);
        chemicalStacks.add(stack.copyWithAmount(1000));
        c.syncGhost();
    }

    private void addId(ResourceLocation id) {
        if (chemicalIds.size() >= 27 || chemicalIds.contains(id)) return;
        Chemical chemical = MekanismAPI.CHEMICAL_REGISTRY.get(id);
        if (chemical == null) return;
        chemicalIds.add(id);
        chemicalStacks.add(new ChemicalStack(chemical, 1000));
    }

    private String modeLabel() {
        return mode == Filter.Mode.WHITELIST ? "p2p.mekanism.filter.whitelist" : "p2p.mekanism.filter.blacklist";
    }
}
