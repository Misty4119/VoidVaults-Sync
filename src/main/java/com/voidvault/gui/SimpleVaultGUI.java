package com.voidvault.gui;

import com.voidvault.config.ConfigManager;
import com.voidvault.manager.PermissionManager;
import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultPage;
import com.voidvault.storage.DataCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Simple mode vault GUI implementation.
 * Provides a single-page vault with dynamic size based on permissions (9-54 slots).
 * No control bar buttons or paging functionality.
 */
public class SimpleVaultGUI extends VaultGUI {
    private final int maxSlots;
    
    /**
     * Private constructor for SimpleVaultGUI.
     *
     * @param player            The player viewing the vault
     * @param inventory         The created inventory
     * @param maxSlots          The maximum number of slots
     * @param configManager     Configuration manager
     * @param permissionManager Permission manager
     * @param dataCache         Data cache for vault data
     */
    private SimpleVaultGUI(Player player, Inventory inventory, int maxSlots,
                           ConfigManager configManager, PermissionManager permissionManager,
                           DataCache dataCache) {
        super(player, 1, inventory, configManager, permissionManager, dataCache);
        this.maxSlots = maxSlots;
    }
    
    /**
     * Factory method to create a SimpleVaultGUI with inventory.
     *
     * @param player            The player viewing the vault
     * @param configManager     Configuration manager
     * @param permissionManager Permission manager
     * @param dataCache         Data cache for vault data
     * @return A new SimpleVaultGUI instance
     */
    public static SimpleVaultGUI create(Player player, ConfigManager configManager,
                                        PermissionManager permissionManager, DataCache dataCache) {
        // Calculate inventory size based on permissions
        int maxSlots = calculateInventorySize(player, permissionManager);
        
        // Get title from config (MiniMessage Component)
        net.kyori.adventure.text.Component title = configManager.getSimpleModeTitle(player.getName());
        
        // Create the inventory
        int rows = maxSlots / 9;
        Inventory inventory = Bukkit.createInventory(player, rows * 9, title);
        
        // Create and return the GUI
        return new SimpleVaultGUI(player, inventory, maxSlots, configManager, permissionManager, dataCache);
    }
    
    /**
     * Calculate the inventory size based on player permissions.
     * Returns the size in rows (9, 18, 27, 36, 45, or 54 slots).
     *
     * @param player            The player to check
     * @param permissionManager The permission manager
     * @return The inventory size in slots
     */
    private static int calculateInventorySize(Player player, PermissionManager permissionManager) {
        // Get max slots from permission manager
        int slots = permissionManager.getMaxSlots(player);
        
        // Ensure it's a valid inventory size (multiple of 9, between 9 and 54)
        if (slots < 9) {
            slots = 9;
        } else if (slots > 54) {
            slots = 54;
        } else {
            // Round to nearest multiple of 9
            slots = ((slots + 8) / 9) * 9;
        }
        
        return slots;
    }
    
    @Override
    public void render() {
        // Get the current page data (always page 1 for SIMPLE mode)
        VaultPage currentPage = getCurrentPage();
        
        // Clear the inventory first
        inventory.clear();
        
        // Load items from vault data into the inventory
        for (int slot = 0; slot < maxSlots && slot < currentPage.getSize(); slot++) {
            ItemStack item = currentPage.getItem(slot);
            if (item != null) {
                inventory.setItem(slot, item);
            }
        }
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        // In SIMPLE mode, all clicks within the vault inventory are allowed
        // The player can freely add, remove, and move items
        
        int slot = event.getRawSlot();
        
        // If clicking outside the vault inventory, allow it
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        
        // Mark data as dirty since the inventory is being modified
        markDirty();
        
        // Play subtle click sound for feedback
        if (event.getWhoClicked() instanceof Player p && event.getCurrentItem() != null) {
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.3f, 1.5f);
        }
    }
    
    @Override
    public void handleDrag(InventoryDragEvent event) {
        // In SIMPLE mode, all drag operations are allowed
        // No restrictions on dragging items
        
        // Check if any slots in the vault are affected
        boolean affectsVault = false;
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < inventory.getSize()) {
                affectsVault = true;
                break;
            }
        }
        
        // If the vault is affected, mark data as dirty
        if (affectsVault) {
            markDirty();
        }
    }
    
    @Override
    public int getStorageSlotCount() {
        return maxSlots;
    }
    
    @Override
    public void saveInventoryToData() {
        // Create a new array to store the inventory contents (use maxSlots size)
        ItemStack[] contents = new ItemStack[maxSlots];
        
        // Copy items from inventory to the contents array
        for (int slot = 0; slot < maxSlots; slot++) {
            contents[slot] = inventory.getItem(slot);
        }
        
        // Create a new VaultPage with the updated contents
        VaultPage updatedPage = new VaultPage(page, contents);
        
        // Update the vault data
        vaultData.setPage(page, updatedPage);
        
        // Mark as dirty
        markDirty();
    }
    
    /**
     * Get the maximum number of slots in this GUI.
     *
     * @return The max slots
     */
    public int getMaxSlots() {
        return maxSlots;
    }
}
