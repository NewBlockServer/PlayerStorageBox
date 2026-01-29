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

    private final StorageManager manager;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public StorageListener(StorageManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        // 检查关闭的是不是仓库
        if (topInv.getHolder() instanceof StorageHolder holder) {
            // 如果没有人看这个界面了，从缓存里移除
            manager.removeActiveIfEmpty(holder.getOwnerUUID(), holder.getPage(), topInv);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory topInv = event.getView().getTopInventory();

        // 【核心修改】直接判断界面是否属于 StorageHolder
        if (!(topInv.getHolder() instanceof StorageHolder holder)) return;

        // 获取该界面的所有者和页码
        UUID ownerUUID = holder.getOwnerUUID();
        int page = holder.getPage();

        // 5 tick 冷却 (250ms)
        long now = System.currentTimeMillis();
        long last = cooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 250) {
            event.setCancelled(true);
            return;
        }
        cooldown.put(player.getUniqueId(), now);

        // 禁止 Shift 和 数字键
        if (event.isShiftClick() || event.getClick() == ClickType.NUMBER_KEY) {
            event.setCancelled(true);
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (player.getItemOnCursor().getType() != Material.AIR) return;
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        event.setCancelled(true);

        // 放入逻辑 (点击的是自己的背包)
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {

            // 权限检查
            if (page >= 3) {
                if (page == 3 && !player.hasPermission("group.vip") && !player.hasPermission("group.mvp") && !player.hasPermission("playerstoragebox.admin")) {
                    player.sendMessage("§c第 3 页需要 VIP 会员！");
                    return;
                }
                if (page >= 4 && !player.hasPermission("group.mvp") && !player.hasPermission("playerstoragebox.admin")) {
                    player.sendMessage("§c第 " + page + " 页需要 MVP 会员！");
                    return;
                }
            }

            // 黑名单物品检查
            if (isBlocked(clickedItem.getType())) {
                player.sendMessage("§c无法放入此限制物品！");
                return;
            }

            // 检查仓库是否满
            if (topInv.firstEmpty() == -1) {
                player.sendMessage("§c物品箱已满！");
                return;
            }

            topInv.addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        }
        // 取出逻辑 (点击的是仓库)
        else {
            if (player.getInventory().firstEmpty() == -1) {
                player.sendMessage("§c背包已满！");
                return;
            }

            player.getInventory().addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        }

        // 整理排序
        sortInventory(topInv);

        // 实时保存
        manager.saveSinglePage(ownerUUID, page, topInv);
    }

    private boolean isBlocked(Material m) {
        return m == Material.GOLD_INGOT || m == Material.NETHER_STAR || m == Material.CHEST
                || m == Material.DIAMOND || m == Material.COMPASS;
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