package top.newblock.playerStorageBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.UUID;

public class StorageHolder implements InventoryHolder {
    private final UUID ownerUUID;
    private final int page;
    public StorageHolder(UUID ownerUUID, int page) { this.ownerUUID = ownerUUID; this.page = page; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public int getPage() { return page; }
    @Override public Inventory getInventory() { return null; }
}