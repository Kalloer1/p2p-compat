package com.kalloer1.p2pcreate.client;

/** Hit-test information for a Create stress channel member action. */
public record ActionHit(int memberIndex, ActionKind kind, int x, int y, int width, int height) {
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}