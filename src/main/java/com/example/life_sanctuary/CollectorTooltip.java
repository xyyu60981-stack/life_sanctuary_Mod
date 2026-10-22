package com.example.life_sanctuary;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.component.ItemContainerContents;

/** Immutable item-component snapshot; contains no client classes or live inventory references. */
public record CollectorTooltip(ItemContainerContents contents, CollectorInventory.Summary summary)
        implements TooltipComponent {}
