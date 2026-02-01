package top.newblock.playerStorageBox;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class StoragePlaceholder extends PlaceholderExpansion {
    private final PlayerStorageBox plugin;
    private final StorageManager manager;

    public StoragePlaceholder(PlayerStorageBox plugin, StorageManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }
    @Override public @NotNull String getIdentifier() { return "playerstoragebox"; }
    @Override public @NotNull String getAuthor() { return "newblock"; }
    @Override public @NotNull String getVersion() { return "1.1.0"; }
    @Override public boolean persist() { return true; }
    @Override public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "0";
        if (params.equalsIgnoreCase("total_value")) {
            return String.valueOf(manager.getTotalValue(player.getUniqueId()));
        }
        return null;
    }
}