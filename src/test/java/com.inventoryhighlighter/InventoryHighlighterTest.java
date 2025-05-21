package com.inventoryhighlighter;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class InventoryHighlighterTest {
    public static void main(String[] args) {
        try {
            ExternalPluginManager.loadBuiltin(InventoryHighlighterPlugin.class);
            RuneLite.main(args);
        } catch (Exception e) {
            System.err.println("Failed to start RuneLite: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}