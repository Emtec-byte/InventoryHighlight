package com.inventoryhighlighter;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.game.ItemManager;
import javax.inject.Inject;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import java.awt.BasicStroke;
import java.awt.Stroke;
import net.runelite.client.util.Text;
import java.util.HashSet;
import java.util.Set;
import net.runelite.client.util.ImageUtil;

//commented out imports as they currently arent used, but may be used in the future
//import java.util.Map;
//import java.util.HashMap;
//import net.runelite.client.ui.overlay.OverlayLayer;
//import net.runelite.client.ui.overlay.OverlayPosition;


@Slf4j
public class HoverHighlightOverlay extends WidgetItemOverlay {
    private final Client client;
    private final InventoryHighlighterConfig config;
    private final ItemManager itemManager;
    private final HoverState hoverState;
    
    // Cache matched item IDs to avoid repeated checks
    private final Set<Integer> knownMatchingItems = new HashSet<>();
    private final Set<Integer> knownNonMatchingItems = new HashSet<>();
    
    // Last checked item list from config
    private String lastConfigList = "";
    
    // Logging control
    private long lastLogTime = 0;
    private static final int LOG_INTERVAL_MS = 60000; // Log every 60 seconds
    private long renderCallCount = 0;
    
    @Inject
    private HoverHighlightOverlay(Client client, InventoryHighlighterConfig config, 
            ItemManager itemManager, HoverState hoverState) {
        this.client = client;
        this.config = config;
        this.itemManager = itemManager;
        this.hoverState = hoverState;
        
        // Configure the overlay for maximum visibility
        showOnInventory();
        showOnBank();
        
        // Set absolute highest priority
        setPriority(Overlay.PRIORITY_HIGHEST);
        
        log.debug("Hover overlay initialized");
    }
    
    /**
     * Check if an item should be highlighted based on its name and the configured patterns
     */
    private boolean shouldHighlight(int itemId) {
        // First check our caches for quick response
        if (knownMatchingItems.contains(itemId)) {
            return true;
        }
        
        if (knownNonMatchingItems.contains(itemId)) {
            return false;
        }
        
        // Get the item name
        ItemComposition itemComp;
        try {
            itemComp = itemManager.getItemComposition(itemId);
            if (itemComp == null) {
                knownNonMatchingItems.add(itemId);
                return false;
            }
        } catch (Exception e) {
            log.debug("Error getting item composition for ID {}: {}", itemId, e.getMessage());
            knownNonMatchingItems.add(itemId);
            return false;
        }
        
        // Standardize name for matching
        String itemName = Text.standardize(itemComp.getName()).toLowerCase();
        
        // Get patterns from config
        String configList = config.itemList();
        if (configList == null || configList.isEmpty()) {
            knownNonMatchingItems.add(itemId);
            return false;
        }
        
        // Only update our cache if config has changed
        if (!configList.equals(lastConfigList)) {
            knownMatchingItems.clear();
            knownNonMatchingItems.clear();
            lastConfigList = configList;
        }
        
        // Check against all patterns
        String[] patterns = Text.standardize(configList).toLowerCase().split(",");
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            
            boolean isWildcard = pattern.endsWith("*");
            if (isWildcard) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (itemName.startsWith(prefix)) {
                    knownMatchingItems.add(itemId);
                    return true;
                }
            } else if (itemName.contains(pattern)) {
                knownMatchingItems.add(itemId);
                return true;
            }
        }
        
        // No match found
        knownNonMatchingItems.add(itemId);
        return false;
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        renderCallCount++;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > LOG_INTERVAL_MS) {
            log.debug("HOVER OVERLAY: processed {} render calls in the last {} seconds", 
                renderCallCount, LOG_INTERVAL_MS / 1000);
            renderCallCount = 0;
            lastLogTime = currentTime;
        }
        
        // Only process if hover-only mode is enabled
        if (!config.hoverOnly()) {
            return;
        }
        
        // Check for null objects
        if (widgetItem == null || graphics == null || client == null) {
            return;
        }
        
        try {
            // Get mouse position
            Point mousePos = client.getMouseCanvasPosition();
            if (mousePos == null) {
                return;
            }
            
            // Check if mouse is hovering over this item
            Rectangle bounds = widgetItem.getCanvasBounds();
            if (bounds == null || !bounds.contains(mousePos.getX(), mousePos.getY())) {
                return;
            }
            
            // Check if this item should be highlighted based on its name
            if (!shouldHighlight(itemId)) {
                return;
            }
            
            // Update hover state when mouse is over a highlightable item
            hoverState.setHoveredItem(widgetItem);
            hoverState.setHoveredItemId(itemId);
            hoverState.setHoveredBounds(bounds);
            
            // Log a debug message to show which specific item is being highlighted
            log.debug("Setting hover state for item: {} at position ({},{})", 
                getItemName(itemId), (int)bounds.getX(), (int)bounds.getY());
            
            // Get colors from config
            Color outlineColor = config.outlineColor();
            Color fillColor = config.fillColor();
            int thickness = config.outlineThickness();
            
            // Save original graphics state
            Color originalColor = graphics.getColor();
            Stroke originalStroke = graphics.getStroke();
            
            try {
                // Draw the highlight based on configuration
                if (config.spriteOnly()) {
                    // Fill first (if not outline only)
                    if (!config.outlineOnly()) {
                        BufferedImage image = itemManager.getImage(itemId);
                        if (image != null) {
                            // Use ImageUtil.fillImage to create a filled version that preserves the sprite shape
                            BufferedImage filledImage = ImageUtil.fillImage(image, fillColor);
                            graphics.drawImage(filledImage, (int)bounds.getX(), (int)bounds.getY(), null);
                        }
                    }
                    
                    // Then draw the outline
                    BufferedImage outline = itemManager.getItemOutline(itemId, thickness, outlineColor);
                    if (outline != null) {
                        graphics.drawImage(outline, (int)bounds.getX(), (int)bounds.getY(), null);
                    }
                } else {
                    // Standard clickbox highlight
                    if (!config.outlineOnly()) {
                        // Semi-transparent fill
                        Color fillWithAlpha = new Color(
                            fillColor.getRed(),
                            fillColor.getGreen(),
                            fillColor.getBlue(),
                            Math.min(fillColor.getAlpha(), 130)
                        );
                        graphics.setColor(fillWithAlpha);
                        graphics.fill(bounds);
                    }
                    
                    // Draw the outline
                    graphics.setColor(outlineColor);
                    graphics.setStroke(new BasicStroke(thickness));
                    graphics.draw(bounds);
                }
                
                log.debug("Highlighted hover item: {} (ID: {})", getItemName(itemId), itemId);
            } finally {
                // Restore original graphics state
                graphics.setColor(originalColor);
                graphics.setStroke(originalStroke);
            }
        } catch (Exception e) {
            log.error("Error highlighting hovered item: {}", e.getMessage(), e);
        }
    }
    
    private String getItemName(int itemId) {
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp != null ? comp.getName() : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    public void clearHoveredItem() {
        hoverState.clear();
    }
    
    /**
     * Clear the item match cache when the config changes
     */
    public void clearItemCache() {
        knownMatchingItems.clear();
        knownNonMatchingItems.clear();
        lastConfigList = "";
    }
    
    /**
     * Get the number of known matching items in cache 
     */
    public int getKnownMatchingItemCount() {
        return knownMatchingItems.size();
    }
} 