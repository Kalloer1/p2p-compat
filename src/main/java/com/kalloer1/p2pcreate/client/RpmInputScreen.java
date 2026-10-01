package com.kalloer1.p2pcreate.client;

import com.kalloer1.p2p.client.screen.P2PFlatButton;
import com.kalloer1.p2p.client.screen.P2PTheme;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import com.kalloer1.p2pcompat.network.CompatNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Small numeric editor used by the Create stress channel's per-output RPM button.
 *
 * <p>1.2.0 skin: masked dialog + rounded white card shared with the core prompts
 * (spec §3 last row); the value row is a stepper (−/+ TINT circles around a FIELD_BG
 * pill), and the confirm/cancel controls are borderless text buttons.
 *
 * <p>1.20.1 适配点：{@code Screen#renderBackground} 只有 (GuiGraphics) 单参形态
 * （(GuiGraphics, int, int, float) 重载是 1.20.2 起的）；发包走 compat 通道。
 */
public final class RpmInputScreen extends Screen {
    private static final int W = 280;
    private static final int H = 126;
    private static final int STEPPER_Y = 36, CTRL_H = 14;
    private static final int BTN_Y = H - 24;

    private final Screen parent;
    private final int channelId;
    private final String memberKey;
    private final float initial;
    private final boolean reverse;
    private final float max;
    private int leftPos;
    private int topPos;
    private EditBox rpmBox;

    public RpmInputScreen(Screen parent, int channelId, String memberKey, float initial, boolean reverse, float max) {
        super(Component.translatable("p2pcreate.gui.rpm_title"));
        this.parent = parent;
        this.channelId = channelId;
        this.memberKey = memberKey;
        this.initial = initial;
        this.reverse = reverse;
        this.max = Math.max(1, max);
    }

    @Override
    protected void init() {
        leftPos = (width - W) / 2;
        topPos = (height - H) / 2;

        int fieldX = leftPos + P2PTheme.PAD + 22;
        int fieldW = W - P2PTheme.PAD * 2 - 44;
        rpmBox = new EditBox(font, fieldX + 4, topPos + STEPPER_Y + 3, fieldW - 8, 12, Component.literal("RPM"));
        rpmBox.setMaxLength(10);
        rpmBox.setFilter(value -> value.matches("[0-9]*"));
        rpmBox.setValue(String.valueOf(Math.round(initial)));
        rpmBox.setBordered(false);
        rpmBox.setTextColor(P2PTheme.TEXT_PRIMARY);
        addRenderableWidget(rpmBox);
        // 键盘输入走 Screen 级焦点；只调 rpmBox.setFocused(true) 打不了字。
        this.setInitialFocus(rpmBox);

        addRenderableWidget(new P2PFlatButton(Button.builder(Component.literal("-"), b -> step(-1))
                .bounds(leftPos + P2PTheme.PAD, topPos + STEPPER_Y, CTRL_H, CTRL_H))
                .setStyle(P2PFlatButton.Style.STEPPER));
        addRenderableWidget(new P2PFlatButton(Button.builder(Component.literal("+"), b -> step(1))
                .bounds(leftPos + W - P2PTheme.PAD - CTRL_H, topPos + STEPPER_Y, CTRL_H, CTRL_H))
                .setStyle(P2PFlatButton.Style.STEPPER));

        textButton(Component.translatable("gui.done"), b -> confirm(), true);
        textButton(Component.translatable("gui.cancel"), b -> minecraft.setScreen(parent), false);
    }

    private Button textButton(Component label, Button.OnPress action, boolean trailingRight) {
        int w = font.width(label) + 12;
        int x = trailingRight ? leftPos + W - P2PTheme.PAD - w : leftPos + P2PTheme.PAD;
        P2PFlatButton b = new P2PFlatButton(Button.builder(label, action).bounds(x, topPos + BTN_Y, w, CTRL_H));
        b.setStyle(P2PFlatButton.Style.TEXT);
        return addRenderableWidget(b);
    }

    private void step(int delta) {
        int value = Mth.clamp(parse(), 1, (int) max);
        rpmBox.setValue(String.valueOf(Mth.clamp(value + delta, 1, (int) max)));
        rpmBox.setCursorPosition(rpmBox.getValue().length());
    }

    private int parse() {
        try {
            return Integer.parseInt(rpmBox.getValue());
        } catch (NumberFormatException ignored) {
            return Math.round(initial);
        }
    }

    private void confirm() {
        float rpm = Mth.clamp(parse(), 1, (int) max);
        KineticBridgeState.setClientOutputConfig(channelId, memberKey, rpm, reverse, max);
        // DIR_KEEP: only the rpm travels; the server keeps its own direction.
        CompatNetwork.sendToServer(new P2PCreateNetwork.SetOutputConfigPayload(
                channelId, memberKey, rpm, P2PCreateNetwork.DIR_KEEP));
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        P2PTheme.dialog(graphics, width, height, leftPos, topPos, W, H, font, title);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.rpm_range", 1, Math.round(max)),
                leftPos + P2PTheme.PAD, topPos + 22, P2PTheme.TEXT_SECOND, false);
        int fieldX = leftPos + P2PTheme.PAD + 22;
        int fieldW = W - P2PTheme.PAD * 2 - 44;
        P2PTheme.field(graphics, fieldX, topPos + STEPPER_Y, fieldW, CTRL_H);
    }
}
