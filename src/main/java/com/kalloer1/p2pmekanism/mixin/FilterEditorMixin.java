package com.kalloer1.p2pmekanism.mixin;

import com.kalloer1.p2p.channel.Filter;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.client.screen.FilterEditor;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2pmekanism.client.ChemicalFilterScreenAccess;
import com.kalloer1.p2pmekanism.filter.ChemicalFilterAccess;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Extends P2P's existing filter editor with a gas marker page without editing P2P. */
@Mixin(FilterEditor.class)
public abstract class FilterEditorMixin implements ChemicalFilterScreenAccess {
    @Shadow @Final private Medium chType;
    @Shadow @Final private int channelId;
    @Shadow @Final private Member member;
    @Shadow @Final private boolean extract;
    @Shadow private void syncGhost() {}

    @Unique
    private final List<ResourceLocation> p2pmekanism$chemicalIds = new ArrayList<>();
    @Unique
    private final List<ChemicalStack> p2pmekanism$chemicalStacks = new ArrayList<>();
    @Unique
    private Filter.Mode p2pmekanism$mode = Filter.Mode.WHITELIST;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void p2pmekanism$loadExisting(Screen parent, int channelId, Medium type, Member member, CallbackInfo ci) {
        if (!p2pmekanism$isGasFilter()) return;
        Filter existing = extract ? member.extractFilter : member.insertFilter;
        if (existing instanceof ChemicalFilterAccess access) {
            p2pmekanism$mode = existing.getMode();
            for (ResourceLocation id : access.p2pmekanism$getChemicalTemplates()) {
                p2pmekanism$addId(id);
            }
        }
    }

    @Override
    public boolean p2pmekanism$isGasFilter() {
        return chType != null && "mekanism:gas".equals(chType.id);
    }

    @Override
    public boolean p2pmekanism$isItemPage() {
        return ((FilterEditor) (Object) this).isItemPage();
    }

    @Override
    public Rect2i p2pmekanism$getMarkerArea() {
        return ((FilterEditor) (Object) this).getMarkerGridArea();
    }

    @Override
    public void p2pmekanism$addChemical(ChemicalStack stack) {
        if (!p2pmekanism$isGasFilter() || stack == null || stack.isEmpty()) return;
        ResourceLocation id = stack.getTypeRegistryName();
        if (id == null || p2pmekanism$chemicalIds.size() >= 27 || p2pmekanism$chemicalIds.contains(id)) return;
        p2pmekanism$chemicalIds.add(id);
        p2pmekanism$chemicalStacks.add(stack.copyWithAmount(1000));
        syncGhost();
    }

