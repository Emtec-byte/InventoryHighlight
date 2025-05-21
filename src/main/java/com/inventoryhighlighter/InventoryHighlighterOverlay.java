package com.inventoryhighlighter;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import javax.inject.Inject;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import net.runelite.api.ItemComposition;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.Overlay;

@Slf4j
public class InventoryHighlighterOverlay extends WidgetItemOverlay
{
    private final Client client;
    private final InventoryHighlighterConfig config;
    private final ItemManager itemManager;
    private final HoverState hoverState;
    
    // Statistics tracking
    private long totalRenderCalls = 0;
    private long renderCallsPerSecond = 0;
    private long lastRenderCountTime = 0;
    private long renderCallsThisInterval = 0;
    
    // Throttled processing
    private int[] lastProcessedItems = new int[50]; // Cache the last 50 processed items
    private long lastFullProcessTime = 0;
    private static final long FULL_PROCESS_INTERVAL_MS = 5000; // Only process all items every 5 seconds
    private boolean forceFullProcess = false; // Flag to force full processing on next render cycle

    // Logging control - single log store for all message types
    private static final Map<String, Long> LOG_THROTTLE = new HashMap<>(); 
    private static final long LOG_INTERVAL_CACHE_HIT_MS = 3000; // 3 seconds for cache hits (changed from 30000)
    private static final long LOG_INTERVAL_RENDER_STATS_MS = 3000; // 3 seconds for render stats (changed from 10000)
    private static final long LOG_INTERVAL_GENERAL_MS = 3000; // 3 seconds for general logs (changed from 5000)
    
    // Image cache for highlights - this will be used for future features
    private final Cache<CacheKey, BufferedImage> imageCache;
    
    // Item pattern matching
    private final Map<String, Boolean> itemPatterns = new HashMap<>();
    private final Map<String, String> wildcardPrefixes = new HashMap<>();
    
    // Track the last config list
    private String lastConfigList = "";
    
    // Cache for item IDs that match our patterns
    private final Set<Integer> matchedItemIds = new HashSet<>();
    
    // Cache for item names we've already checked
    private final Map<Integer, Boolean> itemMatchCache = new HashMap<>();

    private long lastConfigCheck = 0;
    private static final int CONFIG_CHECK_INTERVAL_MS = 2000;
    
    private static final boolean DEBUG = false;
    
    /**
     * Log a message but throttle by message type to avoid spam
     */
    private void throttledLog(String type, String message, Object... args) {
        if (!DEBUG) return;
        
        long currentTime = System.currentTimeMillis();
        long interval = getLogThrottleInterval(type);
        
        long lastTime = LOG_THROTTLE.getOrDefault(type, 0L);
        if (currentTime - lastTime > interval) {
            log.info(message, args);
            LOG_THROTTLE.put(type, currentTime);
        }
    }
    
    private long getLogThrottleInterval(String type) {
        switch (type) {
            case "CACHE_HIT": return LOG_INTERVAL_CACHE_HIT_MS;
            case "RENDER_STATS": return LOG_INTERVAL_RENDER_STATS_MS;
            default: return LOG_INTERVAL_GENERAL_MS;
        }
    }

    /**
     * Check if an item matches a pattern
     */
    private boolean matchesPattern(String itemName, String pattern, boolean isWildcard) {
        if (isWildcard) {
            String prefix = wildcardPrefixes.get(pattern);
            return itemName.startsWith(prefix);
        }
        return itemName.contains(pattern);
    }
    
    /**
     * Cache key for buffered images
     */
    private static class CacheKey {
        private final int itemId;
        private final boolean isOutline;
        private final int alpha;
        private final int rgb;

        CacheKey(int itemId, boolean isOutline, Color color) {
            this.itemId = itemId;
            this.isOutline = isOutline;
            this.alpha = color.getAlpha();
            this.rgb = color.getRGB() & 0x00FFFFFF;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey key = (CacheKey) o;
            return itemId == key.itemId && 
                   isOutline == key.isOutline && 
                   alpha == key.alpha && 
                   rgb == key.rgb;
        }

