package com.kalloer1.p2pmekanism.client;

import java.util.ArrayList;
import java.util.List;

import com.kalloer1.p2p.client.screen.FilterEditor;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.recipe_viewer.jei.MekanismJEI;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * JEI bridge for Mekanism's ChemicalStack ingredient type.
 *
 * <p>旧实现依赖 FilterEditorMixin 施加的 ChemicalFilterScreenAccess 接口；现在改读
 * {@link GasFilterPage} 单例的当前编辑器状态。registerGuiHandlers 首行守卫 mekanism
 * 缺席直接返回（JEI 19.21 已无 @JeiCondition），isActiveOn() 先于任何 MekanismJEI 类型
 * 常量求值，页面未激活时不触碰通用机械化学品注册表。
 *
 * <p>1.2.1 起提供两条幽灵拖入路径（真机反馈：拖气体罐物品此前被本体 addMarker
 * 吞掉——它对化学品物品是 no-op）：
 * <ul>
 * <li>TYPE_CHEMICAL：标记网格 27 个槽位矩形逐格高亮（参照本体 P2PJeiPlugin 的逐格写法），
 *     落入任意一格即 {@link GasFilterPage#addChemical(ChemicalStack)}；</li>
 * <li>VanillaTypes.ITEM_STACK：同样的逐格目标，accept 走
 *     {@link GasFilterPage#addFromItemStack(ItemStack)}——只对带「附带化学品」组件的
 *     气体罐物品生效，普通物品交给本体原有的物品标记处理。</li>
 * </ul>
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
        if (!ModList.get().isLoaded("mekanism")) return;
        registration.addGhostIngredientHandler(FilterEditor.class, new IGhostIngredientHandler<FilterEditor>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(FilterEditor screen, ITypedIngredient<I> ingredient,
                                                        boolean doStart) {
                GasFilterPage page = GasFilterPage.INSTANCE;
                if (!page.isActiveOn(screen)) return List.of();
                // 化学品与物品两条目标共用同一套 27 格几何；非这两类成分不生成目标。
                if (ingredient.getType() != MekanismJEI.TYPE_CHEMICAL
                        && ingredient.getType() != VanillaTypes.ITEM_STACK) {
                    return List.of();
                }
                Rect2i grid = screen.getMarkerGridArea();
                int pitch = screen.getMarkerPitch();
                int slot = 16;   // 槽位井 16x16，井起点即槽位原点
                List<Target<I>> targets = new ArrayList<>(FilterEditor.GRID_COLS * FilterEditor.GRID_ROWS);
                boolean itemPath = ingredient.getType() == VanillaTypes.ITEM_STACK;
                for (int row = 0; row < FilterEditor.GRID_ROWS; row++) {
                    for (int col = 0; col < FilterEditor.GRID_COLS; col++) {
                        final int sx = grid.getX() + col * pitch;
                        final int sy = grid.getY() + row * pitch;
                        targets.add(new Target<I>() {
                            @Override
                            public Rect2i getArea() {
                                return new Rect2i(sx, sy, slot, slot);
                            }

                            @Override
                            public void accept(I value) {
                                if (itemPath) {
                                    page.addFromItemStack((ItemStack) value);
                                } else {
                                    page.addChemical((ChemicalStack) value);
                                }
                            }
                        });
                    }
                }
                return targets;
            }

            @Override
            public void onComplete() {
            }
        });
    }
}
