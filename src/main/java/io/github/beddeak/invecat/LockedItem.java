package io.github.beddeak.invecat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** The barrier that fills a slot once it has disappeared. */
final class LockedItem {

    private final NamespacedKey key;
    private final ItemStack item;

    LockedItem(Plugin plugin) {
        key = new NamespacedKey(plugin, "locked_slot");
        item = ItemStack.of(Material.BARRIER);
        item.editMeta(meta -> {
            meta.itemName(Component.text("사라진 칸", NamedTextColor.RED));
            meta.lore(List.of(Component.text("이 칸은 더 이상 쓸 수 없습니다.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
        });
    }

    /** The template; {@code PlayerInventory#setItem} copies it. */
    ItemStack item() {
        return item;
    }

    boolean is(ItemStack stack) {
        return stack != null && stack.getType() == Material.BARRIER && stack.getPersistentDataContainer().has(key);
    }
}
