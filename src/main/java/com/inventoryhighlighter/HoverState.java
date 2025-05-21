package com.inventoryhighlighter;

import net.runelite.api.widgets.WidgetItem;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.awt.Point;

@Singleton
public class HoverState {
    private WidgetItem hoveredItem;
    private int hoveredItemId;
    private Rectangle hoveredBounds;
    private Point hoveredPosition; // Store the widget position instead of an index
    
    public WidgetItem getHoveredItem() {
        return hoveredItem;
    }
    
    public void setHoveredItem(WidgetItem hoveredItem) {
        this.hoveredItem = hoveredItem;
        if (hoveredItem != null) {
            this.hoveredBounds = hoveredItem.getCanvasBounds();
            if (hoveredBounds != null) {
                // Store the top-left position of the widget item as its identifier
                this.hoveredPosition = new Point((int)hoveredBounds.getX(), (int)hoveredBounds.getY());
            }
        } else {
            this.hoveredBounds = null;
            this.hoveredPosition = null;
        }
    }
    
    public int getHoveredItemId() {
        return hoveredItemId;
    }
    
    public void setHoveredItemId(int hoveredItemId) {
        this.hoveredItemId = hoveredItemId;
    }

    public Rectangle getHoveredBounds() {
        return hoveredBounds;
    }

    public void setHoveredBounds(Rectangle bounds) {
        this.hoveredBounds = bounds;
        if (bounds != null) {
            this.hoveredPosition = new Point((int)bounds.getX(), (int)bounds.getY());
        } else {
            this.hoveredPosition = null;
        }
    }
    
    /**
     * Get the hovered item position
     */
    public Point getHoveredPosition() {
        return hoveredPosition;
    }
    
    /**
     * Check if a specific widget item is being hovered
     */
    public boolean isItemHovered(WidgetItem item) {
        if (hoveredItem == null || item == null || hoveredPosition == null) {
            return false;
        }
        
        // Compare the canvas bounds positions to identify the specific item
        Rectangle itemBounds = item.getCanvasBounds();
        if (itemBounds == null) {
            return false;
        }
        
        Point itemPosition = new Point((int)itemBounds.getX(), (int)itemBounds.getY());
        return hoveredPosition.equals(itemPosition);
    }
    
    public void clear() {
        hoveredItem = null;
        hoveredItemId = -1;
        hoveredBounds = null;
        hoveredPosition = null;
    }
} 