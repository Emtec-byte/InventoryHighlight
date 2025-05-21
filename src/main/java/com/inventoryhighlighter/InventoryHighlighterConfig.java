package com.inventoryhighlighter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Alpha;
import java.awt.Color;

@ConfigGroup("inventoryhighlighter")
public interface InventoryHighlighterConfig extends Config
{
    @ConfigSection(
        name = "Experimental",
        description = "Experimental features that may affect performance",
        position = 100,
        closedByDefault = true
    )
    String experimentalSection = "experimental";
    
    @ConfigItem(
        keyName = "itemList",
        name = "Items to Highlight",
        description = "List of items to highlight (comma-separated)"
    )
    default String itemList()
    {
        return "";
    }

    @Alpha
    @ConfigItem(
        keyName = "outlineColor",
        name = "Outline Color",
        description = "The color of the outline",
        position = 1
    )
    default Color outlineColor()
    {
        return Color.RED;
    }

    @Alpha
    @ConfigItem(
        keyName = "fillColor",
        name = "Fill Color",
        description = "The color of the fill",
        position = 2
    )
    default Color fillColor()
    {
        return new Color(255, 0, 0, 50);
    }

    @ConfigItem(
        keyName = "outlineOnly",
        name = "Outline Only",
        description = "Only show outline instead of filled highlight"
    )
    default boolean outlineOnly()
    {
        return false;
    }

    @ConfigItem(
        keyName = "outlineThickness",
        name = "Outline Thickness",
        description = "The thickness of the outline in pixels (doesn't work with sprite outlines)",
        position = 4
    )
    default int outlineThickness()
    {
        return 2;
    }

    @ConfigItem(
        keyName = "spriteOnly",
        name = "Sprite Only",
        description = "Highlight only the item sprite instead of the full clickbox"
    )
    default boolean spriteOnly()
    {
        return true;
    }

    @ConfigItem(
        keyName = "presets",
        name = "Presets",
        description = "Store preset strings here for easy swapping. Use comma to separate items. Add * for wildcards (e.g. 'angler*').",
        position = 99
    )
    default String presets()
    {
        return "";
    }

    @ConfigItem(
        keyName = "hoverOnly",
        name = "Hover Only",
        description = "Only highlight items when hovering over them (recommended for performance)",
        section = "experimental"
    )
    default boolean hoverOnly()
    {
        return true;
    }
}
