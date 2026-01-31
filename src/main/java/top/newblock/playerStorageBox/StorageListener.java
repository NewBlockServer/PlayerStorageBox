package top.newblock.playerStorageBox;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StorageListener implements Listener {
    private final PlayerStorageBox plugin;
    private final StorageManager manager;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public StorageListener(PlayerStorageBox plugin, StorageManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (topInv.getHolder() instanceof StorageHolder holder) {
            manager.saveSinglePage(holder.getOwnerUUID(), holder.getPage(), topInv);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory topInv = event.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof StorageHolder holder)) return;

        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(player.getUniqueId(), 0L) < 200) { event.setCancelled(true); return; }
        cooldown.put(player.getUniqueId(), now);

        if (event.isShiftClick() || event.getClick() == ClickType.NUMBER_KEY) { event.setCancelled(true); return; }
        ItemStack clickedItem = event.getCurrentItem();
        if (player.getItemOnCursor().getType() != Material.AIR) return;
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        event.setCancelled(true);

        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            // 权限检查
            int page = holder.getPage();
            if (page >= 3) {
                if (page == 3 && !player.hasPermission("group.vip") && !player.hasPermission("group.mvp") && !player.hasPermission("playerstoragebox.admin")) {
                    player.sendMessage(plugin.getLang("vip-required")); return;
                }
                if (page >= 4 && !player.hasPermission("group.mvp") && !player.hasPermission("playerstoragebox.admin")) {
                    player.sendMessage(plugin.getLang("mvp-required").replace("{page}", String.valueOf(page))); return;
                }
            }
            if (plugin.getConfig().getStringList("blocked-materials").contains(clickedItem.getType().name())) {
                player.sendMessage(plugin.getLang("blocked-item")); return;
            }
            if (topInv.firstEmpty() == -1) return;
            topInv.addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            if (player.getInventory().firstEmpty() == -1) return;
            player.getInventory().addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        }
        sortInventory(topInv);
        // 此处通过保存操作触发缓存价值计算
        manager.saveSinglePage(holder.getOwnerUUID(), holder.getPage(), topInv);
    }

    private void sortInventory(Inventory inv) {
        ItemStack[] items = inv.getContents();
        inv.clear();
        for (ItemStack item : items) if (item != null && item.getType() != Material.AIR) inv.addItem(item);
    }
}