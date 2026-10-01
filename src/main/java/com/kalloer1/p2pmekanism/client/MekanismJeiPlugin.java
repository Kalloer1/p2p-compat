package com.kalloer1.p2pmekanism.client;

import java.util.ArrayList;
import java.util.List;

import com.kalloer1.p2p.client.screen.FilterEditor;
import mekanism.api.chemical.gas.GasStack;
import mekanism.client.jei.MekanismJEI;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * JEI bridge for Mekanism's GasStack ingredient type (JEI 15.x, Mekanism 10.4).
 *
 * <p>移植自 1.21.1 整合版同款插件，读 {@link GasFilterPage} 单例的当前编辑器状态。
 * registerGuiHandlers 首行守卫 mekanism 缺席直接返回，isActiveOn() 先于任何 MekanismJEI
 * 类型常量求值，页面未激活时不触碰通用机械化学品注册表。
 *
 * <p>两条幽灵拖入路径：
 * <ul>
 * <li>TYPE_GAS（1.20.1 Mekanism 的 JEI 成分类型，10.7 里叫 TYPE_CHEMICAL）：标记网格 27 个
 *     槽位矩形逐格高亮（参照本体 P2PJeiPlugin 的逐格写法），落入任意一格即
 *     {@link GasFilterPage#addChemical(GasStack)}；</li>
 * <li>VanillaTypes.ITEM_STACK：同样的逐格目标，accept 走
 *     {@link GasFilterPage#addFromItemStack(ItemStack)}——只对带气体附着（item capability）
 *     的气体罐物品生效，普通物品交给本体原有的物品标记处理。</li>
 * </ul>
 *
 * <p>1.20.1 API 适配：{@code ResourceLocation} 用构造器（无 fromNamespaceAndPath）；
 * JEI 15 的 {@code IGhostIngredientHandler} 同样有 {@code getTargetsTyped}，签名与 JEI 19 一致。
 */
@JeiPlugin
public final class MekanismJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = new ResourceLocation("p2p_compat", "jei_plugin");

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
                if (ingredient.getType() != MekanismJEI.TYPE_GAS
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
                                    page.addChemical((GasStack) value);
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
