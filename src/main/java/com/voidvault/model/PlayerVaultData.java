package com.voidvault.model;

import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable record representing a player's complete vault data.
 * Uses Java 21 record syntax for concise, thread-safe data carrier.
 * <p>
 * Cross-thread access requires a defensive copy via
 * {@link com.voidvault.model.VaultDataCloner#deepClone(PlayerVaultData)}.
 * The compact constructor shallow-copies the {@code pages} map and each
 * VaultPage deep-clones its ItemStack array, so once a PlayerVaultData has
 * been created, hand the SAME instance to a different thread only when you
 * are sure no further mutation will happen on the original thread; if you
 * cannot guarantee that, clone first.
 *
 * @param playerId     The unique identifier of the player
 * @param pages        Map of page numbers to VaultPage objects. Cross-thread
 *                     access requires a defensive copy via
 *                     {@link com.voidvault.model.VaultDataCloner#deepClone(PlayerVaultData)}.
 * @param customSlots  Custom slot override (0 = use permissions)
 * @param customPages  Custom page override (0 = use permissions)
 */
public record PlayerVaultData(
        UUID playerId,
        Map<Integer, VaultPage> pages,
        int customSlots,
        int customPages
) {
    /**
     * Compact constructor with validation and defensive copying.
     */
    public PlayerVaultData {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (pages == null) {
            pages = new ConcurrentHashMap<>();
        } else {
            // Create a new ConcurrentHashMap to ensure thread safety
            pages = new ConcurrentHashMap<>(pages);
        }
        if (customSlots < 0) {
            throw new IllegalArgumentException("Custom slots cannot be negative");
        }
        if (customPages < 0) {
            throw new IllegalArgumentException("Custom pages cannot be negative");
        }
    }

    /**
     * Creates a new PlayerVaultData with default values.
     *
     * @param playerId The player's UUID
     * @return A new PlayerVaultData instance with empty pages and no custom overrides
     */
    public static PlayerVaultData createEmpty(UUID playerId) {
        return new PlayerVaultData(playerId, new ConcurrentHashMap<>(), 0, 0);
    }

    /**
     * Retrieves a page from the vault, creating it if it doesn't exist.
     *
     * @param pageNumber The page number to retrieve (1-indexed)
     * @return The VaultPage for the specified page number
     */
    public VaultPage getPage(int pageNumber) {
        return pages.computeIfAbsent(pageNumber, p -> new VaultPage(p, new ItemStack[52]));
    }

    /**
     * Sets a page in the vault.
     *
     * @param pageNumber The page number to set
     * @param page       The VaultPage to store
     */
    public void setPage(int pageNumber, VaultPage page) {
        if (page == null) {
            pages.remove(pageNumber);
        } else {
            pages.put(pageNumber, page);
        }
    }

    /**
     * Checks if a specific page exists in the vault.
     *
     * @param pageNumber The page number to check
     * @return true if the page exists, false otherwise
     */
    public boolean hasPage(int pageNumber) {
        return pages.containsKey(pageNumber);
    }

    /**
     * Gets the number of pages currently stored in the vault.
     *
     * @return The count of stored pages
     */
    public int getStoredPageCount() {
        return pages.size();
    }

    /**
     * Checks if custom slots are configured.
     *
     * @return true if custom slots are set (> 0), false if using permissions
     */
    public boolean hasCustomSlots() {
        return customSlots > 0;
    }

    /**
     * Checks if custom pages are configured.
     *
     * @return true if custom pages are set (> 0), false if using permissions
     */
    public boolean hasCustomPages() {
        return customPages > 0;
    }

    /**
     * Creates a copy of this PlayerVaultData with updated custom slots.
     *
     * @param newCustomSlots The new custom slots value
     * @return A new PlayerVaultData instance with updated custom slots
     */
    public PlayerVaultData withCustomSlots(int newCustomSlots) {
        return new PlayerVaultData(playerId, pages, newCustomSlots, customPages);
    }

    /**
     * Creates a copy of this PlayerVaultData with updated custom pages.
     *
     * @param newCustomPages The new custom pages value
     * @return A new PlayerVaultData instance with updated custom pages
     */
    public PlayerVaultData withCustomPages(int newCustomPages) {
        return new PlayerVaultData(playerId, pages, customSlots, newCustomPages);
    }
}