        @Override
        public int hashCode() {
            int result = itemId;
            result = 31 * result + (isOutline ? 1 : 0);
            result = 31 * result + alpha;
            result = 31 * result + rgb;
            return result;
        }
    }

    @Inject
    private InventoryHighlighterOverlay(Client client, InventoryHighlighterPlugin plugin, 
            InventoryHighlighterConfig config, ItemManager itemManager, HoverState hoverState)
    {
        this.client = client;
        this.config = config;
        this.itemManager = itemManager;
        this.hoverState = hoverState;

        log.debug("Initializing main overlay");
        
        this.imageCache = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100)
            .build();
            
        // Initialize lastProcessedItems to -1
        Arrays.fill(lastProcessedItems, -1);

        showOnInventory();
        showOnBank();
        setPriority(Overlay.PRIORITY_LOW);  // Lower priority than hover overlay
        
        log.debug("Hover-only enabled: {}", config.hoverOnly());
        log.debug("Item list: '{}'", config.itemList());
        log.debug("Main overlay initialized");

        // Process initial config
        updateHighlightPatterns(config.itemList());
    }
    
    /**
     * Update game tick counter - trigger full processing on game tick
     */
    public void onGameTick() {
        forceFullProcess = true;
    }
    
    /**
     * Check if an item matches any of our patterns and cache the result
     */
    private boolean isItemMatch(int itemId) {
        // Return cached match result if available
        Boolean cachedMatch = itemMatchCache.get(itemId);
        if (cachedMatch != null) {
            return cachedMatch;
        }
        
        if (itemManager == null) {
            itemMatchCache.put(itemId, false);
            return false;
        }
        
        try {
            ItemComposition itemDef = itemManager.getItemComposition(itemId);
            if (itemDef == null || itemDef.getName() == null) {
                itemMatchCache.put(itemId, false);
                return false;
            }
            
            String itemName = Text.standardize(itemDef.getName()).toLowerCase();
            
            if (itemPatterns.isEmpty()) {
                itemMatchCache.put(itemId, false);
                return false;
            }
            
            for (Map.Entry<String, Boolean> entry : itemPatterns.entrySet()) {
                String pattern = entry.getKey();
                boolean isWildcard = entry.getValue();
                
                if (matchesPattern(itemName, pattern, isWildcard)) {
                    throttledLog("PATTERN_MATCH", "MAIN: Item {} ({}) matches pattern '{}'", itemId, itemName, pattern);
                    itemMatchCache.put(itemId, true);
                    matchedItemIds.add(itemId);
                    return true;
                }
            }
            
            itemMatchCache.put(itemId, false);
        } catch (Exception e) {
            log.warn("Error matching item {}: {}", itemId, e.getMessage());
            itemMatchCache.put(itemId, false);
        }
        
        return false;
    }
    //This currently is not used here but it may be used in the future
    private String getItemName(int itemId) {
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp != null ? comp.getName() : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    /**
     * Update pattern cache from the config list
     */
    private void updateHighlightPatterns(String configList) {
        // Skip if same config
        if (configList != null && configList.equals(lastConfigList)) {
            return;
        }
        
        log.debug("Updating highlight patterns from: '{}'", configList);
        lastConfigList = configList;
        
        // Clear existing pattern data
        itemPatterns.clear();
        wildcardPrefixes.clear();
        matchedItemIds.clear();
        itemMatchCache.clear();
        
        if (configList == null || configList.isEmpty()) {
            log.debug("No patterns to process (empty config)");
            return;
        }
        
        // Split the comma-separated list
        String[] patterns = configList.toLowerCase().split(",");
        log.debug("Processing {} patterns", patterns.length);
        
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (!pattern.isEmpty()) {
                boolean isWildcard = pattern.endsWith("*");
                String lowercasePattern = pattern.toLowerCase();
                itemPatterns.put(lowercasePattern, isWildcard);
                
                if (isWildcard) {
                    String prefix = lowercasePattern.substring(0, lowercasePattern.length() - 1);
                    wildcardPrefixes.put(lowercasePattern, prefix);
                    log.debug("Added wildcard pattern: '{}' with prefix: '{}'", lowercasePattern, prefix);
                } else {
                    log.debug("Added exact pattern: '{}'", lowercasePattern);
                }
            }
        }
        
        log.debug("Pattern update complete. {} patterns configured ({} wildcards)",
                itemPatterns.size(), wildcardPrefixes.size());
    }
    
    /**
     * Check if we've already processed this item recently
     */
    private boolean wasProcessedRecently(int itemId) {
        for (int id : lastProcessedItems) {
            if (id == itemId) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Mark an item as processed recently
     */
    private void markAsProcessed(int itemId) {
        // Shift the array to make room for the new item
        System.arraycopy(lastProcessedItems, 0, lastProcessedItems, 1, lastProcessedItems.length - 1);
        lastProcessedItems[0] = itemId;
    }

    // Add these fields for non-hover throttling
    private static final long NON_HOVER_THROTTLE_MS = 100; // Process non-hover items less frequently
    private long lastNonHoverRenderTime = 0;
    private int processedItemCount = 0;
    private static final int MAX_ITEMS_PER_FRAME = 10; // Process at most 10 items per frame when not in hover mode
    
    /**
     * Update render statistics (extracted to a method for cleaner code)
     */
    private void updateRenderStats(long currentTime) {
        // Track render statistics once per second
        if (currentTime - lastRenderCountTime > 1000) {
            renderCallsPerSecond = renderCallsThisInterval;
            renderCallsThisInterval = 0;
            lastRenderCountTime = currentTime;
            
            // Log render stats at reduced frequency
            throttledLog("RENDER_STATS", "RENDER STATS: {} calls/sec, {} total calls", 
                renderCallsPerSecond, totalRenderCalls);
        }
    }
    
    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem itemWidget) {
        // FIRST: Quick check if we should skip rendering entirely
        if (client.getGameState() != GameState.LOGGED_IN || 
            itemWidget == null || 
            itemManager == null) {
            return;
        }

        // SECOND: Handle hover-only mode specially - this is the fast path
        if (config.hoverOnly()) {
            // Use the position-based isItemHovered check instead of comparing IDs
            if (!hoverState.isItemHovered(itemWidget)) {
                return;
            }
            
            // Process the hovered item
            totalRenderCalls++;
            renderCallsThisInterval++;
            updateRenderStats(System.currentTimeMillis());
            drawHighlight(graphics, itemId, itemWidget);
            return;
        }

        // THIRD: Non-hover mode with optimizations
        
        // Fast cache hit - if we know this item doesn't match patterns, skip it
        if (!matchedItemIds.contains(itemId) && itemMatchCache.containsKey(itemId) && !itemMatchCache.get(itemId)) {
            return;
        }
        
        // Track render stats
        totalRenderCalls++;
        renderCallsThisInterval++;
        long currentTime = System.currentTimeMillis();
        updateRenderStats(currentTime);
        
        // Throttle processing in non-hover mode by limiting items processed per frame
        // and adding a small delay between batches
        boolean shouldProcess = false;
        
        // Always process known matching items (items we've already determined match our patterns)
        if (matchedItemIds.contains(itemId)) {
            shouldProcess = true;
        } 
        // For other items, batch processing
        else {
            // Only begin processing a new batch after the throttle time has elapsed
            if (currentTime - lastNonHoverRenderTime >= NON_HOVER_THROTTLE_MS) {
                lastNonHoverRenderTime = currentTime;
                processedItemCount = 0;
            }
            
            // Limit how many items we process in a single frame
            if (processedItemCount < MAX_ITEMS_PER_FRAME) {
                shouldProcess = true;
                processedItemCount++;
            }
        }
        
        // Periodically check if patterns need updating
        if (currentTime - lastConfigCheck > CONFIG_CHECK_INTERVAL_MS) {
            String configList = config.itemList();
            if (configList != null && !configList.equals(lastConfigList)) {
                updateHighlightPatterns(Text.standardize(configList));
                forceFullProcess = true;
            }
            lastConfigCheck = currentTime;
        }
        
        // Force processing on key events
        if (forceFullProcess || (currentTime - lastFullProcessTime > FULL_PROCESS_INTERVAL_MS)) {
            shouldProcess = true;
            if (forceFullProcess) {
                lastFullProcessTime = currentTime;
                forceFullProcess = false;
            }
        } else if (!shouldProcess && !wasProcessedRecently(itemId)) {
            // We haven't checked this item recently
            shouldProcess = true;
            markAsProcessed(itemId);
        }
        
        // Only proceed to the expensive processing step if needed
        if (shouldProcess && shouldHighlightItem(itemId)) {
            drawHighlight(graphics, itemId, itemWidget);
        }
    }

    public boolean shouldHighlightItem(int itemId) {
        if (itemPatterns.isEmpty()) {
            return false;
        }
        
        if (matchedItemIds.contains(itemId)) {
            return true;
        }
        
        if (!itemMatchCache.containsKey(itemId)) {
            boolean matches = isItemMatch(itemId);
            return matches;
        }
        
        return itemMatchCache.get(itemId);
    }

    private void drawHighlight(Graphics2D graphics, int itemId, WidgetItem itemWidget) {
        // Skip if widget is null
        if (itemWidget == null) {
            return;
        }
        
        // Get item bounds
        Rectangle bounds = itemWidget.getCanvasBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return;
        }
        
        // Save original graphics state
        Color originalColor = graphics.getColor();
        Stroke originalStroke = graphics.getStroke();
        
        try {
            // Get colors from config
            Color outlineColor = config.outlineColor();
            Color fillColor = config.fillColor();
            int thickness = config.outlineThickness();

            if (config.spriteOnly()) {
                // Draw sprite highlight
                if (!config.outlineOnly()) {
                    BufferedImage image = itemManager.getImage(itemId);
                    if (image != null) {
                        // Use ItemManager's fillImage method to preserve item shape
                        BufferedImage filledImage = ImageUtil.fillImage(image, fillColor);
                        graphics.drawImage(filledImage, (int) bounds.getX(), (int) bounds.getY(), null);
                    }
                }
                
                // Draw sprite outline
                BufferedImage outline = itemManager.getItemOutline(itemId, thickness, outlineColor);
                if (outline != null) {
                    graphics.drawImage(outline, (int) bounds.getX(), (int) bounds.getY(), null);
                }
            } else {
                // Draw clickbox highlight
                if (!config.outlineOnly()) {
                    // Use a semi-transparent fill for the clickbox
                    Color fillWithAlpha = new Color(
                        fillColor.getRed(),
                        fillColor.getGreen(),
                        fillColor.getBlue(),
                        Math.min(fillColor.getAlpha(), 130)
                    );
                    graphics.setColor(fillWithAlpha);
                    graphics.fill(bounds);
                }
                
                graphics.setColor(outlineColor);
                graphics.setStroke(new BasicStroke(thickness));
                graphics.draw(bounds);
            }
        } catch (Exception e) {
            log.error("Error drawing highlight: {}", e.getMessage(), e);
        } finally {
            // Restore original graphics state
            graphics.setColor(originalColor);
            graphics.setStroke(originalStroke);
        }
    }
    
    /**
     * Get current render statistics
     */
    public String getRenderStats() {
        return String.format("Render calls: %d/sec, Total: %d", 
            renderCallsPerSecond, totalRenderCalls);
    }
    
    /**
     * Clear caches
     */
    public void clearCache() {
        itemMatchCache.clear();
        matchedItemIds.clear();
        Arrays.fill(lastProcessedItems, -1);
        forceFullProcess = true;
    }
}
