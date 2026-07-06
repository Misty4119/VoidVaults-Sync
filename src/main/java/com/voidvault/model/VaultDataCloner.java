package com.voidvault.model;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-thread boundary deep-clone helper for vault data.
 * <p>
 * PlayerVaultData is an immutable record but every ItemStack it carries is a
 * mutable Bukkit object. Once a snapshot crosses a thread boundary (e.g. the
 * main thread hands a payload to an async storage save, or the storage layer
 * returns a payload to the subscriber thread that must later invalidate the
 * local cache), a follow-up mutation on the original ItemStack would corrupt
 * the in-flight copy. VaultDataCloner produces a defensive deep copy that
 * owns its own ItemStack / ItemMeta instances.
 *
 * <h2>Clone semantics</h2>
 * <ul>
 *     <li>{@link ItemStack#clone()} on Bukkit 1.20.5+ is a deep copy of the
 *         stack itself, including enchantments, custom attributes and most
 *         item meta fields. PersistentDataContainer remains a shallow copy
 *         (the underlying map is shared between the original and the clone)
 *         so callers that mutate PDC tags should still treat the clone as
 *         read-only with respect to that map.</li>
 *     <li>{@link ItemMeta#clone()} deep-copies the meta (name, lore, flags,
 *         attribute modifiers, …) and the persistent container.</li>
 *     <li>Pages are rebuilt with a fresh {@code ItemStack[]} array so that
 *         the new page does not alias the source array.</li>
 * </ul>
 *
 * <h2>Why a static helper instead of a record method</h2>
 * The {@link VaultPage} compact constructor already performs an in-place
 * shallow copy of the array (so the record stays cheap to build). Cross-
 * thread boundaries happen far less often than record creation, so doing
 * the expensive ItemStack clone only there keeps the hot path lean.
 */
public final class VaultDataCloner {

    private VaultDataCloner() {}

    /**
     * Deep-clone every ItemStack inside the supplied vault snapshot.
     * Custom slots / custom pages / playerId are value types and are simply
     * forwarded to the new record.
     *
     * @param src the source data; may be {@code null}, in which case {@code null} is returned
     * @return a structurally equal but mutually independent copy of {@code src}
     */
    public static PlayerVaultData deepClone(PlayerVaultData src) {
        if (src == null) {
            return null;
        }
        Map<Integer, VaultPage> clonedPages = new LinkedHashMap<>(src.pages().size());
        for (Map.Entry<Integer, VaultPage> entry : src.pages().entrySet()) {
            clonedPages.put(entry.getKey(), clonePage(entry.getValue()));
        }
        return new PlayerVaultData(src.playerId(), clonedPages, src.customSlots(), src.customPages());
    }

    /**
     * Deep-clone a single page. The returned VaultPage owns its own
     * ItemStack[]; mutating the source array after this call does not
     * affect the clone.
     *
     * @param page the source page; may be {@code null}
     * @return a deep copy, or {@code null} when {@code page} is {@code null}
     */
    public static VaultPage clonePage(VaultPage page) {
        if (page == null) {
            return null;
        }
        ItemStack[] originalContents = page.contents();
        ItemStack[] clonedContents = new ItemStack[originalContents.length];
        for (int i = 0; i < originalContents.length; i++) {
            clonedContents[i] = cloneItem(originalContents[i]);
        }
        return new VaultPage(page.pageNumber(), clonedContents);
    }

    /**
     * Deep-clone a single ItemStack. The returned stack has its own
     * {@link ItemMeta} instance; callers that mutate meta on the original
     * will not see the change reflected in the clone.
     *
     * @param item the source ItemStack; may be {@code null} or air
     * @return a defensive copy, or {@code null} when {@code item} is null/air
     */
    public static ItemStack cloneItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemStack copy = item.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            copy.setItemMeta(meta.clone());
        }
        return copy;
    }
}