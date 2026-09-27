package com.kalloer1.p2pmekanism.client;

import java.util.List;

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

/**
 * JEI bridge for Mekanism's ChemicalStack ingredient type.
 *
 * <p>旧实现依赖 FilterEditorMixin 施加的 ChemicalFilterScreenAccess 接口；现在改读
 * {@link GasFilterPage} 单例的当前编辑器状态。isActiveOn() 先于任何 MekanismJEI 类型
 * 常量求值，页面未激活时不触碰机械动力化学品注册表。
 */
@JeiPlugin
public final class MekanismJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("p2p_compat", "jei_plugin");

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
                GasFilterPage page = GasFilterPage.INSTANCE;
                if (!page.isActiveOn(screen)) return List.of();
                if (ingredient.getType() != MekanismJEI.TYPE_CHEMICAL) return List.of();
                ChemicalStack chemical = ingredient.getIngredient(MekanismJEI.TYPE_CHEMICAL).orElse(null);
                if (chemical == null || chemical.isEmpty()) return List.of();
                @SuppressWarnings("unchecked")
                Target<I> target = (Target<I>) new Target<ChemicalStack>() {
                    @Override
                    public Rect2i getArea() {
                        return page.markerArea();
                    }

                    @Override
                    public void accept(ChemicalStack value) {
                        page.addChemical(value);
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
