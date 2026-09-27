package com.kalloer1.p2pmekanism;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.medium.Mediums;
import net.neoforged.bus.api.IEventBus;

/**
 * Mekanism compat（气体/化学品 + 热量介质）：仅在 Mekanism 模组加载时由统一入口 p2p_compat 注册。
 * 注册走本体 1.1.0 唯一契约：附属构造期直调 Mediums.register（幂等）。
 */
public final class CompatMekanism {
    public static final String MODID = "p2pmekanism";

    private CompatMekanism() {}

    public static void register(IEventBus modEventBus) {
        Mediums.register(new Medium("mekanism:gas", 0xFF4fbfbf, "p2p.mekanism.gas", 20, MekGasTransfer::transfer));
        Mediums.register(new Medium("mekanism:heat", 0xFFd97b4f, "p2p.mekanism.heat", 5, MekHeatTransfer::transfer));
        P2P.LOGGER.info("[p2pmekanism] media registered (gas, heat)");
    }
}
