package com.kalloer1.p2pcreate.mixin.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.kalloer1.p2p.channel.Channel;
import com.kalloer1.p2p.channel.Member;
import com.kalloer1.p2p.channel.Role;
import com.kalloer1.p2p.Lang;
import com.kalloer1.p2p.client.screen.ChannelScreen;
import com.kalloer1.p2p.client.screen.NameInputScreen;
import com.kalloer1.p2p.client.screen.P2PFlatButton;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.network.ChannelActionC2SPacket;
import com.kalloer1.p2p.network.ModMessages;
import com.kalloer1.p2pcreate.CompatCreate;
import com.kalloer1.p2pcreate.client.ActionHit;
import com.kalloer1.p2pcreate.client.ActionKind;
import com.kalloer1.p2pcreate.client.RpmInputScreen;
import com.kalloer1.p2pcreate.bridge.KineticBridgeState;
import com.kalloer1.p2pcreate.bridge.KineticEndpoints;
import com.kalloer1.p2pcreate.mixin.KineticBlockEntityAccessor;
import com.kalloer1.p2pcreate.network.P2PCreateNetwork;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Replaces the generic item/fluid controls with a compact Create stress layout.
 * The P2P screen remains the owner of channel management; this mixin changes the
 * stress channel's presentation and output controls, plus two layout fixes that
 * apply to every channel (medium tag fitting, config button row re-flow).
 */
@Mixin(ChannelScreen.class)
public abstract class ChannelScreenMixin {
    @Shadow private int selectedChannel;
    @Shadow private int leftPos;
    @Shadow private int topPos;
    @Shadow private List<Channel> channels;

    @Shadow private P2PFlatButton redstoneBtn;
    @Shadow private P2PFlatButton distBtn;
    @Shadow private P2PFlatButton groupBtn;
    @Shadow private P2PFlatButton cleanBtn;
    @Shadow private P2PFlatButton chunkLoadBtn;
    @Shadow private P2PFlatButton rateDown;
    @Shadow private P2PFlatButton rateUp;
    @Shadow private P2PFlatButton speedDown;
    @Shadow private P2PFlatButton speedUp;
    @Shadow private P2PFlatButton maxRateBtn;
    @Shadow private EditBox rateEdit;
    @Shadow private EditBox speedEdit;

    // Base-screen hover hit lists (List<BlockHit>/List<DimHit> over private records; raw type on purpose).
    @SuppressWarnings("rawtypes")
    @Shadow @Final private List blockHits;
    @SuppressWarnings("rawtypes")
    @Shadow @Final private List dimHits;

    private int p2pcreate$memberScroll;
    private final List<ActionHit> p2pcreate$actionHits = new ArrayList<>();

    // drawRight returns early when the channel is missing or has no members; @At("RETURN")
    // covers every exit path so the stress panel is painted even for empty channels.
    @Inject(method = "drawRight", at = @At(value = "RETURN"), require = 1)
    private void p2pcreate$drawStressLayout(GuiGraphics graphics, CallbackInfo ci) {
        p2pcreate$fitConfigRow();
        Channel selected = p2pcreate$selectedChannel();
        if (!p2pcreate$isStress(selected)) return;

        p2pcreate$hideGenericControls();
        // The base table just registered icon/dimension hover boxes at its own 16px row
        // pitch; our 18px rows would trigger the wrong endpoint's tooltip.
        blockHits.clear();
        dimHits.clear();
        p2pcreate$drawStressPanel(graphics, selected);
    }

