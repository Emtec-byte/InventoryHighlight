package com.inventoryhighlighter;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import java.awt.Rectangle;
import net.runelite.api.Point;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.api.InventoryID;
import net.runelite.client.callback.ClientThread;

//Commented out imports as they currently arent used, but may be used in the future
//import net.runelite.api.widgets.WidgetItem;
//import net.runelite.api.ItemContainer;
//import net.runelite.api.widgets.WidgetInfo;
//import net.runelite.api.MenuEntry;
//import net.runelite.api.events.MenuEntryAdded;
//import net.runelite.api.widgets.Widget;
//import net.runelite.api.widgets.ComponentID;


@Slf4j
@PluginDescriptor(
    name = "Inventory Highlighter",
    description = "Highlights specified items in your inventory",
    tags = {"inventory", "highlight", "items"}
)
public class InventoryHighlighterPlugin extends Plugin
{
    @Inject
    private Client client;
    //This was used for a feature that was removed but may be used in the future if i can get it to work
    @Inject
    private ClientThread clientThread;
    
    @Inject
    private OverlayManager overlayManager;

    @Inject
    private InventoryHighlighterOverlay overlay;

    @Inject
    private HoverHighlightOverlay hoverOverlay;

    @Inject
    private HoverState hoverState;
    
    @Inject
    private InventoryHighlighterConfig config;
    
    @Inject
    private ItemManager itemManager;
    
    // Track configuration to detect changes
    private boolean lastHoverOnlyValue = true;
    private String lastItemListValue = "";
    
    // Last mouse position for optimization
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    
    // Performance monitoring
    private int tickCounter = 0;
    private static final int PERF_LOG_INTERVAL = 100; // Log approximately once per minute (100 ticks ≈ 60 seconds)
    private int lastKnownMatchingItems = 0;

    @Override
    protected void startUp()
    {
        log.info("InventoryHighlighter started");
        
        // Track initial config values
        lastHoverOnlyValue = config.hoverOnly();
        lastItemListValue = config.itemList();
        
        // Clear hover state
        hoverState.clear();
        
        // Always add main overlay
        overlayManager.add(overlay);
        
        // Only add hover overlay when needed
        if (config.hoverOnly()) {
            overlayManager.add(hoverOverlay);
        }
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        overlayManager.remove(hoverOverlay);
        hoverState.clear();
        
        log.info("InventoryHighlighter stopped");
    }

    @Provides
    InventoryHighlighterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(InventoryHighlighterConfig.class);
    }
    
    @Subscribe
    public void onClientTick(ClientTick event) {
        if (!config.hoverOnly()) {
            return;
        }
        
        // Only check mouse movement if mouse has actually moved
        Point mousePos = client.getMouseCanvasPosition();
        if (mousePos == null) {
            return;
        }
        
        int currentMouseX = mousePos.getX();
        int currentMouseY = mousePos.getY();
        
        // Skip if mouse hasn't moved
        if (currentMouseX == lastMouseX && currentMouseY == lastMouseY) {
            return;
        }
        
        // Update last mouse position
        lastMouseX = currentMouseX;
        lastMouseY = currentMouseY;
        
        // If mouse is not over current hovered bounds, clear hover state
        Rectangle currentHover = hoverState.getHoveredBounds();
        if (currentHover != null && !currentHover.contains(currentMouseX, currentMouseY)) {
            hoverState.clear();
            if (hoverOverlay != null) {
                hoverOverlay.clearHoveredItem();
            }
        }
        
        // Don't process further checks on every tick - only when mouse moves
        // This is handled directly in the HoverHighlightOverlay class
    }
    
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Only process relevant containers - I tried to use non deprecated containers but it wasnt working
        if (event.getContainerId() != InventoryID.INVENTORY.getId() && 
            event.getContainerId() != InventoryID.BANK.getId()) {
            return;
        }
        
        // Clear hover state when inventory or bank changes
        hoverState.clear();
        if (hoverOverlay != null) {
            hoverOverlay.clearItemCache();
        }
            
        // Clear main overlay cache for container changes
        if (overlay != null) {
            overlay.clearCache();
        }
    }
    
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        // Clear hover state when a widget loads (like opening bank or inventory)
        hoverState.clear();
        if (hoverOverlay != null) {
            hoverOverlay.clearHoveredItem();
        }
        
        // Clear cache when widgets change
        if (overlay != null) {
            overlay.clearCache();
        }
    }
    //currently this is not used here but it may be used in the future
    private String getItemName(int itemId) {
        if (itemManager == null) return "Unknown";
        
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp != null ? comp.getName() : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        // Notify overlay of the game tick
        if (overlay != null) {
            overlay.onGameTick();
        }
        
        // Check for config changes
        checkConfigChanges();
        
        // Log performance statistics periodically
        tickCounter++;
        if (tickCounter % PERF_LOG_INTERVAL == 0) {
            logPerformanceStats();
            tickCounter = 0;
        }
    }
    
    /**
     * Log performance statistics
     */
    private void logPerformanceStats() {
        StringBuilder stats = new StringBuilder();
        
        if (overlay != null) {
            stats.append(overlay.getRenderStats());
        }
        
        if (hoverOverlay != null) {
            int matchingItems = hoverOverlay.getKnownMatchingItemCount();
            if (matchingItems > 0 || matchingItems != lastKnownMatchingItems) {
                stats.append(", Matching items: ").append(matchingItems);
                lastKnownMatchingItems = matchingItems;
            }
        }
        
        // Add hover mode info to help understand performance characteristics
        stats.append(", Hover-only mode: ").append(config.hoverOnly());
        
        log.debug("PERFORMANCE: {}", stats.toString());
    }
    
    private void checkConfigChanges() {
        // Handle hover-only mode changes
        boolean currentHoverOnly = config.hoverOnly();
        if (currentHoverOnly != lastHoverOnlyValue) {
            log.debug("Hover-only mode changed to: {}", currentHoverOnly);
            
            if (currentHoverOnly) {
                // Add hover overlay when switching to hover-only mode
                overlayManager.add(hoverOverlay);
            } else {
                // Remove hover overlay when disabling hover-only mode
                overlayManager.remove(hoverOverlay);
                hoverState.clear();
            }
            
            // Clear caches on mode switch
            if (overlay != null) {
                overlay.clearCache();
            }
            
            lastHoverOnlyValue = currentHoverOnly;
        }
        
        // Track item list changes
        String currentItemList = config.itemList();
        if (!currentItemList.equals(lastItemListValue)) {
            log.debug("Item list changed to: '{}'", currentItemList);
            lastItemListValue = currentItemList;
            
            // Clear the hover overlay's item cache
            if (hoverOverlay != null) {
                hoverOverlay.clearItemCache();
            }
            
            // Clear the main overlay's cache
            if (overlay != null) {
                overlay.clearCache();
            }
        }
    }
}
