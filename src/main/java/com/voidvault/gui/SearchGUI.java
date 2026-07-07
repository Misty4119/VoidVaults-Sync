package com.voidvault.gui;

import com.voidvault.config.ConfigManager;
import com.voidvault.config.ConfigManager.SearchGuiButton;
import com.voidvault.config.ConfigManager.SearchGuiRole;
import com.voidvault.config.MessageManager;
import com.voidvault.manager.VaultManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Search GUI for filtering vault items.
 *
 * <p>Provides a user-friendly overlay with predefined quick-search options,
 * a custom chat-prompt option, a clear-filter button and a cancel button.</p>
 *
 * <p>All visible text — the inventory title, every button's display name /
 * lore and every chat prompt — is loaded from {@link ConfigManager} and
 * {@link MessageManager} so authors can rebrand the overlay without
 * touching this class. The class itself holds no {@code §}-style colour
 * strings.</p>
 */
public class SearchGUI {

    private final Player player;
    private final int page;
    private final VaultManager vaultManager;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final Inventory inventory;

    /**
     * Slot → button lookup, rebuilt on every {@link #setupInventory()} call so
     * the GUI always reflects the latest config.yml contents.
     */
    private final Map<Integer, SearchGuiButton> buttonsBySlot = new HashMap<>();

    public SearchGUI(Player player,
                     int page,
                     VaultManager vaultManager,
                     ConfigManager configManager,
                     MessageManager messageManager) {
        this.player = player;
        this.page = page;
        this.vaultManager = vaultManager;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.inventory = Bukkit.createInventory(null, 27, configManager.getSearchGuiTitle());
    }

    /**
     * Open the search GUI for the player.
     */
    public void open() {
        setupInventory();
        player.openInventory(inventory);
    }

    /**
     * Set up the search GUI inventory with buttons.
     *
     * <p>Reads every button definition from {@link ConfigManager}, applies the
     * filler glass pane to all unused slots, then renders the buttons at
     * their configured slots. Anything missing from config.yml falls back to
     * the defaults read from the bundled {@code config.yml} template via
     * {@link SearchGuiButton#defaultQuickOptions(FileConfiguration)} and the
     * role-aware fallback in {@link ConfigManager#loadSearchGui()}.</p>
     */
    private void setupInventory() {
        // Rebuild the slot map every time so config reloads are reflected
        // immediately on next open.
        buttonsBySlot.clear();

        // 1. Fill every slot with the configured filler glass pane so the
        //    overlay looks uniform before the buttons paint over their slots.
        ItemStack filler = configManager.getSearchGuiFiller().toItemStack();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        // 2. Render the seven quick-search buttons.
        for (SearchGuiButton button : configManager.getSearchGuiQuickOptions()) {
            placeButton(button);
        }

        // 3. Render the three role buttons. Each one is configurable but the
        //    role is fixed, so we can fall back to defaults safely.
        placeButton(configManager.getSearchGuiCustomButton());
        placeButton(configManager.getSearchGuiClearButton());
        placeButton(configManager.getSearchGuiCancelButton());
    }

    /**
     * Place a configured button into its target slot and remember the mapping
     * so {@link #handleClick(InventoryClickEvent)} can dispatch on it.
     */
    private void placeButton(SearchGuiButton button) {
        if (button == null) {
            return;
        }
        inventory.setItem(button.slot(), button.toItemStack());
        // Last write wins. Authors can override slots freely; if two buttons
        // happen to share a slot we surface the second one's behaviour.
        buttonsBySlot.put(button.slot(), button);
    }

    /**
     * Handle click events in the search GUI.
     */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);

        int slot = event.getSlot();
        SearchGuiButton button = buttonsBySlot.get(slot);
        if (button == null) {
            return;
        }

        switch (button.role()) {
            case QUICK_OPTION  -> processSearch(button.query());
            case CUSTOM_SEARCH -> promptCustomSearch();
            case CLEAR_FILTER  -> clearFilter();
            case CANCEL        -> cancel();
        }
    }

    /**
     * Handle inventory close event.
     */
    public void handleClose(InventoryCloseEvent event) {
        // Clean up if needed
    }

    /**
     * Prompt player to type custom search in chat.
     */
    private void promptCustomSearch() {
        player.closeInventory();
        vaultManager.getSearchManager().startSearch(player, page);
        messageManager.send(player, "vault.search-prompt-raw");
    }

    /**
     * Process the search query.
     */
    private void processSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            cancel();
            return;
        }

        // Set search query in manager
        vaultManager.getSearchManager().startSearch(player, page);
        vaultManager.getSearchManager().setSearchQuery(player, query.trim());

        // Close GUI and reopen vault with filter
        player.closeInventory();
        messageManager.send(player, "vault.search-starting",
                Map.of("query", query.trim()));
        vaultManager.openVault(player, page);
    }

    /**
     * Clear the search filter.
     */
    private void clearFilter() {
        vaultManager.getSearchManager().clearSearch(player);
        player.closeInventory();
        messageManager.send(player, "vault.search-cleared");
        vaultManager.openVault(player, page);
    }

    /**
     * Cancel and return to vault.
     */
    private void cancel() {
        player.closeInventory();
        vaultManager.openVault(player, page);
    }

    /**
     * Get the inventory.
     */
    public Inventory getInventory() {
        return inventory;
    }
}