package com.kalloer1.p2pmekanism.client;

import com.kalloer1.p2p.client.screen.FilterEditor;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * EMI bridge for the gas filter page (EMI 1.1.24 for 1.20.1)。
 *
 * <p>移植自 1.21.1 整合版同款插件：EMI 对幽灵拖入没有 JEI 那样的通用容器槽位兜底，
 * 必须显式注册拖放处理器。dropStack 守卫 = {@link GasFilterPage#isActiveOn(Object)}
 * （内含物品页判定）+ 落点在标记网格内；普通物品堆（气体罐）走
 * {@link GasFilterPage#addFromItemStack(ItemStack)}，返回 true 才消费，否则交还给
 * EMI 原有拖放逻辑处理。
 *
 * <p><b>与 1.21.1 版的偏差</b>：1.21.1 版经 Mekanism 自家的
 * {@code mekanism.client.recipe_viewer.emi.ChemicalEmiStack} 支持「纯化学品」成分拖入；
 * Mekanism 10.4（1.20.1）没有 EMI 集成、也没有化学品 EmiStack 包装类型，因此本版只走
 * 物品堆路径（气体罐物品拖入仍可用，气体注册表内的化学品本体拖入不生效）。
 *
 * <p>EMI 通过注解扫描发现带 {@link EmiEntrypoint} 的 {@link EmiPlugin} 类；本类只在
 * EMI 在场时才会被 EMI 加载，EMI 缺席时不会有人触碰这里的任何类型，因此 EMI 依赖保持
 * compileOnly、运行时无需声明。register 首行守卫 mekanism 缺席直接返回。
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
                Rect2i grid = page.markerArea();
                if (mx < grid.getX() || mx >= grid.getX() + grid.getWidth()
                        || my < grid.getY() || my >= grid.getY() + grid.getHeight()) return false;
                for (EmiStack s : ingredient.getEmiStacks()) {
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
