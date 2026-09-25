package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.packagePort.PackagePortScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.widget.IconButton;
import dev.distantstock.item.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep Create's native Frogport 2x9 inventory screen, but hide controls that are meaningless for
 * system-managed diagnostic/cache addresses.
 */
@Mixin(PackagePortScreen.class)
public abstract class PackagePortScreenMixin {
    @Shadow private EditBox addressBox;
    @Shadow private IconButton dontAcceptPackages;
    @Shadow private IconButton acceptPackages;
    @Shadow private ItemStack icon;

    @Inject(method = "init", at = @At("TAIL"))
    private void distantstock$hideManagedRoutingControls(CallbackInfo ci) {
        distantstock$applyManagedUi();
    }

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void distantstock$keepManagedRoutingControlsHidden(CallbackInfo ci) {
        distantstock$applyManagedUi();
    }

    private void distantstock$applyManagedUi() {
        if (icon == null || (icon.getItem() != ModItems.DIAGNOSTIC_FROGPORT.get()
                && icon.getItem() != ModItems.CACHE_FROGPORT.get())) {
            return;
        }
        if (addressBox != null) {
            addressBox.visible = false;
            addressBox.active = false;
            addressBox.setEditable(false);
        }
        if (acceptPackages != null) {
            acceptPackages.visible = false;
            acceptPackages.active = false;
        }
        if (dontAcceptPackages != null) {
            dontAcceptPackages.visible = false;
            dontAcceptPackages.active = false;
        }
    }

    /**
     * Diagnostic Frogports have no user-configurable interception address. Create draws the little
     * edit-pencil directly from renderBg rather than as a widget, so hiding the EditBox alone still
     * leaves a misleading button-looking icon in the title strip. Suppress only that texture for
     * the diagnostic Frogport and leave the rest of Create's Package Port artwork untouched.
     */
    @Redirect(method = "renderBg",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/gui/AllGuiTextures;render(Lnet/minecraft/client/gui/GuiGraphics;II)V"))
    private void distantstock$hideDiagnosticAddressPencil(AllGuiTextures texture, GuiGraphics graphics,
                                                           int x, int y) {
        if (distantstock$isDiagnostic() && texture == AllGuiTextures.FROGPORT_EDIT_NAME) {
            return;
        }
        texture.render(graphics, x, y);
    }

    /** The address tooltip belongs to the same removed control; do not leave an invisible hotspot. */
    @Redirect(method = "renderBg",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/EditBox;isHovered()Z"))
    private boolean distantstock$hideDiagnosticAddressTooltip(EditBox box) {
        return !distantstock$isDiagnostic() && box.isHovered();
    }

    private boolean distantstock$isDiagnostic() {
        return icon != null && icon.getItem() == ModItems.DIAGNOSTIC_FROGPORT.get();
    }
}
