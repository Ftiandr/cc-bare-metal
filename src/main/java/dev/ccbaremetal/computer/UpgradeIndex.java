package dev.ccbaremetal.computer;

import dan200.computercraft.api.upgrades.UpgradeBase;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which items are upgrades of a kind (pocket or turtle): CC: Tweaked's data-driven upgrade registries, with CC's own
 * upgrades and whatever addons register. Works on both sides (the registries are synced to clients); the item index
 * is built once per registry instance.
 */
public final class UpgradeIndex<T extends UpgradeBase> {
    private record Index<T>(Map<Item, List<Holder.Reference<T>>> byItem, List<Holder.Reference<T>> all) {
    }

    private final ResourceKey<Registry<T>> key;
    private final Map<Registry<T>, Index<T>> indexes = new WeakHashMap<>();

    public UpgradeIndex(ResourceKey<Registry<T>> key) {
        this.key = key;
    }

    private synchronized Index<T> index(RegistryAccess access) {
        var registry = access.registry(key).orElse(null);
        if (registry == null) return new Index<>(Map.of(), List.of());
        return indexes.computeIfAbsent(registry, r -> {
            var map = new HashMap<Item, List<Holder.Reference<T>>>();
            var list = new ArrayList<Holder.Reference<T>>();
            r.holders().forEach(holder -> {
                list.add(holder);
                map.computeIfAbsent(holder.value().getCraftingItem().getItem(), k -> new ArrayList<>()).add(holder);
            });
            list.sort((a, b) -> a.key().location().toString().compareTo(b.key().location().toString()));
            return new Index<>(map, List.copyOf(list));
        });
    }

    /** The upgrade this item installs, or null. */
    public Holder.Reference<T> find(RegistryAccess access, ItemStack stack) {
        if (stack.isEmpty()) return null;
        var candidates = index(access).byItem().get(stack.getItem());
        if (candidates == null) return null;
        for (var holder : candidates) {
            if (holder.value().isItemSuitable(stack)) return holder;
        }
        return null;
    }

    /** Every upgrade, sorted by id (for the list of supported items). */
    public List<Holder.Reference<T>> all(RegistryAccess access) {
        return index(access).all();
    }
}
