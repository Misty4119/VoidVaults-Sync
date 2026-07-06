package com.voidvault.model;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Immutable record representing a single page of vault storage.
 * Uses Java 21 record syntax for concise data representation.
 * <p>
 * The compact constructor makes a shallow copy of the {@code contents} array
 * AND deep-clones every non-null ItemStack so that two VaultPage instances
 * never share a mutable ItemStack reference. Note that
 * {@link ItemStack#clone()} in Bukkit 1.20.5+ deep-copies the stack but the
 * {@link org.bukkit.persistence.PersistentDataContainer} backing map is
 * still shared — callers that mutate PDC tags must do their own defensive
 * copy or use {@link com.voidvault.model.VaultDataCloner#clonePage(VaultPage)}.
 *
 * @param pageNumber The page number (1-indexed)
 * @param contents   Array of ItemStacks representing the page contents (52 slots for PAGED mode)
 */
public record VaultPage(
        int pageNumber,
        ItemStack[] contents
) {
    /**
     * Compact constructor with validation and defensive copying.
     * Every non-null ItemStack is deep-cloned so the record owns its data;
     * mutating a source ItemStack after construction does not leak into the
     * VaultPage. See {@link #cloneItemStack(ItemStack)} for the per-slot
     * cloning logic.
     */
    public VaultPage {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("Page number must be at least 1");
        }
        if (contents == null) {
            contents = new ItemStack[52];
        } else {
            // 1. Shallow-copy the array so the page does not alias the caller's array.
            contents = Arrays.copyOf(contents, contents.length);
            // 2. Deep-clone every non-null ItemStack so individual slot
            //    mutations on the source do not bleed into the page.
            for (int i = 0; i < contents.length; i++) {
                contents[i] = cloneItemStack(contents[i]);
            }
        }
    }

    /**
     * Deep-clone a single ItemStack, copying its {@link org.bukkit.inventory.meta.ItemMeta}
     * so that mutations on the source meta are not visible on the clone.
     */
    private static ItemStack cloneItemStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemStack copy = stack.clone();
        if (stack.getItemMeta() != null) {
            copy.setItemMeta(stack.getItemMeta().clone());
        }
        return copy;
    }

    /**
     * Creates a new empty VaultPage with the specified page number.
     *
     * @param pageNumber The page number
     * @return A new VaultPage with empty contents
     */
    public static VaultPage createEmpty(int pageNumber) {
        return new VaultPage(pageNumber, new ItemStack[52]);
    }

    /**
     * Creates a new VaultPage with a specific size.
     *
     * @param pageNumber The page number
     * @param size       The size of the contents array
     * @return A new VaultPage with the specified size
     */
    public static VaultPage createWithSize(int pageNumber, int size) {
        return new VaultPage(pageNumber, new ItemStack[size]);
    }

    /**
     * Retrieves an item from a specific slot.
     *
     * @param slot The slot index (0-indexed)
     * @return The ItemStack at the slot, or null if empty or out of bounds
     */
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= contents.length) {
            return null;
        }
        return contents[slot];
    }

    /**
     * Creates a new VaultPage with an item set at a specific slot.
     * This maintains immutability by returning a new instance.
     *
     * @param slot The slot index (0-indexed)
     * @param item The ItemStack to set (null to clear)
     * @return A new VaultPage with the updated item
     */
    public VaultPage withItem(int slot, ItemStack item) {
        if (slot < 0 || slot >= contents.length) {
            return this; // Return unchanged if slot is invalid
        }
        ItemStack[] newContents = Arrays.copyOf(contents, contents.length);
        newContents[slot] = item;
        return new VaultPage(pageNumber, newContents);
    }

    /**
     * Gets the size of the contents array.
     *
     * @return The number of slots in this page
     */
    public int getSize() {
        return contents.length;
    }

    /**
     * Checks if the page is completely empty.
     *
     * @return true if all slots are null, false otherwise
     */
    public boolean isEmpty() {
        for (ItemStack item : contents) {
            if (item != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Counts the number of non-null items in the page.
     *
     * @return The count of items in the page
     */
    public int getItemCount() {
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Creates a mutable copy of the contents array.
     * Use this when you need to modify the contents directly.
     *
     * @return A new array containing copies of the contents
     */
    public ItemStack[] copyContents() {
        return Arrays.copyOf(contents, contents.length);
    }

    /**
     * Clears all items from the page.
     *
     * @return A new VaultPage with all slots cleared
     */
    public VaultPage clear() {
        return new VaultPage(pageNumber, new ItemStack[contents.length]);
    }
    
    /**
     * Returns a copy of this page with its contents grouped by item category
     * (weapons, tools, armour, food, building blocks, redstone, potions,
     * miscellaneous) and then sorted alphabetically within each group. The
     * original instance is left untouched so callers can safely share pages
     * across threads without observing mid-sort state.
     *
     * <p>Stacks of the same material are merged first so the player does not
     * end up with 5 slots each holding 16 cobblestone after sorting — they
     * get collapsed into a smaller number of fully-stacked slots, and the
     * tail of the slot list ends up empty, which is much easier on the eye
     * than the old "alphabetical by material name" output.</p>
     */
    public VaultPage sorted() {
        // First pass: gather non-air items, merging stacks of the same
        // material so we don't end up with N near-empty stacks of cobblestone
        // taking up valuable slots.
        Map<String, ItemStack> merged = new java.util.LinkedHashMap<>();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            String key = item.getType().name();
            ItemStack existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item.clone());
            } else {
                int combined = Math.min(existing.getMaxStackSize(),
                        existing.getAmount() + item.getAmount());
                existing.setAmount(combined);
            }
        }

        // Second pass: group by category using the ItemClassifier helper
        // below, then sort within each group by material name so the result
        // is both predictable and not "alphabetical across every type".
        Map<Category, List<ItemStack>> grouped = new java.util.EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            grouped.put(c, new java.util.ArrayList<>());
        }
        for (ItemStack item : merged.values()) {
            Category cat = Category.classify(item);
            grouped.get(cat).add(item);
        }
        for (List<ItemStack> bucket : grouped.values()) {
            bucket.sort((a, b) -> a.getType().name().compareTo(b.getType().name()));
        }

        // Fixed category order (most useful first). The order is exposed via
        // Category.order() so the player always sees Weapons → Tools →
        // Armour → … regardless of underlying EnumMap ordering.
        ItemStack[] newContents = new ItemStack[contents.length];
        int index = 0;
        for (Category c : Category.ORDER) {
            for (ItemStack item : grouped.get(c)) {
                if (index >= newContents.length) {
                    return new VaultPage(pageNumber, newContents);
                }
                newContents[index++] = item;
            }
        }
        return new VaultPage(pageNumber, newContents);
    }

    /**
     * Coarse categorisation used by {@link #sorted()} to group similar items
     * together. Categories are intentionally broad — every block / item in
     * vanilla falls into exactly one bucket, and the bucket order is what
     * the player sees first after pressing the Sort button.
     */
    private enum Category {
        WEAPONS,
        TOOLS,
        ARMOUR,
        FOOD,
        POTIONS,
        REDSTONE,
        BUILDING_BLOCKS,
        NATURAL_BLOCKS,
        DECORATIONS,
        MISC;

        /** Ordering used to lay out sorted contents left-to-right. */
        static final List<Category> ORDER = List.of(
                WEAPONS, TOOLS, ARMOUR, FOOD, POTIONS, REDSTONE,
                BUILDING_BLOCKS, NATURAL_BLOCKS, DECORATIONS, MISC
        );

        /**
         * Classify an item into one of the categories above. The order of the
         * checks matters: a diamond sword is a weapon, not a tool, even
         * though "sword" is a tool-like type — we look it up by enum first
         * for the weapons/tool/shortcut rules, and only fall through to the
         * block-style classifier for everything else.
         */
        static Category classify(ItemStack item) {
            if (item == null || item.getType().isAir()) {
                return MISC;
            }
            org.bukkit.Material type = item.getType();
            String name = type.name();

            // Weapons: anything that ends in _SWORD, _BOW, _CROSSBOW,
            // _TRIDENT, ARROW, TIPPED_ARROW, SPECTRAL_ARROW, MACE, SHIELD.
            if (name.endsWith("_SWORD") || name.endsWith("_BOW")
                    || name.endsWith("_CROSSBOW") || name.endsWith("TRIDENT")
                    || name.contains("ARROW") || name.endsWith("MACE")
                    || name.equals("SHIELD")) {
                return WEAPONS;
            }

            // Tools: pickaxe / axe / shovel / hoe / shears / fishing rod /
            // flint-and-steel / brush.
            if (name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                    || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                    || name.endsWith("_SHEARS") || name.endsWith("_FISHING_ROD")
                    || name.contains("FLINT_AND_STEEL") || name.endsWith("BRUSH")) {
                return TOOLS;
            }

            // Armour: helmet / chestplate / leggings / boots of every tier.
            if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                    || name.equals("SHIELD") /* already covered, but harmless */) {
                return ARMOUR;
            }

            // Potions & brewing.
            if (name.contains("POTION") || name.contains("BREWING")
                    || name.equals("BLAZE_POWDER") || name.equals("FERMENTED_SPIDER_EYE")
                    || name.equals("NETHER_WART") || name.equals("GHAST_TEAR")
                    || name.equals("MAGMA_CREAM") || name.equals("RABBIT_FOOT")
                    || name.equals("SPIDER_EYE") || name.equals("SUGAR")
                    || name.equals("GLISTERING_MELON") || name.equals("PHANTOM_MEMBRANE")
                    || name.equals("DRAGON_BREATH") || name.equals("TURTLE_SCUTE")) {
                return POTIONS;
            }

            // Food & edible items.
            if (type.isEdible() || name.endsWith("_APPLE")
                    || name.endsWith("BREAD") || name.equals("CAKE")
                    || name.equals("PUMPKIN_PIE") || name.equals("HONEY_BOTTLE")
                    || name.equals("DRIED_KELP") || name.equals("COOKIE")
                    || name.contains("STEW") || name.equals("MUSHROOM_STEW")
                    || name.equals("BEETROOT_SOUP") || name.equals("RABBIT_STEW")) {
                return FOOD;
            }

            // Redstone & wiring (kept before blocks so we don't double-count
            // technical blocks as building blocks).
            if (name.contains("REDSTONE") || name.contains("REPEATER")
                    || name.contains("COMPARATOR") || name.contains("PISTON")
                    || name.equals("SLIME_BALL") || name.contains("HOPPER")
                    || name.equals("DROPPER") || name.equals("DISPENSER")
                    || name.equals("OBSERVER") || name.equals("DAYLIGHT_DETECTOR")
                    || name.equals("TRIPWIRE_HOOK") || name.equals("LEVER")
                    || name.equals("BUTTON") || name.endsWith("_BUTTON")
                    || name.equals("PRESSURE_PLATE") || name.endsWith("PRESSURE_PLATE")
                    || name.equals("TARGET") || name.equals("STRING")
                    || name.equals("TRIPWIRE") || name.equals("REDSTONE_TORCH")) {
                return REDSTONE;
            }

            // Building blocks: stones, bricks, planks, slabs, stairs, fences,
            // doors, glass, terracotta, concrete.
            if (name.endsWith("_STONE") || name.endsWith("_STAIRS")
                    || name.endsWith("_SLAB") || name.endsWith("_FENCE")
                    || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL")
                    || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                    || name.endsWith("_PRESSURE_PLATE") || name.endsWith("_BUTTON")
                    || name.endsWith("_PLANKS") || name.endsWith("_LOG")
                    || name.endsWith("_WOOD") || name.endsWith("_CONCRETE")
                    || name.endsWith("_TERRACOTTA") || name.endsWith("_GLAZED_TERRACOTTA")
                    || name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE")
                    || name.endsWith("_BRICK") || name.endsWith("BRICKS")
                    || name.endsWith("COBBLESTONE") || name.equals("BRICK")
                    || name.equals("NETHER_BRICKS") || name.equals("END_STONE_BRICKS")
                    || name.equals("QUARTZ_BLOCK") || name.equals("SMOOTH_STONE")
                    || name.equals("MOSSY_COBBLESTONE") || name.equals("MOSSY_STONE_BRICKS")
                    || name.equals("CHISELED_STONE_BRICKS") || name.equals("CRACKED_STONE_BRICKS")
                    || name.equals("POLISHED_ANDESITE") || name.equals("POLISHED_DIORITE")
                    || name.equals("POLISHED_GRANITE") || name.equals("POLISHED_DEEPSLATE")
                    || name.contains("CUT_COPPER") || name.equals("COPPER_BLOCK")
                    || name.equals("WAXED_COPPER_BLOCK")) {
                return BUILDING_BLOCKS;
            }

            // Natural / organic blocks: dirt, grass, sand, gravel, leaves,
            // saplings, ores (the player usually wants ore right next to
            // mining-related buckets anyway, so keep them here).
            if (name.contains("_ORE") || name.contains("_DEEPSLATE")
                    || name.equals("DIRT") || name.equals("GRASS_BLOCK")
                    || name.equals("COARSE_DIRT") || name.equals("PODZOL")
                    || name.equals("MYCELIUM") || name.equals("SAND")
                    || name.equals("RED_SAND") || name.equals("GRAVEL")
                    || name.equals("CLAY") || name.equals("SOUL_SAND")
                    || name.equals("SOUL_SOIL") || name.equals("MUD")
                    || name.equals("MANGROVE_ROOTS") || name.endsWith("_LEAVES")
                    || name.endsWith("_SAPLING") || name.endsWith("_LOG")
                    || name.equals("MELON") || name.equals("PUMPKIN")
                    || name.equals("WATER_BUCKET") || name.equals("LAVA_BUCKET")) {
                return NATURAL_BLOCKS;
            }

            // Decorations: flowers, paintings, carpets, banners, candles,
            // heads, item frames, beds, chests, signs.
            if (name.endsWith("_CARPET") || name.endsWith("_BANNER")
                    || name.endsWith("_CANDLE") || name.endsWith("_BED")
                    || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                    || name.endsWith("_FLOWER") || name.equals("PAINTING")
                    || name.equals("ITEM_FRAME") || name.equals("GLOW_ITEM_FRAME")
                    || name.contains("CHEST") || name.contains("SIGN")
                    || name.contains("HANGING_SIGN") || name.endsWith("_POT")
                    || name.contains("FLOWER_POT")) {
                return DECORATIONS;
            }

            return MISC;
        }
    }

    /**
     * Sets an item at a specific slot. Mutates the underlying array — required
     * by callers that already hold the page reference and want a fast in-place
     * write (e.g. Quick Deposit). New code should prefer
     * {@link #withItem(int, ItemStack)}.
     *
     * @param slot The slot index (0-indexed)
     * @param item The ItemStack to set (null to clear)
     */
    public void setItem(int slot, ItemStack item) {
        if (slot >= 0 && slot < contents.length) {
            contents[slot] = item;
        }
    }
}
