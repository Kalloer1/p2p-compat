package com.kalloer1.p2pmekanism.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Screen's own addRenderableWidget via @Invoker so addon screens can add widgets without shadowing an inherited method. */
@Mixin(Screen.class)
public interface ScreenAddRenderableInvoker {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable> T p2pmekanism$addRenderable(T widget);
}
