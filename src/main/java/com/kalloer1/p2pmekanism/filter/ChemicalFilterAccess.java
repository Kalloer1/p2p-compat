package com.kalloer1.p2pmekanism.filter;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Runtime bridge added to P2P's Filter without changing the P2P source project. */
public interface ChemicalFilterAccess {
    List<ResourceLocation> p2pmekanism$getChemicalTemplates();
    void p2pmekanism$setChemicalTemplates(List<ResourceLocation> templates);
}
