package com.kalloer1.p2pmekanism.client;

import com.kalloer1.p2p.client.screen.FilterEditor;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.recipe_viewer.emi.ChemicalEmiStack;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * EMI bridge for the gas filter page (1.2.1 真机反馈：EMI 用户把化学品/气体罐拖进
 * FilterEditor 的标记网格完全不通——EMI 对幽灵拖入没有 JEI 那样的通用容器槽位兜底，
 * 必须显式注册拖放处理器；此前附属只写了 JEI 一条路）。
 *
 * <p>EMI 通过注解扫描发现带 {@link EmiEntrypoint} 的 {@link EmiPlugin} 类；本类只在
 * EMI 在场时才会被 EMI 加载，EMI 缺席时不会有人触碰这里的任何类型，因此 EMI 依赖保持
 * compileOnly、运行时无需声明。register 首行守卫 mekanism 缺席直接返回。
 *
 * <p>dropStack 守卫 = {@link GasFilterPage#isActiveOn(Object)}（内含物品页判定）+
 * 落点在标记网格内。成分提取参照 Mekanism 自家 EmiGhostIngredientHandler 的判型：
 * {@link ChemicalEmiStack#getStack()} 拿 ChemicalStack（通用 getItemStack() 拿不到气体）；
 * 普通物品堆（气体罐）走 {@link GasFilterPage#addFromItemStack(ItemStack)}，非化学品
 * 物品返回 false，交还给 EMI 原有拖放逻辑处理。
 */
@EmiEntrypoint
public final class MekanismEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        if (!ModList.get().isLoaded("mekanism")) return;
        registry.addDragDropHandler(FilterEditor.class, new EmiDragDropHandler<FilterEditor>() {
            @Override
            public boolean dropStack(FilterEditor screen, EmiIngredient ingredient, int mx, int my) {
                GasFilterPage page = GasFilterPage.INSTANCE;
                if (!page.isActiveOn(screen)) return false;
                net.minecraft.client.renderer.Rect2i grid = page.markerArea();
                if (mx < grid.getX() || mx >= grid.getX() + grid.getWidth()
                        || my < grid.getY() || my >= grid.getY() + grid.getHeight()) return false;
                for (EmiStack s : ingredient.getEmiStacks()) {
                    if (s instanceof ChemicalEmiStack chemical) {
                        ChemicalStack stack = chemical.getStack();
                        if (stack != null && !stack.isEmpty()) {
                            page.addChemical(stack);
                            return true;
                        }
                        continue;
                    }
                    ItemStack item = s.getItemStack();
                    if (item != null && !item.isEmpty() && page.addFromItemStack(item)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }
}
