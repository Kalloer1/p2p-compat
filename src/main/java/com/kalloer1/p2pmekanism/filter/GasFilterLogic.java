package com.kalloer1.p2pmekanism.filter;

import com.kalloer1.p2p.channel.Filter;
import com.kalloer1.p2pmekanism.CompatMekanism;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Applies the chemical part of a P2P filter to a Mekanism chemical stack. */
public final class GasFilterLogic {
    private GasFilterLogic() {}

    /** Chemical templates live in the base mod's namespaced pass-through slot (Filter.addonData). */
    public static List<ResourceLocation> templates(Filter filter) {
        List<ResourceLocation> ids = new ArrayList<>();
        if (filter == null) return ids;
        CompoundTag gas = filter.addonData.get(CompatMekanism.GAS_ID);
        if (gas == null) return ids;
        for (Tag entry : gas.getList("chemicals", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    public static boolean accepts(Filter filter, ChemicalStack stack) {
        if (filter == null || stack == null || stack.isEmpty()) return true;
        List<ResourceLocation> templates = templates(filter);
        // A filter created for a gas channel with no gas markers is intentionally a pass-through filter.
        if (templates.isEmpty()) return true;

        boolean match = templates.contains(stack.getTypeRegistryName());
        return filter.getMode() == Filter.Mode.WHITELIST ? match : !match;
    }
}
