package com.voidvault.gui;

import com.voidvault.config.ConfigManager;
import com.voidvault.manager.PermissionManager;
import com.voidvault.model.VaultPage;
import com.voidvault.storage.DataCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * Paged mode vault GUI implementation.
 * Provides a fixed 54-slot inventory with 50 storage slots and 4 control bar
 * buttons.
 * Supports multiple pages, locked slots, and QoL features.
 */
public class PagedVaultGUI extends VaultGUI {
    // Control bar button slots
    private static final int SLOT_PREVIOUS_PAGE = 45;
    private static final int SLOT_SORT = 46;
    private static final int SLOT_QUICK_DEPOSIT = 47;
    private static final int SLOT_SEARCH = 48;
    private static final int SLOT_NEXT_PAGE = 53;

    // Storage slot positions (0-44, 49-52)
    private static final List<Integer> STORAGE_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            49, 50, 51, 52);

    // Control bar slots
    private static final Set<Integer> CONTROL_BAR_SLOTS = Set.of(
            SLOT_PREVIOUS_PAGE, SLOT_SORT, SLOT_QUICK_DEPOSIT, SLOT_SEARCH, SLOT_NEXT_PAGE);

    private int maxSlots;
    private int maxPages;
    private com.voidvault.manager.VaultManager vaultManager; // Optional, for page navigation
    private String searchQuery; // Active search filter

    /**
     * Private constructor for PagedVaultGUI.
     *
     * @param player            The player viewing the vault
     * @param page              The current page number
     * @param inventory         The created inventory
     * @param maxSlots          The maximum number of unlocked slots
     * @param maxPages          The maximum number of pages
     * @param configManager     Configuration manager
     * @param permissionManager Permission manager
     * @param dataCache         Data cache for vault data
     */
    private PagedVaultGUI(Player player, int page, Inventory inventory, int maxSlots, int maxPages,
            ConfigManager configManager, PermissionManager permissionManager,
            DataCache dataCache) {
        super(player, page, inventory, configManager, permissionManager, dataCache);
        this.maxSlots = maxSlots;
        this.maxPages = maxPages;
    }

    /**
     * Factory method to create a PagedVaultGUI.
     *
     * @param player            The player viewing the vault
     * @param page              The page number to open
     * @param configManager     Configuration manager
     * @param permissionManager Permission manager
     * @param dataCache         Data cache for vault data
     * @return A new PagedVaultGUI instance
     */
    public static PagedVaultGUI create(Player player, int page, ConfigManager configManager,
            PermissionManager permissionManager, DataCache dataCache) {
        // Get max slots and pages from permissions
        int maxSlots = permissionManager.getMaxSlots(player);
        int maxPages = permissionManager.getMaxPages(player);

        // Get title from config (MiniMessage Component)
        net.kyori.adventure.text.Component title = configManager.getPagedModeTitle(player.getName(), page, maxPages);

        // Create fixed 54-slot inventory
        Inventory inventory = Bukkit.createInventory(player, 54, title);

        // Create and return the GUI
        return new PagedVaultGUI(player, page, inventory, maxSlots, maxPages,
                configManager, permissionManager, dataCache);
    }

    /**
     * Update the inventory title to show search status.
     * Note: Inventory titles cannot be changed after creation in Bukkit,
     * so this is for future reference if using Paper's dynamic titles.
     */
    private void updateTitle() {
        // This would require Paper API's dynamic inventory titles
        // For now, we use chat messages to indicate search status
    }

    @Override
    public void render() {
        // Clear the inventory
        inventory.clear();

        // Get the current page data
        VaultPage currentPage = getCurrentPage();

        // Check if search is active
        boolean searchActive = searchQuery != null && !searchQuery.isEmpty();

        // First, render all storage slots with items or locked indicators
        int dataIndex = 0;
        for (int slot : STORAGE_SLOTS) {
            if (dataIndex < currentPage.getSize()) {
                if (isLockedSlot(slot)) {
                    // Display locked slot item
                    inventory.setItem(slot, configManager.getLockedSlotItem().toItemStack());
                } else {
                    // Display stored item or empty
                    ItemStack item = currentPage.getItem(dataIndex);
                    if (item != null && !item.getType().isAir()) {
                        // Apply search filter if active
                        if (searchActive && !matchesSearch(item)) {
                            // Show filtered item (grayed out) or hide completely
                            if (configManager.isSearchGrayoutEnabled()) {
                                inventory.setItem(slot, createFilteredItem());
                            }
                            // If grayout is disabled, leave slot empty (item is hidden)
                        } else {
                            // Item matches search or no search active - show normally
                            inventory.setItem(slot, item);
                        }
                    }
                }
                dataIndex++;
            }
        }

        // Then render control bar buttons ONLY in empty slots
        renderControlBar();
    }

    /**
     * Check if an item matches the current search query.
     * Supports multiple keywords separated by spaces (OR logic).
     */
    private boolean matchesSearch(ItemStack item) {
        if (searchQuery == null || searchQuery.isEmpty()) {
            return true;
        }

        // Split query into keywords (supports multiple keywords with OR logic)
        String[] keywords = searchQuery.split("\\s+");

        for (String keyword : keywords) {
            if (keyword.isEmpty())
                continue;

            String query = configManager.isSearchCaseSensitive() ? keyword : keyword.toLowerCase();
            String itemName = item.getType().name().replace("_", " ");
            if (!configManager.isSearchCaseSensitive()) {
                itemName = itemName.toLowerCase();
            }

            // Check material name
            if (itemName.contains(query)) {
                return true;
            }

            // If search mode is 'all', check display name and lore
            if (configManager.getSearchMode().equals("all")) {
                // Check display name if present
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    String displayName = item.getItemMeta().getDisplayName();
                    if (!configManager.isSearchCaseSensitive()) {
                        displayName = displayName.toLowerCase();
                    }
                    if (displayName.contains(query)) {
                        return true;
                    }
                }

                // Check lore if present
                if (item.hasItemMeta() && item.getItemMeta().hasLore()) {
                    for (String loreLine : item.getItemMeta().getLore()) {
                        String lore = configManager.isSearchCaseSensitive() ? loreLine : loreLine.toLowerCase();
                        if (lore.contains(query)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Create a filtered (grayed out) item placeholder.
     */
    private ItemStack createFilteredItem() {
        return configManager.getFilteredSlotItem().toItemStack();
    }

    /**
     * Check if a slot currently has a button in it.
     *
     * @param slot The slot to check
     * @return true if the slot has a button
     */
    private boolean isButtonSlot(int slot) {
        return CONTROL_BAR_SLOTS.contains(slot);
    }

    /**
     * Render the control bar buttons.
     * Always renders navigation buttons, overwriting any items in those slots.
     * This prevents items from being placed in control slots.
     */
    private void renderControlBar() {
        // Previous Page button
        if (page > 1) {
            inventory.setItem(SLOT_PREVIOUS_PAGE, configManager.getPreviousPageButton().toItemStack());
        } else {
            inventory.setItem(SLOT_PREVIOUS_PAGE, configManager.getFillerBarItem().toItemStack());
        }

        // Sort button (if enabled)
        if (configManager.isSortEnabled()) {
            inventory.setItem(SLOT_SORT, configManager.getSortButton().toItemStack());
        } else {
            inventory.setItem(SLOT_SORT, configManager.getFillerBarItem().toItemStack());
        }

        // Quick Deposit button (if enabled)
        if (configManager.isQuickDepositEnabled()) {
            inventory.setItem(SLOT_QUICK_DEPOSIT, configManager.getQuickDepositButton().toItemStack());
        } else {
            inventory.setItem(SLOT_QUICK_DEPOSIT, configManager.getFillerBarItem().toItemStack());
        }

        // Search button (if enabled)
        if (configManager.isSearchEnabled()) {
            inventory.setItem(SLOT_SEARCH, configManager.getSearchButton().toItemStack());
        } else {
            inventory.setItem(SLOT_SEARCH, configManager.getFillerBarItem().toItemStack());
        }

        // Next Page button
        if (page < maxPages) {
            inventory.setItem(SLOT_NEXT_PAGE, configManager.getNextPageButton().toItemStack());
        } else {
            inventory.setItem(SLOT_NEXT_PAGE, configManager.getFillerBarItem().toItemStack());
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();

        // If clicking outside the vault inventory, allow it
        if (slot < 0 || slot >= 54) {
            return;
        }

        // Check if clicking a control bar slot. We also guard against
        // clicking on a "filler bar" button placeholder so the inventory
        // never reports a no-op click on a disabled navigation slot.
        if (CONTROL_BAR_SLOTS.contains(slot)) {
            event.setCancelled(true);
            ItemStack clickedItem = inventory.getItem(slot);
            if (clickedItem == null || isFillerPlaceholder(clickedItem)) {
                // Disabled control-bar slot (e.g. Previous Page on page 1).
                // We have already cancelled the click — nothing else to do.
                return;
            }
            handleButtonClick(slot);
            return;
        }

        // Check if clicking a filtered placeholder item
        ItemStack clickedItem = inventory.getItem(slot);
        if (clickedItem != null && isFilteredPlaceholder(clickedItem)) {
            event.setCancelled(true);
            player.sendMessage("§c§oThis item is filtered by your search. Clear the filter to interact with it.");
            return;
        }

        // Check if clicking a locked slot
        if (isLockedSlot(slot)) {
            event.setCancelled(true);
            // Play sound feedback for locked slot
            if (event.getWhoClicked() instanceof Player p) {
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.0f);
            }
            return;
        }

        // Check if clicking a storage slot
        if (STORAGE_SLOTS.contains(slot)) {
            // Allow the interaction and mark data as dirty
            markDirty();
            return;
        }

        // Cancel any other clicks (shouldn't happen, but safety check)
        event.setCancelled(true);
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        // Check if any dragged slots are restricted
        for (int slot : event.getRawSlots()) {
            // Only check slots within the vault inventory
            if (slot >= 0 && slot < 54) {
                // Cancel if dragging to control bar or locked slot
                if (CONTROL_BAR_SLOTS.contains(slot) || isLockedSlot(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // If drag affects vault storage slots, mark as dirty
        boolean affectsVault = false;
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < 54 && STORAGE_SLOTS.contains(slot) && !isLockedSlot(slot)) {
                affectsVault = true;
                break;
            }
        }

        if (affectsVault) {
            markDirty();
        }
    }

    @Override
    public int getStorageSlotCount() {
        return Math.min(maxSlots, 50); // Max 50 storage slots in PAGED mode (slots 0-44, 49-52)
    }

    /**
     * Handle the Sort button click.
     * Re-renders the page after grouping items into logical categories
     * (weapons, tools, armour, …) and sorting each group by material name.
     * Empty trailing slots are normal — they are simply never written to.
     */
    private void handleSort() {
        saveInventoryToData();

        VaultPage currentPage = getCurrentPage();

        // Compact items: remove nulls and air, collect all valid items
        ItemStack[] items = new ItemStack[maxSlots];
        int writeIndex = 0;

        for (int i = 0; i < currentPage.getSize(); i++) {
            ItemStack item = currentPage.getItem(i);
            if (item != null && !item.getType().isAir()) {
                items[writeIndex++] = item;
            }
        }
        VaultPage compactedPage = new VaultPage(page, items);
        VaultPage sorted = compactedPage.sorted();
        vaultData.setPage(page, sorted);

        render();
        markDirty();

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f);
    }

    /**
     * Handle the Quick Deposit button click.
     * Deposits matching items from player inventory into existing stacks in vault.
     */
    private void handleQuickDeposit() {
        saveInventoryToData();

        VaultPage currentPage = getCurrentPage();
        int depositedCount = 0;

        // Get player's inventory
        ItemStack[] playerInv = player.getInventory().getContents();

        // For each item in vault, try to stack matching items from player inventory
        for (int i = 0; i < currentPage.getSize() && i < maxSlots; i++) {
            ItemStack vaultItem = currentPage.getItem(i);
            if (vaultItem == null || vaultItem.getType().isAir()) {
                continue;
            }

            // Find matching items in player inventory
            for (int j = 0; j < playerInv.length; j++) {
                ItemStack playerItem = playerInv[j];
                if (playerItem == null || playerItem.getType().isAir()) {
                    continue;
                }

                // Check if items match and can stack
                if (vaultItem.isSimilar(playerItem)) {
                    int maxStack = vaultItem.getMaxStackSize();
                    int vaultAmount = vaultItem.getAmount();
                    int playerAmount = playerItem.getAmount();

                    if (vaultAmount < maxStack) {
                        int canAdd = Math.min(maxStack - vaultAmount, playerAmount);
                        vaultItem.setAmount(vaultAmount + canAdd);

                        if (canAdd >= playerAmount) {
                            playerInv[j] = null;
                        } else {
                            playerItem.setAmount(playerAmount - canAdd);
                        }

                        depositedCount += canAdd;
                        currentPage.setItem(i, vaultItem);
                    }
                }
            }
        }

        // Update player inventory
        player.getInventory().setContents(playerInv);

        // Render and mark dirty
        render();
        markDirty();

        // Send feedback (messages will be sent via VaultManager if needed)
        if (depositedCount > 0) {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);
        } else {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.5f, 1.0f);
        }
    }

    /**
     * Handle the Search button click.
     * Initiates search mode via VaultManager.
     */
    private void handleSearch() {
        if (vaultManager != null) {
            vaultManager.initiateSearch(player, page);
        }
    }

    /**
     * Set the search query for filtering items.
     *
     * @param query The search query (null to clear)
     */
    public void setSearchQuery(String query) {
        this.searchQuery = query;
    }

    /**
     * Get the active search query.
     *
     * @return The search query, or null if no filter active
     */
    public String getSearchQuery() {
        return searchQuery;
    }

    /**
     * Check if a slot is locked based on player permissions.
     *
     * @param slot The inventory slot to check
     * @return true if the slot is locked, false otherwise
     */
    public boolean isLockedSlot(int slot) {
        // Only storage slots can be locked
        if (!STORAGE_SLOTS.contains(slot)) {
            return false;
        }

        // Convert inventory slot to data index
        int dataIndex = getDataIndexFromSlot(slot);

        // Check if this slot exceeds the player's permission
        return dataIndex >= maxSlots;
    }

    /**
     * Convert an inventory slot number to a data index.
     *
     * @param slot The inventory slot
     * @return The corresponding data index
     */
    private int getDataIndexFromSlot(int slot) {
        return STORAGE_SLOTS.indexOf(slot);
    }

    /**
     * Convert a data index to an inventory slot number.
     *
     * @param dataIndex The data index
     * @return The corresponding inventory slot
     */
    private int getSlotFromDataIndex(int dataIndex) {
        if (dataIndex >= 0 && dataIndex < STORAGE_SLOTS.size()) {
            return STORAGE_SLOTS.get(dataIndex);
        }
        return -1; // Invalid index
    }

    /**
     * Handle a button click in the control bar.
     *
     * @param slot The slot that was clicked
     */
    private void handleButtonClick(int slot) {
        switch (slot) {
            case SLOT_PREVIOUS_PAGE -> handlePreviousPage();
            case SLOT_SORT -> {
                if (configManager.isSortEnabled())
                    handleSort();
            }
            case SLOT_QUICK_DEPOSIT -> {
                if (configManager.isQuickDepositEnabled())
                    handleQuickDeposit();
            }
            case SLOT_SEARCH -> {
                if (configManager.isSearchEnabled())
                    handleSearch();
            }
            case SLOT_NEXT_PAGE -> handleNextPage();
        }
    }

    /**
     * Handle the Previous Page button click.
     * Validates page access permissions, saves current page, and navigates to the
     * previous page.
     */
    private void handlePreviousPage() {
        // Check if we can go to previous page
        if (page <= 1) {
            return;
        }

        int targetPage = page - 1;

        // Validate page access permission
        if (!hasPagePermission(targetPage)) {
            return;
        }

        // Navigate to the previous page
        navigateToPage(targetPage);
    }

    /**
     * Handle the Next Page button click.
     * Validates page access permissions, saves current page, and navigates to the
     * next page.
     */
    private void handleNextPage() {
        // Check if we can go to next page
        if (page >= maxPages) {
            return;
        }

        int targetPage = page + 1;

        // Validate page access permission
        if (!hasPagePermission(targetPage)) {
            return;
        }

        // Navigate to the next page
        navigateToPage(targetPage);
    }

    /**
     * Check if the player has permission to access a specific page.
     *
     * @param pageNumber The page number to check
     * @return true if the player can access the page, false otherwise
     */
    private boolean hasPagePermission(int pageNumber) {
        // Check if player has custom pages override
        if (vaultData.hasCustomPages()) {
            return pageNumber <= vaultData.customPages();
        }

        // Check permission-based access
        return pageNumber <= maxPages;
    }

    /**
     * Navigate to a different page.
     * Saves the current page, loads the new page, and re-renders the GUI.
     * Uses VaultManager to properly handle the page transition.
     *
     * @param targetPage The page number to navigate to
     */
    private void navigateToPage(int targetPage) {
        // Play page turn sound
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.0f);

        if (vaultManager != null) {
            // Use VaultManager for proper page navigation. The manager keeps
            // the player's existing top inventory alive across the swap so
            // the cursor and hotbar slot are preserved (preventing the
            // cursor from jumping back to the middle row on every page
            // change).
            vaultManager.navigateToPage(player, targetPage);
        } else {
            // Fallback: Save and close without navigation
            // This shouldn't happen in normal operation
            saveInventoryToData();
            player.closeInventory();
        }
    }

    /**
     * Switch this GUI to a different page number without re-creating the
     * underlying inventory. Used by
     * {@link com.voidvault.manager.VaultManager#navigateToPage} so the
     * player's hotbar slot and cursor item are preserved across page
     * navigation (instead of snapping back to the middle slot).
     *
     * @param targetPage The new page number (1-indexed).
     */
    public void setPage(int targetPage) {
        this.page = targetPage;
    }

    @Override
    public void saveInventoryToData() {
        // Get the current page data (original items)
        VaultPage currentPage = getCurrentPage();

        // Create a new array to store the inventory contents
        ItemStack[] contents = new ItemStack[maxSlots];

        // Check if search is active
        boolean searchActive = searchQuery != null && !searchQuery.isEmpty();

        // Copy items from inventory storage slots to the contents array
        int dataIndex = 0;
        for (int slot : STORAGE_SLOTS) {
            if (dataIndex < contents.length) {
                ItemStack inventoryItem = inventory.getItem(slot);

                // If search is active and this slot has a filtered placeholder,
                // restore the original item from the page data
                if (searchActive && inventoryItem != null && isFilteredPlaceholder(inventoryItem)) {
                    // Get the original item from the page data
                    contents[dataIndex] = currentPage.getItem(dataIndex);
                } else {
                    // Save the item as-is (could be modified by player)
                    contents[dataIndex] = inventoryItem;
                }
                dataIndex++;
            }
        }

        // Create a new VaultPage with the updated contents
        VaultPage updatedPage = new VaultPage(page, contents);

        // Update the vault data
        vaultData.setPage(page, updatedPage);

        // Mark as dirty
        markDirty();
    }

    /**
     * Check if an item is a filtered placeholder (gray glass pane).
     */
    private boolean isFilteredPlaceholder(ItemStack item) {
        if (item == null)
            return false;

        // Check if it matches the filtered slot item
        ItemStack filteredItem = configManager.getFilteredSlotItem().toItemStack();
        return item.getType() == filteredItem.getType() &&
                item.hasItemMeta() &&
                filteredItem.hasItemMeta() &&
                item.getItemMeta().getDisplayName().equals(filteredItem.getItemMeta().getDisplayName());
    }

    /**
     * Check if an item is the filler-bar placeholder (gray stained glass pane)
     * used to disable a control-bar slot such as "Previous Page" on page 1.
     */
    private boolean isFillerPlaceholder(ItemStack item) {
        if (item == null)
            return false;
        ItemStack filler = configManager.getFillerBarItem().toItemStack();
        return item.getType() == filler.getType();
    }

    /**
     * Get the maximum number of pages accessible to the player.
     *
     * @return The max pages
     */
    public int getMaxPages() {
        return maxPages;
    }

    /**
     * Get the maximum number of unlocked slots.
     *
     * @return The max slots
     */
    public int getMaxSlots() {
        return maxSlots;
    }

    /**
     * Set the VaultManager reference for page navigation.
     * This is called by VaultManager after creating the GUI.
     *
     * @param vaultManager The VaultManager instance
     */
    public void setVaultManager(com.voidvault.manager.VaultManager vaultManager) {
        this.vaultManager = vaultManager;
    }

    /**
     * Get the current page number.
     *
     * @return The page number (1-indexed)
     */
    public int getPage() {
        return page;
    }
}
