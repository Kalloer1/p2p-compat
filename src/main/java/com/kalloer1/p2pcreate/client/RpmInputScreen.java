package com.kalloer1.p2pcreate.client;

import com.kalloer1.p2p.client.screen.P2PFlatButton;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small numeric editor used by the Create stress channel's per-output RPM button. */
public final class RpmInputScreen extends Screen {
    private static final int W = 280;
    private static final int H = 126;

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
        rpmBox = new EditBox(font, leftPos + 12, topPos + 42, W - 24, 18, Component.literal("RPM"));
        rpmBox.setMaxLength(10);
        rpmBox.setFilter(value -> value.matches("[0-9]*"));
        rpmBox.setValue(String.valueOf(Math.round(initial)));
        rpmBox.setFocused(true);
        addRenderableWidget(rpmBox);

        addRenderableWidget(new P2PFlatButton(Button.builder(Component.literal("-"), b -> step(-1))
                .bounds(leftPos + 12, topPos + 70, 36, 18)));
        addRenderableWidget(new P2PFlatButton(Button.builder(Component.literal("+"), b -> step(1))
                .bounds(leftPos + 52, topPos + 70, 36, 18)));
        addRenderableWidget(new P2PFlatButton(Button.builder(Component.translatable("gui.done"), b -> confirm())
                .bounds(leftPos + W - 176, topPos + H - 26, 78, 20)));
        addRenderableWidget(new P2PFlatButton(Button.builder(Component.translatable("gui.cancel"), b -> minecraft.setScreen(parent))
                .bounds(leftPos + W - 90, topPos + H - 26, 78, 20)));
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
        PacketDistributor.sendToServer(new P2PCreateNetwork.SetOutputConfigPayload(
                channelId, memberKey, rpm, P2PCreateNetwork.DIR_KEEP));
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x66000000);
        graphics.fill(leftPos, topPos, leftPos + W, topPos + H, 0xFF252525);
        graphics.fill(leftPos, topPos, leftPos + W, topPos + 18, 0xFF3B4148);
        graphics.fill(leftPos, topPos, leftPos + W, topPos + 1, 0xFF6E625A);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.rpm_title"),
                leftPos + 7, topPos + 5, 0xFFE7C27D, false);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.rpm_range", 1, Math.round(max)),
                leftPos + 12, topPos + 28, 0xFFB8BEC4, false);
    }
}
