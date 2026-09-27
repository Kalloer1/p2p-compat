package com.kalloer1.p2pmekanism.filter;

import com.kalloer1.p2p.channel.Filter;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Applies the chemical part of a P2P filter to a Mekanism chemical stack. */
public final class GasFilterLogic {
    private GasFilterLogic() {}

    public static boolean accepts(Filter filter, ChemicalStack stack) {
        if (filter == null || stack == null || stack.isEmpty()) return true;
        if (!(filter instanceof ChemicalFilterAccess access)) return true;

        List<ResourceLocation> templates = access.p2pmekanism$getChemicalTemplates();
        // A filter created for a gas channel with no gas markers is intentionally a pass-through filter.
        if (templates.isEmpty()) return true;

        boolean match = templates.contains(stack.getTypeRegistryName());
        return filter.getMode() == Filter.Mode.WHITELIST ? match : !match;
    }
}
