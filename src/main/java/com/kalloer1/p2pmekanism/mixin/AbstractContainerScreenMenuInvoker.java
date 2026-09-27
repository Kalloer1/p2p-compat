package com.kalloer1.p2pmekanism.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes AbstractContainerScreen's own getMenu() so the addon never has to resolve inherited members. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenMenuInvoker {
    @Invoker("getMenu")
    AbstractContainerMenu p2pmekanism$callGetMenu();
}
