package com.kalloer1.p2pcompat;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2pae2.CompatAE2;
import com.kalloer1.p2parsnouveau.CompatArsNouveau;
import com.kalloer1.p2pbotania.CompatBotania;
import com.kalloer1.p2pmekanism.CompatMekanism;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * 统一附属入口：合并 p2pmekanism / p2pae2 / p2parsnouveau / p2pbotania 四个旧 1.20.1 附属。
 *
 * <p>每个附属在宿主模组在场时才注册（介质、自有网络包、事件监听）。守卫放在调用点，
 * 保证缺宿主时对应 CompatXxx 类根本不被加载（其静态初始化会解析宿主类的方法引用）。
 *
 * <p>与旧独立附属互斥：Forge 1.20.1 的 mods.toml 没有 breaks 语义，无法声明互斥依赖，
 * 因此这里在构造期检测旧附属 mod id 并打 ERROR 日志提醒用户移除；运行时双注册由
 * {@code Mediums.register} 幂等兜底（先注册者保留，顺序不保证）。
 */
@Mod(P2PCompat.MODID)
public final class P2PCompat {
    public static final String MODID = "p2p_compat";

    public P2PCompat() {
        warnLegacyAddons();
        if (ModList.get().isLoaded("mekanism")) registerMekanism();
        if (ModList.get().isLoaded("ae2")) registerAE2();
        if (ModList.get().isLoaded("ars_nouveau")) registerArsNouveau();
        if (ModList.get().isLoaded("botania")) registerBotania();
        // Client half: registerClientExtensions bodies touch GUI classes, but the JVM resolves those
        // lazily per call site, so a dedicated server never loads them.
        if (FMLEnvironment.dist == Dist.CLIENT) registerClientExtensions();
        P2P.LOGGER.info("[p2p_compat] unified compat addon loaded");
    }

    private static void registerClientExtensions() {
        if (ModList.get().isLoaded("mekanism")) CompatMekanism.registerClient();
        if (ModList.get().isLoaded("ae2")) CompatAE2.registerClient();
    }

    // Each register* is a separate class-load boundary: CompatXxx (and transitively the host's
    // classes) is only ever touched from inside an isLoaded guard.

    private static void registerMekanism() {
        CompatMekanism.register();
    }

    private static void registerAE2() {
        CompatAE2.register();
    }

    private static void registerArsNouveau() {
        CompatArsNouveau.register();
    }

    private static void registerBotania() {
        CompatBotania.register();
    }

    /**
     * Forge 1.20.1 mods.toml 无法表达 breaks：旧独立附属若仍在 mods 目录会与本模组
     * 同时加载并重复注册同一介质 id。这里显式检测并提示用户移除（双注册由
     * Mediums.register 幂等兜底，但旧附属的行为未定义，必须移除）。
     */
    private static void warnLegacyAddons() {
        String[] legacy = {"p2pae2", "p2pmekanism", "p2parsnouveau", "p2pbotania"};
        for (String id : legacy) {
            if (ModList.get().isLoaded(id)) {
                P2P.LOGGER.error("[p2p_compat] legacy standalone addon '{}' detected! It is merged into "
                        + "p2p_compat and MUST be removed from the mods folder; keeping both installed "
                        + "causes duplicate medium registration and undefined behaviour.", id);
            }
        }
    }
}