    // GuiGraphics renders items in a batched layer painted after the panel quads, so the base
    // table's block icons cannot be covered by fills - they must not be submitted at all for
    // stress channels, or they float over the replacement layout at stale positions.
    @Redirect(method = "drawRight",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/item/ItemStack;II)V"),
            require = 1)
    private void p2pcreate$skipStressIcons(GuiGraphics graphics, ItemStack icon, int x, int y) {
        if (p2pcreate$isStress(p2pcreate$selectedChannel())) return;
        graphics.renderItem(icon, x, y);
    }

    // The base tag is a fixed 28px plate with untrimmed text, so long medium names ("Create 应力",
    // "气体/化学品") run past the list border. Draw the same plate right-aligned at the original
    // right edge, sized to the text and ellipsised at a capped width instead.
    @Redirect(method = "drawLeft",
            at = @At(value = "INVOKE",
                    target = "Lcom/kalloer1/p2p/client/screen/ChannelScreen;typeTag(Lnet/minecraft/client/gui/GuiGraphics;IILcom/kalloer1/p2p/medium/Medium;)V"),
            require = 1)
    private void p2pcreate$typeTagFitted(ChannelScreen self, GuiGraphics graphics, int bx, int by, Medium type) {
        if (type == null) return;
        Font font = self.getMinecraft().font;
        Component label = type.displayName();
        int textWidth = font.width(label);
        int tagWidth = Math.min(textWidth + 8, 56);
        int x = bx + 28 - tagWidth;
        graphics.fill(x, by, x + tagWidth, by + 11, type.argbColor);
        graphics.fill(x, by, x + tagWidth, by + 1, 0x55FFFFFF);
        Component shown = textWidth + 8 > tagWidth
                ? Component.literal(p2pcreate$trimWidth(font, label.getString(), tagWidth - 8))
                : label;
        graphics.drawString(font, shown, x + 4, by + 2, 0xFFFFFFFF);
    }

    /**
     * Re-flows the 红石/分配/分组/清理空气/区块加载 row to its messages' real widths; the base
     * uses one fixed width per button, so longer Chinese labels spill past the panel edge.
     * Runs after drawRight refreshed the messages and before renderWidgets draws the buttons.
     */
    private void p2pcreate$fitConfigRow() {
        if (redstoneBtn == null || !redstoneBtn.visible) return;
        Font font = ((ChannelScreen) (Object) this).getMinecraft().font;
        P2PFlatButton[] row = {redstoneBtn, distBtn, groupBtn, cleanBtn, chunkLoadBtn};
        int rx = leftPos + 148;
        int rw = 384 - 148 - 4;
        int gap = 4;
        int padding = 8;
        int[] textWidths = new int[row.length];
        int textTotal = 0;
        for (int i = 0; i < row.length; i++) {
            textWidths[i] = font.width(row[i].getMessage());
            textTotal += textWidths[i];
        }
        int budget = rw - gap * (row.length - 1) - row.length * padding;
        float scale = textTotal <= budget ? 1f : (float) budget / textTotal;
        int x = rx;
        for (int i = 0; i < row.length; i++) {
            int text = Math.max(12, Math.round(textWidths[i] * scale));
            if (text < textWidths[i]) row[i].setMessage(Component.literal(
                    p2pcreate$trimWidth(font, row[i].getMessage().getString(), text)));
            row[i].setX(x);
            row[i].setWidth(text + padding);
            x += text + padding + gap;
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
    private void p2pcreate$handleStressLayout(double mouseX, double mouseY, int button,
            CallbackInfoReturnable<Boolean> cir) {
        Channel selected = p2pcreate$selectedChannel();
        if (!p2pcreate$isStress(selected)) return;

        int bodyTop = topPos + 84;
        int bodyBottom = topPos + 182;
        int rx = leftPos + 148;
        int rw = 384 - 148 - 4;
        if (mouseX < rx || mouseX >= rx + rw || mouseY < bodyTop || mouseY >= bodyBottom) return;
        // Every button inside the stress table is consumed: the generic role/face/delete
        // controls hidden underneath must never react to right- or middle-clicks.
        if (button != 0) {
            cir.setReturnValue(true);
            return;
        }

        for (ActionHit hit : p2pcreate$actionHits) {
            if (!hit.contains(mouseX, mouseY)) continue;
            if (hit.kind() == ActionKind.RPM) {
                Member member = p2pcreate$member(selected, hit.memberIndex());
                if (member != null) {
                    var config = KineticBridgeState.getClientOutputConfig(selected.id, member,
                            KineticBridgeState.maxRotationSpeed());
                    ((ChannelScreen) (Object) this).getMinecraft().setScreen(
                            new RpmInputScreen((ChannelScreen) (Object) this, selected.id, member.key(),
                                    config.rpm(), config.reverse(), KineticBridgeState.maxRotationSpeed()));
                }
            } else if (hit.kind() == ActionKind.DIRECTION) {
                Member member = p2pcreate$member(selected, hit.memberIndex());
                if (member != null) {
                    var config = KineticBridgeState.getClientOutputConfig(selected.id, member,
                            KineticBridgeState.maxRotationSpeed());
                    KineticBridgeState.setClientOutputConfig(selected.id, member.key(), config.rpm(),
                            !config.reverse(), KineticBridgeState.maxRotationSpeed());
                    // rpm < 0 + DIR_TOGGLE: the server flips direction and keeps its own rpm,
                    // so a stale client cache can never overwrite the other field.
                    PacketDistributor.sendToServer(new P2PCreateNetwork.SetOutputConfigPayload(
                            selected.id, member.key(), -1, P2PCreateNetwork.DIR_TOGGLE));
                }
            } else if (hit.kind() == ActionKind.PRIORITY) {
                Member member = p2pcreate$member(selected, hit.memberIndex());
                if (member != null && selected.distribution == Channel.Distribution.PRIORITY) {
                    ((ChannelScreen) (Object) this).getMinecraft().setScreen(new NameInputScreen(
                            (ChannelScreen) (Object) this,
                            Lang.t(Lang.UI_SET_PRIORITY),
                            String.valueOf(member.priority),
                            2, selected.id, member.key()));
                }
            } else if (hit.kind() == ActionKind.DELETE) {
                p2pcreate$removeMember(selected, hit.memberIndex());
            } else if (hit.kind() == ActionKind.ROLE) {
                Member member = p2pcreate$member(selected, hit.memberIndex());
                if (member != null) {
                    int target = member.role == Role.INPUT ? Role.OUTPUT.ordinal() : Role.INPUT.ordinal();
                    ModMessages.sendToServer(new ChannelActionC2SPacket(selected.id,
                            ChannelActionC2SPacket.Action.SET_ROLE, null, target, member.key(), false, null, ""));
                }
            }
            cir.setReturnValue(true);
            return;
        }

        // Consume clicks inside the stress table so the generic filter/priority/face controls
        // underneath the painted replacement cannot be activated accidentally.
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 1)
    private void p2pcreate$scrollStressLayout(double mouseX, double mouseY, double scrollX, double scrollY,
            CallbackInfoReturnable<Boolean> cir) {
        Channel selected = p2pcreate$selectedChannel();
        if (!p2pcreate$isStress(selected)) return;
        int rx = leftPos + 148;
        int rw = 384 - 148 - 4;
        if (mouseX < rx || mouseX >= rx + rw || mouseY < topPos + 84 || mouseY >= topPos + 182) return;

        int total = p2pcreate$sectionHeight(selected);
        int viewport = 98;
        int max = Math.max(0, total - viewport);
        p2pcreate$memberScroll = Math.max(0, Math.min(max,
                p2pcreate$memberScroll - (int) Math.signum(scrollY) * 18));
        cir.setReturnValue(true);
    }

    private void p2pcreate$drawStressPanel(GuiGraphics graphics, Channel channel) {
        int rx = leftPos + 148;
        int rw = 384 - 148 - 4;
        int panelBottom = topPos + 182;
        var screen = (ChannelScreen) (Object) this;
        var font = screen.getMinecraft().font;

        // Cover the generic rate/throttle labels and endpoint table drawn by P2P.
        graphics.fill(rx, topPos + 48, rx + rw, panelBottom, 0xFF252525);
        p2pcreate$inset(graphics, rx, topPos + 48, rw, panelBottom - (topPos + 48));

        List<Member> inputs = p2pcreate$members(channel, Role.INPUT);
        List<Member> outputs = p2pcreate$members(channel, Role.OUTPUT);
        float inputSupply = 0;
        boolean missingInput = false;
        for (Member member : inputs) {
            float value = p2pcreate$inputSupply(member);
            if (value < 0) missingInput = true;
            else inputSupply += value;
        }
        float outputDemand = 0;
        boolean missingOutput = false;
        boolean anyPausedOutput = false;
        for (Member member : outputs) {
            float value = p2pcreate$outputDemand(member);
            if (value < 0) missingOutput = true;
            else outputDemand += value;
            if (KineticBridgeState.getClientOutputConfig(channel.id, member,
                    KineticBridgeState.maxRotationSpeed()).paused()) anyPausedOutput = true;
        }

        graphics.drawString(font, Component.translatable("p2pcreate.gui.stress_status"),
                rx + 5, topPos + 52, 0xFFE7C27D, false);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.input_available",
                        p2pcreate$formatStress(inputSupply, missingInput)),
                rx + 5, topPos + 64, 0xFFE0E0E0, false);
        graphics.drawString(font, Component.translatable("p2pcreate.gui.output_demand",
                        p2pcreate$formatStress(outputDemand, missingOutput)),
                rx + 116, topPos + 64, 0xFFE0E0E0, false);
        String statusKey;
        int statusColor = 0xFFFFC66D;
        if (inputs.isEmpty() || outputs.isEmpty()) statusKey = "p2pcreate.gui.status.missing_io";
        else if (missingInput || missingOutput) statusKey = "p2pcreate.gui.status.unloaded";
        else if (anyPausedOutput) statusKey = "p2pcreate.gui.status.paused";
        else { statusKey = "p2pcreate.gui.status.connected"; statusColor = 0xFF8FD18F; }
        graphics.drawString(font, Component.translatable("p2pcreate.gui.status",
                Component.translatable(statusKey)), rx + 5, topPos + 76, statusColor, false);

        int listTop = topPos + 84;
        int listBottom = panelBottom - 1;
        graphics.fill(rx + 1, listTop, rx + rw - 1, listBottom, 0xFF202020);
        p2pcreate$actionHits.clear();
        p2pcreate$memberScroll = Math.max(0, Math.min(p2pcreate$memberScroll,
                Math.max(0, p2pcreate$sectionHeight(channel) - 98)));

        graphics.enableScissor(rx + 1, listTop + 1, rx + rw - 1, listBottom);
        int y = listTop - p2pcreate$memberScroll;
        for (int index = 0; index < channel.members.size(); index++) {
            Member member = channel.members.get(index);
            if (member.role != Role.INPUT) continue;
            p2pcreate$drawMemberRow(graphics, channel, member, index, y, false);
            y += 18;
        }
        for (int index = 0; index < channel.members.size(); index++) {
            Member member = channel.members.get(index);
            if (member.role != Role.OUTPUT) continue;
            p2pcreate$drawMemberRow(graphics, channel, member, index, y, true);
            y += 18;
        }
        if (inputs.isEmpty() && outputs.isEmpty()) {
            graphics.drawString(font, Component.translatable("p2pcreate.gui.no_endpoints"),
                    rx + 5, listTop + 25, 0xFF8B8B8B, false);
        }
        graphics.disableScissor();
    }

    private void p2pcreate$drawMemberRow(GuiGraphics graphics, Channel channel, Member member,
            int memberIndex, int y, boolean output) {
        int rx = leftPos + 148;
        int rw = 384 - 148 - 4;
        if (y + 18 <= topPos + 84 || y >= topPos + 181) return;
        graphics.fill(rx + 2, y, rx + rw - 2, y + 17, 0xFF2B2B2B);

        ItemStack icon = p2pcreate$endpointIcon(member);
        if (!icon.isEmpty()) graphics.renderItem(icon, rx + 2, y + 1);
        p2pcreate$button(graphics, rx + 20, y + 2, 22, 14,
                Component.literal(output ? "OUT" : "IN"), output ? 0xFF3A7AD9 : 0xFFD93A3A);
        Font font = ((ChannelScreen) (Object) this).getMinecraft().font;
        String key = p2pcreate$trimWidth(font, p2pcreate$shortKey(member), output ? 48 : 80);
        graphics.drawString(font, Component.literal(key), rx + 44, y + 5, 0xFFE0E0E0, false);

        // Same predicate the server bridge uses to resolve endpoints: a row that fails it can
        // never transfer, so surface why (bound to a non-kinetic block, unloaded, …) on the row.
        Component warning = p2pcreate$endpointWarning(member, output);

        if (!output) {
            if (warning != null) {
                graphics.drawString(font, Component.literal("!" + warning.getString()),
                        rx + 126, y + 5, 0xFFFF6D6D, false);
            } else {
                float supplied = p2pcreate$inputSupply(member);
                String value = supplied < 0 ? "—" : p2pcreate$formatNumber(supplied) + " SU";
                graphics.drawString(font, Component.translatable("p2pcreate.gui.supply", value),
                        rx + 126, y + 5, 0xFFE7C27D, false);
            }
            p2pcreate$button(graphics, rx + rw - 20, y + 2, 16, 14, Component.literal("×"), 0xFF6C3030);
            p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.ROLE, rx + 20, y + 2, 22, 14));
            p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.DELETE, rx + rw - 20, y + 2, 16, 14));
        } else {
            var config = KineticBridgeState.getClientOutputConfig(channel.id, member,
                    KineticBridgeState.maxRotationSpeed());
            boolean marked = warning != null || config.paused();
            graphics.drawString(font, Component.literal(Math.round(config.rpm()) + " RPM"),
                    rx + 94, y + 5, marked ? 0xFF8B8B8B : 0xFFE7C27D, false);
            Component dirLabel = warning != null ? warning : config.paused()
                    ? p2pcreate$reasonLabel(config.reason())
                    : Component.translatable(config.reverse()
                            ? "p2pcreate.gui.counter_clockwise" : "p2pcreate.gui.clockwise");
            // The direction cell doubles as the toggle control (replacing the old separate ↻
            // button) and carries the row's warning / pause reason when the output cannot run.
            p2pcreate$button(graphics, rx + 128, y + 2, 34, 14,
                    Component.literal((marked ? "!" : "")
                            + p2pcreate$trimWidth(font, dirLabel.getString(), marked ? 26 : 30)),
                    marked ? 0xFF6C3030 : 0xFF3D3530);
            if (channel.distribution == Channel.Distribution.PRIORITY) {
                // Keep the per-output priority editor compact so RPM, direction, and delete
                // remain available in the same row.
                p2pcreate$button(graphics, rx + 164, y + 2, 16, 14,
                        Component.literal("P" + member.priority), 0xFF3D3530);
                p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.PRIORITY, rx + 164, y + 2, 16, 14));
            }
            p2pcreate$button(graphics, rx + 182, y + 2, 26, 14,
                    Component.translatable("p2pcreate.gui.rpm"), 0xFF3D3530);
            p2pcreate$button(graphics, rx + rw - 15, y + 2, 13, 14, Component.literal("×"), 0xFF6C3030);
            p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.ROLE, rx + 20, y + 2, 22, 14));
            p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.DIRECTION, rx + 128, y + 2, 34, 14));
            p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.RPM, rx + 182, y + 2, 26, 14));
            p2pcreate$actionHits.add(new ActionHit(memberIndex, ActionKind.DELETE, rx + rw - 15, y + 2, 13, 14));
        }
    }

    /** Mirrors the base screen's icon resolution: live pick-stack (with BE NBT) first, bound blockId fallback. */
    private ItemStack p2pcreate$endpointIcon(Member member) {
        var screen = (ChannelScreen) (Object) this;
        Level level = screen.getMinecraft().level;
        if (level != null && level.dimension().location().equals(member.dim)) {
            // When the clicked block is only a carrier (e.g. the tunnel) and the kinetic machine is
            // on an adjacent face, show what the endpoint actually drives instead of the carrier.
            KineticBlockEntity resolved = KineticEndpoints.firstKinetic(level, member);
            BlockPos target = resolved != null ? resolved.getBlockPos() : member.pos;
            BlockState state = level.getBlockState(target);
            Block block = state.getBlock();
            if (block != Blocks.AIR && block.asItem() != Items.AIR) {
                ItemStack pick = block.getCloneItemStack(state,
                        new BlockHitResult(Vec3.atCenterOf(target), member.face, target, false),
                        level, target, screen.getMinecraft().player);
                if (pick.isEmpty()) pick = new ItemStack(block.asItem());
                BlockEntity be = level.getBlockEntity(target);
                if (be != null) {
                    CompoundTag beTag = be.saveWithFullMetadata(level.registryAccess());
                    beTag.remove("id");
                    BlockItem.setBlockEntityData(pick, be.getType(), beTag);
                }
                return pick;
            }
        }
        if (member.blockId != null) {
            // BuiltInRegistries.BLOCK is defaulted: unknown ids return AIR rather than null.
            Block block = BuiltInRegistries.BLOCK.get(member.blockId);
            if (block != Blocks.AIR && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
        }
        return ItemStack.EMPTY;
    }

    /** Why this endpoint cannot participate in the bridge right now; null when it resolves fine. */
    private Component p2pcreate$endpointWarning(Member member, boolean output) {
        Level level = ((ChannelScreen) (Object) this).getMinecraft().level;
        if (level == null || !level.dimension().location().equals(member.dim))
            return Component.translatable("p2pcreate.gui.row.unloaded");
        KineticBlockEntity be = KineticEndpoints.firstKinetic(level, member);
        if (be == null) {
            boolean anyLoaded = false;
            for (var pos : KineticEndpoints.candidatePositions(member)) {
                if (level.isLoaded(pos)) { anyLoaded = true; break; }
            }
            return Component.translatable(anyLoaded ? "p2pcreate.gui.row.not_kinetic" : "p2pcreate.gui.row.unloaded");
        }
        if (!output && !be.hasNetwork()) return Component.translatable("p2pcreate.gui.row.no_network");
        if (!output && Math.abs(be.getSpeed()) < 0.001f) return Component.translatable("p2pcreate.gui.row.idle");
        return null;
    }

    private void p2pcreate$hideGenericControls() {
        rateDown.visible = rateUp.visible = speedDown.visible = speedUp.visible = maxRateBtn.visible = false;
        rateEdit.visible = speedEdit.visible = false;
    }

    private int p2pcreate$sectionHeight(Channel channel) {
        int inputs = 0;
        int outputs = 0;
        for (Member member : channel.members) {
            if (member.role == Role.INPUT) inputs++;
            else if (member.role == Role.OUTPUT) outputs++;
        }
        return (inputs + outputs) * 18;
    }

    private float p2pcreate$inputSupply(Member member) {
        KineticBlockEntity be = p2pcreate$resolveMember(member);
        if (be == null || !be.hasNetwork()) return -1;
        // Read the values Create syncs onto the block entity; client-side KineticNetwork objects
        // are phantoms whose currentCapacity is never populated.
        return Math.max(0, ((KineticBlockEntityAccessor) (Object) be).p2pcreate$getSyncedCapacity());
    }

    private float p2pcreate$outputDemand(Member member) {
        KineticBlockEntity be = p2pcreate$resolveMember(member);
        if (be == null) return -1;
        // An unpowered train has no network yet, which is exactly the case a wireless output
        // creates: read the synced stress and let the bridge form the network.
        return Math.max(0, ((KineticBlockEntityAccessor) (Object) be).p2pcreate$getSyncedStress());
    }

    /** Client mirror of the bridge's endpoint resolution (shared candidate order in KineticEndpoints). */
    @Nullable
    private KineticBlockEntity p2pcreate$resolveMember(Member member) {
        Level level = ((ChannelScreen) (Object) this).getMinecraft().level;
        if (level == null || !level.dimension().location().equals(member.dim)) return null;
        return KineticEndpoints.firstKinetic(level, member);
    }

    private String p2pcreate$formatStress(float value, boolean missing) {
        return missing ? "—" : p2pcreate$formatNumber(value) + " SU";
    }

    private String p2pcreate$formatNumber(float value) {
        if (!Float.isFinite(value)) return "—";
        if (value >= 1000000) return String.format("%.1fM", value / 1000000f);
        if (value >= 1000) return String.format("%.1fk", value / 1000f);
        return String.valueOf(Math.round(value));
    }

    private String p2pcreate$shortKey(Member member) {
        Level level = ((ChannelScreen) (Object) this).getMinecraft().level;
        boolean sameDim = level != null && level.dimension().location().equals(member.dim);
        String face = Component.translatable(
                "p2pcreate.gui.face." + member.face.name().toLowerCase(java.util.Locale.ROOT)).getString();
        String coords = member.pos.getX() + "," + member.pos.getY() + "," + member.pos.getZ() + "·" + face;
        return sameDim ? coords : member.dim.getPath() + "|" + coords;
    }

    private String p2pcreate$trimWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        int cut = value.length();
        while (cut > 1 && font.width(value.substring(0, cut - 1) + "…") > maxWidth) cut--;
        return value.substring(0, Math.max(1, cut - 1)) + "…";
    }

    private Component p2pcreate$reasonLabel(String reason) {
        // Fixed key set; unknown reasons fall back to the generic paused label instead of
        // leaking a server-provided string into a translation key.
        return switch (reason) {
            case "real_source" -> Component.translatable("p2pcreate.gui.reason.real_source");
            case "speed_conflict" -> Component.translatable("p2pcreate.gui.reason.speed_conflict");
            case "shared" -> Component.translatable("p2pcreate.gui.reason.shared");
            default -> Component.translatable("p2pcreate.gui.reason.paused");
        };
    }

    private Channel p2pcreate$selectedChannel() {
        for (Channel channel : channels) if (channel.id == selectedChannel) return channel;
        return null;
    }

    private static boolean p2pcreate$isStress(Channel channel) {
        return channel != null && channel.type != null && CompatCreate.STRESS_ID.equals(channel.type.id);
    }

    private static List<Member> p2pcreate$members(Channel channel, Role role) {
        List<Member> result = new ArrayList<>();
        for (Member member : channel.members) if (member.role == role) result.add(member);
        return result;
    }

    private static Member p2pcreate$member(Channel channel, int index) {
        return index >= 0 && index < channel.members.size() ? channel.members.get(index) : null;
    }

    private void p2pcreate$removeMember(Channel channel, int index) {
        Member member = p2pcreate$member(channel, index);
        if (member == null) return;
        ModMessages.sendToServer(new ChannelActionC2SPacket(channel.id,
                ChannelActionC2SPacket.Action.REMOVE_MEMBER, null, 0, member.key(), false, null, ""));
    }

    private void p2pcreate$button(GuiGraphics graphics, int x, int y, int width, int height,
            Component label, int color) {
        String text = label.getString();
        Font font = ((ChannelScreen) (Object) this).getMinecraft().font;
        graphics.fill(x, y, x + width, y + height, color);
        graphics.fill(x, y, x + width, y + 1, 0xFF6E625A);
        graphics.drawString(font, label, x + Math.max(2, (width - font.width(text)) / 2),
                y + 3, 0xFFE7E0D9, false);
    }

    private void p2pcreate$inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + 1, 0xFF6E625A);
        graphics.fill(x, y, x + 1, y + height, 0xFF6E625A);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF171717);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF171717);
    }

}
