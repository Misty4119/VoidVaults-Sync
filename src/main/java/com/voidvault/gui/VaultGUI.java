package com.voidvault.gui;

import com.voidvault.config.ConfigManager;
import com.voidvault.manager.PermissionManager;
import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultPage;
import com.voidvault.storage.DataCache;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Abstract base class for all vault GUI implementations.
 * Provides common functionality and defines the contract for GUI rendering and interaction handling.
 */
public abstract class VaultGUI {
    protected final Player player;
    protected int page;
    protected final Inventory inventory;
    protected final ConfigManager configManager;
    protected final PermissionManager permissionManager;
    protected final DataCache dataCache;
    protected final PlayerVaultData vaultData;

    /**
     * Constructor for VaultGUI base class.
     *
     * @param player            The player viewing the vault
     * @param page              The current page number (1-indexed)
     * @param inventory         The Bukkit inventory instance
     * @param configManager     Configuration manager
     * @param permissionManager Permission manager
     * @param dataCache         Data cache for vault data
     */
    protected VaultGUI(Player player, int page, Inventory inventory,
                       ConfigManager configManager, PermissionManager permissionManager,
                       DataCache dataCache) {
        this.player = player;
        this.page = page;
        this.inventory = inventory;
        this.configManager = configManager;
        this.permissionManager = permissionManager;
        this.dataCache = dataCache;
        this.vaultData = dataCache.get(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("Vault data not loaded for player " + player.getName()));
    }
    
    /**
     * Render the GUI contents.
     * This method should populate the inventory with items based on the vault data and permissions.
     */
    public abstract void render();
    
    /**
     * Handle a click event in the vault inventory.
     *
     * @param event The inventory click event
     */
    public abstract void handleClick(InventoryClickEvent event);
    
    /**
     * Handle a drag event in the vault inventory.
     *
     * @param event The inventory drag event
     */
    public abstract void handleDrag(InventoryDragEvent event);
    
    /**
     * Get the number of storage slots available in this GUI.
     *
     * @return The count of storage slots
     */
    public abstract int getStorageSlotCount();
    
    /**
     * Get the player viewing this GUI.
     *
     * @return The player
     */
    public Player getPlayer() {
        return player;
    }
    
    /**
     * Get the current page number.
     *
     * @return The page number (1-indexed)
     */
    public int getPage() {
        return page;
    }
    
    /**
     * Get the Bukkit inventory instance.
     *
     * @return The inventory
     */
    public Inventory getInventory() {
        return inventory;
    }
    
    /**
     * Get the vault data for the player.
     *
     * @return The player's vault data
     */
    protected PlayerVaultData getVaultData() {
        return vaultData;
    }
    
    /**
     * Get the current page's data.
     *
     * @return The VaultPage for the current page
     */
    protected VaultPage getCurrentPage() {
        return vaultData.getPage(page);
    }
    
    /**
     * Save the current inventory contents back to the vault data.
     * This method should be called when the GUI is closed or when changes need to be persisted.
     */
    public void saveInventoryToData() {
        // This will be implemented by subclasses based on their specific slot layouts
    }
    
    /**
     * Mark the player's data as dirty in the cache.
     * This ensures the data will be saved during the next auto-save or on disconnect.
     */
    protected void markDirty() {
        dataCache.markDirty(player.getUniqueId());
    }
}
