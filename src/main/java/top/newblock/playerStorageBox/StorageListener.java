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

    public StorageListener(PlayerStorageBox plugin, StorageManager manager) { this.plugin = plugin; this.manager = manager; }

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
        if (now - cooldown.getOrDefault(player.getUniqueId(), 0L) < 200) { event.setCancelled(true); return; }
        cooldown.put(player.getUniqueId(), now);

        if (event.isShiftClick() || event.getClick() == ClickType.NUMBER_KEY) { event.setCancelled(true); return; }
        ItemStack clickedItem = event.getCurrentItem();
        if (player.getItemOnCursor().getType() != Material.AIR) return;
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        event.setCancelled(true);

        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            int page = holder.getPage();
            // 权限检查简略
            if (topInv.firstEmpty() == -1) return;
            topInv.addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            if (player.getInventory().firstEmpty() == -1) return;
            player.getInventory().addItem(clickedItem.clone());
            event.getClickedInventory().setItem(event.getSlot(), null);
        }
        sortInventory(topInv);
        manager.saveSinglePage(holder.getOwnerUUID(), holder.getPage(), topInv);
    }
    private void sortInventory(Inventory inv) {
        ItemStack[] items = inv.getContents(); inv.clear();
        for (ItemStack item : items) if (item != null && item.getType() != Material.AIR) inv.addItem(item);
    }
}