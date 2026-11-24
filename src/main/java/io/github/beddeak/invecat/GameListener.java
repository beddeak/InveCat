package io.github.beddeak.invecat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Game lifecycle hooks, and keeps the locked-slot barriers where they are. */
final class GameListener implements Listener {

    private final InveCatPlugin plugin;

    GameListener(InveCatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.game().onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.game().onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        plugin.game().onDeath(event);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.game().onRespawn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        plugin.game().onGameModeChange(event.getPlayer(), event.getNewGameMode());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            plugin.game().dragonKilled(dragon.getKiller());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        LockedItem locked = plugin.game().lockedItem();
        if (locked.is(event.getCurrentItem()) || locked.is(event.getCursor())) {
            event.setCancelled(true);
        } else if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && locked.is(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
        } else if (event.getClick() == ClickType.SWAP_OFFHAND && locked.is(event.getWhoClicked().getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        LockedItem locked = plugin.game().lockedItem();
        if (locked.is(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (locked.is(event.getView().getItem(rawSlot))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.game().lockedItem().is(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        LockedItem locked = plugin.game().lockedItem();
        if (locked.is(event.getMainHandItem()) || locked.is(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.game().lockedItem().is(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    /** Decorated pots and shelves take whatever item is held, so keep the barrier out of them. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && (block.getType() == Material.DECORATED_POT || Tag.WOODEN_SHELVES.isTagged(block.getType()))
                && plugin.game().lockedItem().is(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** Right-clicking armor swaps it with what is worn, which would pull the barrier out of a locked armor slot. */
    @EventHandler(priority = EventPriority.LOW)
    public void onEquip(PlayerInteractEvent event) {
        Action action = event.getAction();
        ItemStack item = event.getItem();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK || item == null) {
            return;
        }
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable != null && equippable.slot().isArmor() && equippable.swappable()
                && plugin.game().lockedItem().is(event.getPlayer().getInventory().getItem(equippable.slot()))) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (holdsLocked(event.getPlayer(), event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (holdsLocked(event.getPlayer(), event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (plugin.game().lockedItem().is(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    /** Middle-click would swap the barrier into another slot. */
    @EventHandler(ignoreCancelled = true)
    public void onPickItem(PlayerPickItemEvent event) {
        LockedItem locked = plugin.game().lockedItem();
        PlayerInventory inventory = event.getPlayer().getInventory();
        if (locked.is(inventory.getItem(event.getTargetSlot()))
                || event.getSourceSlot() >= 0 && locked.is(inventory.getItem(event.getSourceSlot()))) {
            event.setCancelled(true);
        }
    }

    private boolean holdsLocked(HumanEntity player, PlayerInteractEntityEvent event) {
        return plugin.game().lockedItem().is(player.getInventory().getItem(event.getHand()));
    }
}
