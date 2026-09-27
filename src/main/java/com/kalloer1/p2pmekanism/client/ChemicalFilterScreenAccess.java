package com.kalloer1.p2pmekanism.client;

import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

public interface ChemicalFilterScreenAccess {
    boolean p2pmekanism$isGasFilter();
    boolean p2pmekanism$isItemPage();
    Rect2i p2pmekanism$getMarkerArea();
    void p2pmekanism$addChemical(ChemicalStack stack);
}