    @Unique
    private void p2pmekanism$addId(ResourceLocation id) {
        if (id == null || p2pmekanism$chemicalIds.size() >= 27 || p2pmekanism$chemicalIds.contains(id)) return;
        Chemical chemical = MekanismAPI.CHEMICAL_REGISTRY.get(id);
        if (chemical == null) return;
        p2pmekanism$chemicalIds.add(id);
        p2pmekanism$chemicalStacks.add(new ChemicalStack(chemical, 1000));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void p2pmekanism$addModeButton(CallbackInfo ci) {
        if (!p2pmekanism$isGasFilter()) return;
        Rect2i area = p2pmekanism$getMarkerArea();
        ((ScreenAddRenderableInvoker) (Object) this).p2pmekanism$addRenderable(Button.builder(Component.literal(p2pmekanism$modeLabel()), button -> {
            p2pmekanism$mode = p2pmekanism$mode == Filter.Mode.WHITELIST ? Filter.Mode.BLACKLIST : Filter.Mode.WHITELIST;
            button.setMessage(Component.literal(p2pmekanism$modeLabel()));
        }).bounds(area.getX(), area.getY() + 102, 158, 16).build());
    }

    @Unique
    private String p2pmekanism$modeLabel() {
        return p2pmekanism$mode == Filter.Mode.WHITELIST ? "Whitelist" : "Blacklist";
    }

    @Unique
    private Object p2pmekanism$filterMenu() {
        return ((AbstractContainerScreenMenuInvoker) (Object) this).p2pmekanism$callGetMenu();
    }

    @Inject(method = "finish", at = @At("HEAD"), cancellable = true)
    private void p2pmekanism$finishGas(CallbackInfo ci) {
        if (!p2pmekanism$isGasFilter()) return;
        Filter filter = Filter.fluidFilter(p2pmekanism$mode, List.of());
        if (filter instanceof ChemicalFilterAccess access) {
            access.p2pmekanism$setChemicalTemplates(p2pmekanism$chemicalIds);
        }
        net.minecraft.nbt.CompoundTag tag = filter.serializeNBT(Minecraft.getInstance().level.registryAccess());
        com.kalloer1.p2p.network.ModMessages.sendToServer(new com.kalloer1.p2p.network.ChannelActionC2SPacket(
                channelId, com.kalloer1.p2p.network.ChannelActionC2SPacket.Action.SET_FILTER,
                null, 0, member.key(), extract, tag, ""));
        ((FilterEditor) (Object) this).onClose();
        ci.cancel();
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void p2pmekanism$slotClickedGas(Slot slot, int slotId, int mouseButton, ClickType clickType, CallbackInfo ci) {
        if (!p2pmekanism$isGasFilter() || slot == null) return;
        if (!(p2pmekanism$filterMenu() instanceof FilterEditor.FilterMenu fm)) return;
        if (slot.container == fm.ghost) {
            if (!p2pmekanism$isItemPage()) return;
            int index = slot.getSlotIndex();
            if (index < p2pmekanism$chemicalIds.size()) {
                p2pmekanism$chemicalIds.remove(index);
                p2pmekanism$chemicalStacks.remove(index);
                syncGhost();
            }
            ci.cancel();
            return;
        }
        if (!p2pmekanism$isItemPage()) return;
        mekanism.common.attachments.containers.chemical.AttachedChemicals attached =
                slot.getItem().get(mekanism.common.registries.MekanismDataComponents.ATTACHED_CHEMICALS.get());
        if (attached == null) return;
        boolean added = false;
        for (ChemicalStack stored : attached.containers()) {
            if (stored == null || stored.isEmpty()) continue;
            int before = p2pmekanism$chemicalIds.size();
            p2pmekanism$addChemical(stored);
            if (p2pmekanism$chemicalIds.size() > before) added = true;
        }
        if (added) ci.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void p2pmekanism$renderChemicals(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                               CallbackInfo ci) {
        if (!p2pmekanism$isGasFilter()) return;
        Rect2i area = p2pmekanism$getMarkerArea();
        int count = Math.min(p2pmekanism$chemicalStacks.size(), 27);
        for (int i = 0; i < count; i++) {
            int x = area.getX() + (i % 9) * 18 + 1;
            int y = area.getY() + (i / 9) * 18 + 1;
            ChemicalStack stack = p2pmekanism$chemicalStacks.get(i);
            ResourceLocation id = stack.getTypeRegistryName();
            int color = 0xFF404040 | (id == null ? 0 : Math.abs(id.hashCode()) % 0x808080);
            graphics.fill(x, y, x + 16, y + 16, color);
            graphics.fill(x, y, x + 16, y + 1, 0x40FFFFFF);
            graphics.fill(x, y + 15, x + 16, y + 16, 0x40000000);
        }
        if (mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight()) {
            int index = ((mouseY - area.getY()) / 18) * 9 + ((mouseX - area.getX()) / 18);
            if (index >= 0 && index < p2pmekanism$chemicalStacks.size()) {
                graphics.renderTooltip(Minecraft.getInstance().font,
                        p2pmekanism$chemicalStacks.get(index).getTextComponent(), mouseX, mouseY);
            }
        }
    }
}

