package top.newblock.playerStorageBox;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
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
        if (event.getView().getTopInventory().getHolder() instanceof StorageHolder holder) {
            // 关闭时触发保存（仍走防抖路径，确保最终一致）
            manager.saveSinglePage(holder.getOwnerUUID(), holder.getPage(),
                    event.getView().getTopInventory());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory topInv = event.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof StorageHolder holder)) return;

        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(player.getUniqueId(), 0L) < 200) {
            event.setCancelled(true);
            return;
        }
        cooldown.put(player.getUniqueId(), now);

        // 禁止 Shift+点击 或 数字键点击
        if (event.isShiftClick() || event.getClick() == ClickType.NUMBER_KEY) {
            event.setCancelled(true);
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (player.getItemOnCursor().getType() != Material.AIR) {
            // 放入操作
        } else if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            int page = holder.getPage();

            // 检查付费页权限（从配置读取，管理员自动绕过）
            if (!player.hasPermission("playerstoragebox.admin")) {
                var paidPages = plugin.getConfig().getConfigurationSection("pages.paid-pages");
                if (paidPages != null) {
                    String requiredPerm = paidPages.getString(String.valueOf(page));
                    if (requiredPerm != null && !player.hasPermission(requiredPerm)) {
                        player.sendMessage(plugin.getLang("page-paid-required")
                                .replace("{page}", String.valueOf(page))
                                .replace("{permission}", requiredPerm));
                        return;
                    }
                }
            }

            if (plugin.getConfig().getStringList("blocked-materials").contains(clickedItem.getType().name())) {
                player.sendMessage(plugin.getLang("blocked-item"));
                return;
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
        // 防抖异步保存：主线程只标记脏数据，不阻塞
        manager.saveSinglePage(holder.getOwnerUUID(), holder.getPage(), topInv);
    }

    private void sortInventory(Inventory inv) {
        ItemStack[] items = inv.getContents();
        inv.clear();
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                inv.addItem(item);
            }
        }
    }
}
