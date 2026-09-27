package com.kalloer1.p2pmekanism.mixin;

import com.kalloer1.p2p.channel.Filter;
import com.kalloer1.p2pmekanism.filter.ChemicalFilterAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Adds a small, registry-name-only chemical template list to P2P's Filter. */
@Mixin(Filter.class)
public abstract class FilterMixin implements ChemicalFilterAccess {
    @Unique
    private final List<ResourceLocation> p2pmekanism$chemicalTemplates = new ArrayList<>();

    @Override
    public List<ResourceLocation> p2pmekanism$getChemicalTemplates() {
        return p2pmekanism$chemicalTemplates;
    }

    @Override
    public void p2pmekanism$setChemicalTemplates(List<ResourceLocation> templates) {
        p2pmekanism$chemicalTemplates.clear();
        if (templates != null) p2pmekanism$chemicalTemplates.addAll(templates);
    }

    @Inject(method = "isEmpty", at = @At("RETURN"), cancellable = true)
    private void p2pmekanism$includeChemicals(CallbackInfoReturnable<Boolean> cir) {
        if (!p2pmekanism$chemicalTemplates.isEmpty()) cir.setReturnValue(false);
    }

    @Inject(method = "serializeNBT", at = @At("RETURN"))
    private void p2pmekanism$serializeChemicals(HolderLookup.Provider provider,
                                                  CallbackInfoReturnable<CompoundTag> cir) {
        if (p2pmekanism$chemicalTemplates.isEmpty()) return;
        ListTag chemicals = new ListTag();
        for (ResourceLocation id : p2pmekanism$chemicalTemplates) {
            chemicals.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        cir.getReturnValue().put("chemicals", chemicals);
    }

    @Inject(method = "deserializeNBT", at = @At("RETURN"))
    private static void p2pmekanism$deserializeChemicals(CompoundTag tag, HolderLookup.Provider provider,
                                                          CallbackInfoReturnable<Filter> cir) {
        List<ResourceLocation> chemicals = new ArrayList<>();
        for (Tag entry : tag.getList("chemicals", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null && !chemicals.contains(id)) chemicals.add(id);
        }
        ((ChemicalFilterAccess) cir.getReturnValue()).p2pmekanism$setChemicalTemplates(chemicals);
    }
}
