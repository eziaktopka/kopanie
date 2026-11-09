package pl.autokopanie.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.autokopanie.AutoKopanie;

/** Lapie napisy wyswietlane jako title / subtitle na srodku ekranu. */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "setTitle", at = @At("HEAD"))
    private void autokopanie$title(Text title, CallbackInfo ci) {
        AutoKopanie.onText(title);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void autokopanie$subtitle(Text subtitle, CallbackInfo ci) {
        AutoKopanie.onText(subtitle);
    }
}
