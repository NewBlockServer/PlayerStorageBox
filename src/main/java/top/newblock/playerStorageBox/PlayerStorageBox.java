package top.newblock.playerStorageBox;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerStorageBox extends JavaPlugin {

    private StorageManager storageManager;

    @Override
    public void onEnable() {
        SQLiteManager.init(this);
        storageManager = new StorageManager(this);

        storageManager.migrateOldData();

        getServer().getPluginManager().registerEvents(
                new StorageListener(storageManager), this
        );
    }

    @Override
    public void onDisable() {
        storageManager.saveAllAndClose();
        SQLiteManager.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        // /psb [页码] - 自己打开
        // /psb <玩家名> [页码] - 管理员打开

        if (args.length == 0) {
            storageManager.open(player, player.getUniqueId(), 1);
            return true;
        }

        // 尝试解析第一个参数
        String targetName = args[0];

        // 如果第一个参数是数字，则是玩家自己想开某一页
        if (targetName.matches("\\d+")) {
            int page = Math.max(1, Integer.parseInt(targetName));
            storageManager.open(player, player.getUniqueId(), page);
            return true;
        }

        // 否则视为查看他人
        if (!player.hasPermission("playerstoragebox.admin")) {
            player.sendMessage("§c你没有权限查看他人的物品箱！");
            return true;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            player.sendMessage("§c目标玩家不在线，无法操作其仓库。");
            return true;
        }

        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (Exception ignored) {}
        }

        storageManager.open(player, target.getUniqueId(), page);
        return true;
    }
}