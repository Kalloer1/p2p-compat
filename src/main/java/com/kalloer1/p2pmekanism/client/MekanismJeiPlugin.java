package com.kalloer1.p2pmekanism.client;

import com.kalloer1.p2p.client.screen.FilterEditor;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.recipe_viewer.jei.MekanismJEI;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** JEI bridge for Mekanism's ChemicalStack ingredient type. */
@JeiPlugin
public final class MekanismJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("p2pmekanism", "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(FilterEditor.class, new IGhostIngredientHandler<FilterEditor>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(FilterEditor screen, ITypedIngredient<I> ingredient,
                                                        boolean doStart) {
                if (!(screen instanceof ChemicalFilterScreenAccess access)
                        || !access.p2pmekanism$isGasFilter()
                        || !access.p2pmekanism$isItemPage()
                        || ingredient.getType() != MekanismJEI.TYPE_CHEMICAL) {
                    return List.of();
                }
                ChemicalStack chemical = ingredient.getIngredient(MekanismJEI.TYPE_CHEMICAL).orElse(null);
                if (chemical == null || chemical.isEmpty()) return List.of();
                @SuppressWarnings("unchecked")
                Target<I> target = (Target<I>) new Target<ChemicalStack>() {
                    @Override
                    public Rect2i getArea() {
                        return access.p2pmekanism$getMarkerArea();
                    }

                    @Override
                    public void accept(ChemicalStack value) {
                        access.p2pmekanism$addChemical(value);
                    }
                };
                return List.of(target);
            }

            @Override
            public void onComplete() {
            }
        });
    }
}

