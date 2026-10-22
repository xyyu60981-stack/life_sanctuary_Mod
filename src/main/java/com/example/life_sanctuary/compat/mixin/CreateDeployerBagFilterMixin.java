package com.example.life_sanctuary.compat.mixin;

import com.example.life_sanctuary.LifeSanctuaryMod;
import com.example.life_sanctuary.PollenBagItem;
import com.example.life_sanctuary.SporeBagItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create 6.0.10: restrict only the predicate used to pull items from mounted storage.
 * Already-held bags (including manual/hopper supply) and all non-bag items retain
 * Create's behavior. Its original predicate still decides whether a configured
 * filter matches; we must never force a positive result here.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour", remap = false)
public abstract class CreateDeployerBagFilterMixin {
    @Inject(method = "lambda$tryGrabbingItem$0", at = @At("HEAD"), cancellable = true, require = 1)
    private static void lifeSanctuary$requireBagFilter(@Coerce Object filter, @Coerce Object context,
                                                      ItemStack stack, CallbackInfoReturnable<Boolean> result) {
        if (stack.isEmpty()) return;
        boolean emptyBag = stack.is(LifeSanctuaryMod.EMPTY_SPORE_BAG.get())
                || stack.is(LifeSanctuaryMod.EMPTY_POLLEN_BAG.get())
                || (stack.is(LifeSanctuaryMod.SPORE_BAG.get()) && SporeBagItem.species(stack) == null)
                || (stack.is(LifeSanctuaryMod.POLLEN_BAG.get()) && PollenBagItem.species(stack) == null);
        if (!emptyBag) return;
        // Fail closed for bags if another mod replaces Create's wrapper with an unknown type.
        if (!(filter instanceof CreateFilterAccess access) || access.lifeSanctuary$isEmpty()) {
            result.setReturnValue(false);
            return;
        }
        ItemStack configured = access.lifeSanctuary$item();
        if (configured.is(stack.getItem()) && SporeBagItem.species(configured) == null) return;
        if (configured.getItem() instanceof CreateListFilterAccess list) {
            // Only direct whitelist entries count as an explicit choice. No recursive scans,
            // attribute matches or blacklist complements can implicitly enable bag collection.
            for (ItemStack entry : list.lifeSanctuary$allowedItems(configured)) {
                if (entry.is(stack.getItem()) && SporeBagItem.species(entry) == null) return;
            }
        }
        result.setReturnValue(false);
    }
}
