package com.voidvault.listener;

import com.voidvault.gui.PagedVaultGUI;
import com.voidvault.gui.VaultGUI;
import com.voidvault.manager.VaultManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.logging.Logger;

/**
 * Listener for vault inventory interactions.
 * Handles click and drag events in vault inventories, enforcing restrictions on locked slots
 * and control bar slots, and routing button clicks to the appropriate handlers.
 */
public class VaultInventoryListener implements Listener {
    private final VaultManager vaultManager;
    private final Logger logger;
    
    /**
     * Constructor for VaultInventoryListener.
     *
     * @param vaultManager The vault manager for tracking open GUIs
     * @param logger       The logger for error logging
     */
    public VaultInventoryListener(VaultManager vaultManager, Logger logger) {
        this.vaultManager = vaultManager;
        this.logger = logger;
    }
    
    /**
     * Handle inventory click events in vault inventories.
     * Prevents clicks on locked slots in PAGED mode, prevents clicks on control bar slots,
     * handles button clicks, and allows normal storage interaction for unlocked slots.
     *
     * @param event The InventoryClickEvent
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        // Check if this is a vault inventory
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        
        // Check for SearchGUI first
        com.voidvault.gui.SearchGUI searchGUI = vaultManager.getOpenSearchGUI(player);
        if (searchGUI != null && event.getClickedInventory() != null && 
            event.getClickedInventory().equals(searchGUI.getInventory())) {
            searchGUI.handleClick(event);
            return;
        }
        
        // Get the open GUI for this player
        VaultGUI gui = vaultManager.getOpenGUI(player);
        if (gui == null) {
            return;
        }
        
        // Check if the clicked inventory is the vault inventory
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null || !clickedInventory.equals(gui.getInventory())) {
            // Allow clicks in player's own inventory
            return;
        }
        
        // Delegate to the GUI's click handler
        gui.handleClick(event);
    }
    
    /**
     * Handle inventory drag events in vault inventories.
     * Cancels drags that include locked slots or control bar slots.
     *
     * @param event The InventoryDragEvent
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        // Check if this is a vault inventory
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        
        // Get the open GUI for this player
        VaultGUI gui = vaultManager.getOpenGUI(player);
        if (gui == null) {
            return;
        }
        
        // Check if any of the dragged slots are in the vault inventory
        boolean affectsVault = false;
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < gui.getInventory().getSize()) {
                affectsVault = true;
                break;
            }
        }
        
        if (!affectsVault) {
            return;
        }
        
        // Delegate to the GUI's drag handler
        gui.handleDrag(event);
    }
    
    /**
     * Handle inventory close events for vault inventories.
     * Saves the vault data when the player closes the inventory.
     *
     * @param event The InventoryCloseEvent
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        
        // Check for SearchGUI first
        com.voidvault.gui.SearchGUI searchGUI = vaultManager.getOpenSearchGUI(player);
        if (searchGUI != null && event.getInventory().equals(searchGUI.getInventory())) {
            vaultManager.removeSearchGUI(player);
            searchGUI.handleClose(event);
            return;
        }
        
        // Check if this player has a vault open
        VaultGUI gui = vaultManager.getOpenGUI(player);
        if (gui == null) {
            return;
        }
        
        // Check if the closed inventory is the vault inventory
        if (!event.getInventory().equals(gui.getInventory())) {
            return;
        }
        
        // Close the vault (saves data and removes from tracking)
        vaultManager.closeVault(player).exceptionally(ex -> {
            logger.severe("Failed to close vault for " + player.getName() + ": " + ex.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
            return null;
        });
    }
}
