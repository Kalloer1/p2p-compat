package com.kalloer1.p2pbotania;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.medium.Mediums;

/**
 * Botania compat（魔力介质）：仅在 botania 模组加载时由统一入口 p2p_compat 注册。
 *
 * <p>本模块从 1.20.1 独立附属 p2pbotania 移植（1.21.1 整合版没有此模块）：按整合版模块的
 * 组织方式改造——{@code @Mod} 主类改为无主类状态的 {@code CompatBotania.register()}，
 * 介质 id / 颜色 / lang 键与独立版保持一致。
 */
public final class CompatBotania {

    private CompatBotania() {}

    public static void register() {
        Mediums.register(new Medium("botania:mana", 0xFF2fbf7f, "p2p.botania.mana", 20, BotaniaManaTransfer::transfer));
        P2P.LOGGER.info("[p2pbotania] media registered (mana)");
    }
}
