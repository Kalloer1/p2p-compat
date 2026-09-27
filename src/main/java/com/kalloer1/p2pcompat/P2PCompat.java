package com.kalloer1.p2pcompat;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2pae2.CompatAE2;
import com.kalloer1.p2parsnouveau.CompatArsNouveau;
import com.kalloer1.p2pcreate.CompatCreate;
import com.kalloer1.p2pmekanism.CompatMekanism;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/**
 * 统一附属入口：合并 p2pcreate / p2pmekanism / p2pae2 / p2parsnouveau 四个旧附属。
 *
 * <p>每个附属在宿主模组在场时才注册（介质、自有网络包、事件监听）。守卫放在调用点，
 * 保证缺宿主时对应 CompatXxx 类根本不被加载（其静态初始化会解析宿主类的方法引用）。
 */
@Mod(P2PCompat.MODID)
public final class P2PCompat {
    public static final String MODID = "p2p_compat";

    public P2PCompat(IEventBus modEventBus, ModContainer modContainer) {
        if (ModList.get().isLoaded("create")) registerCreate(modEventBus);
        if (ModList.get().isLoaded("mekanism")) registerMekanism(modEventBus);
        if (ModList.get().isLoaded("ae2")) registerAE2(modEventBus);
        if (ModList.get().isLoaded("ars_nouveau")) registerArsNouveau(modEventBus);
        P2P.LOGGER.info("[p2p_compat] unified compat addon loaded");
    }

    // Each register* is a separate class-load boundary: CompatXxx (and transitively the host's
    // classes) is only ever touched from inside an isLoaded guard.

    private static void registerCreate(IEventBus bus) {
        CompatCreate.register(bus);
    }

    private static void registerMekanism(IEventBus bus) {
        CompatMekanism.register(bus);
    }

    private static void registerAE2(IEventBus bus) {
        CompatAE2.register(bus);
    }

    private static void registerArsNouveau(IEventBus bus) {
        CompatArsNouveau.register(bus);
    }
}
