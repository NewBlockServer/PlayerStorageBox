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
            manager.saveSinglePage(holder.getOwnerUUID(), holder.getPage(), event.getView().getTopInventory());
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

        // 禁止 Shift+点击 或 数字键点击 (防止绕过检测直接存入)
        if (event.isShiftClick() || event.getClick() == ClickType.NUMBER_KEY) {
            event.setCancelled(true);
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        // 只有当玩家手里拿着东西，或者点击了有效物品时才处理
        if (player.getItemOnCursor().getType() != Material.AIR) {
            // 这是一个放入操作 (光标有物品)
        } else if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        event.setCancelled(true);

        // 判断点击的是玩家背包（意图存入物品）
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            int page = holder.getPage();

            // --- 修复：补回 VIP/MVP 权限检查 ---
            if (page >= 3) {
                // 第3页：需要 VIP 或 MVP 或 Admin
                if (page == 3 && !player.hasPermission("group.vip") && !player.hasPermission("group.mvp") && !player.hasPermission("playerstoragebox.admin")) {
                    player.sendMessage(plugin.getLang("vip-required"));
                    return;
                }
                // 第4页及以上：需要 MVP 或 Admin
                if (page >= 4 && !player.hasPermission("group.mvp") && !player.hasPermission("playerstoragebox.admin")) {
                    player.sendMessage(plugin.getLang("mvp-required").replace("{page}", String.valueOf(page)));
                    return;
                }
            }

            // --- 修复：补回违禁物品检查 ---
            if (plugin.getConfig().getStringList("blocked-materials").contains(clickedItem.getType().name())) {
                player.sendMessage(plugin.getLang("blocked-item"));
                return;
            }

            // 执行存入逻辑
            if (topInv.firstEmpty() == -1) return;
            topInv.addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);

        } else {
            // 点击的是仓库（意图取出物品）
            if (player.getInventory().firstEmpty() == -1) return;
            player.getInventory().addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        }

        sortInventory(topInv);
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