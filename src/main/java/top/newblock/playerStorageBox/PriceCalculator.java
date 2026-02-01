package top.newblock.playerStorageBox;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PriceCalculator {

    private final PlayerStorageBox plugin;
    private final Map<String, Long> namePrices = new HashMap<>();
    private final Map<String, Long> lorePrices = new HashMap<>();
    private final List<Combination> combinations = new ArrayList<>();

    public PriceCalculator(PlayerStorageBox plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        namePrices.clear();
        lorePrices.clear();
        combinations.clear();

        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // 1. 加载 Lore 价值
        if (config.getConfigurationSection("prices.lore") != null) {
            for (String key : config.getConfigurationSection("prices.lore").getKeys(false)) {
                lorePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.lore." + key));
            }
        }

        // 2. 加载 Name 价值
        if (config.getConfigurationSection("prices.name") != null) {
            for (String key : config.getConfigurationSection("prices.name").getKeys(false)) {
                namePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.name." + key));
            }
        }

        // 3. 加载组合规则 (升级版)
        ConfigurationSection comboSec = config.getConfigurationSection("combinations");
        if (comboSec != null) {
            for (String key : comboSec.getKeys(false)) {
                ConfigurationSection sec = comboSec.getConfigurationSection(key);
                if (sec == null) continue;

                // 加载 Lore 要求
                List<String> rawLore = sec.getStringList("required-lore");
                List<String> coloredLore = new ArrayList<>();
                for (String s : rawLore) coloredLore.add(ChatColor.translateAlternateColorCodes('&', s));

                // 加载基础 Lore 倍率
                double lMult = sec.getDouble("lore-multiplier", 1.0);

                // 加载名字倍率映射 (New!)
                Map<String, Double> nMultMap = new HashMap<>();

                // 兼容旧配置：如果有 required-name，自动转入 map
                if (sec.contains("required-name")) {
                    String oldName = ChatColor.translateAlternateColorCodes('&', sec.getString("required-name"));
                    double oldMult = sec.getDouble("name-multiplier", 1.0);
                    nMultMap.put(oldName, oldMult);
                }

                // 加载新的 name-multipliers 部分
                ConfigurationSection nameSec = sec.getConfigurationSection("name-multipliers");
                if (nameSec != null) {
                    for (String nameKey : nameSec.getKeys(false)) {
                        String coloredName = ChatColor.translateAlternateColorCodes('&', nameKey);
                        double mult = nameSec.getDouble(nameKey);
                        nMultMap.put(coloredName, mult);
                    }
                }

                combinations.add(new Combination(coloredLore, lMult, nMultMap));
            }
        }
    }

    public long calculateItemValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();

        long baseValue = 0;

        // Step 1: 基础价值计算
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                for (Map.Entry<String, Long> entry : lorePrices.entrySet()) {
                    if (isMatch(line, entry.getKey(), false)) {
                        baseValue += entry.getValue();
                    }
                }
            }
        }

        if (meta.hasDisplayName()) {
            String dName = meta.getDisplayName();
            for (Map.Entry<String, Long> entry : namePrices.entrySet()) {
                if (isMatch(dName, entry.getKey(), true)) {
                    baseValue += entry.getValue();
                }
            }
        }

        if (baseValue == 0) return 0;

        // Step 2: 组合倍率计算 (升级版)
        double maxMultiplier = 1.0;

        if (meta.hasLore()) {
            List<String> itemLore = meta.getLore();
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : "";

            for (Combination combo : combinations) {
                // 1. 先检查 Lore 是否达标
                if (containsAllLore(itemLore, combo.requiredLore)) {

                    // 默认倍率 = 基础Lore倍率
                    double bestComboMult = combo.loreMultiplier;

                    // 2. 遍历该组合下的所有 Name 规则，寻找匹配项
                    if (!combo.nameMultipliers.isEmpty()) {
                        boolean matchedAnyName = false;
                        double bestNameMult = 0;

                        for (Map.Entry<String, Double> entry : combo.nameMultipliers.entrySet()) {
                            String targetNamePattern = entry.getKey();
                            double targetNameMult = entry.getValue();

                            // 检查名字匹配 (支持通配符)
                            if (isMatch(itemName, targetNamePattern, true)) {
                                matchedAnyName = true;
                                // 取该组合内最高的 name 倍率 (防止一个物品匹配多个名字规则)
                                if (targetNameMult > bestNameMult) {
                                    bestNameMult = targetNameMult;
                                }
                            }
                        }

                        // 如果匹配到了名字，计算最终倍率 = Lore倍率 * Name倍率
                        if (matchedAnyName) {
                            double calculated = combo.loreMultiplier * bestNameMult;
                            if (calculated > bestComboMult) {
                                bestComboMult = calculated;
                            }
                        }
                    }

                    // 3. 更新全局最大倍率
                    if (bestComboMult > maxMultiplier) {
                        maxMultiplier = bestComboMult;
                    }
                }
            }
        }

        return (long) (baseValue * maxMultiplier);
    }

    private boolean containsAllLore(List<String> itemLore, List<String> required) {
        if (required.isEmpty()) return false;
        for (String reqLine : required) {
            boolean found = false;
            for (String iLine : itemLore) {
                if (isMatch(iLine, reqLine, false)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean isMatch(String text, String pattern, boolean exactIfNoWildcard) {
        if (text == null || pattern == null) return false;
        if (pattern.contains("*")) {
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            return text.matches(regex);
        }
        if (exactIfNoWildcard) {
            return text.equals(pattern);
        } else {
            return text.contains(pattern);
        }
    }

    private static class Combination {
        List<String> requiredLore;
        double loreMultiplier;
        // Key: 名字匹配串, Value: 名字倍率
        Map<String, Double> nameMultipliers;

        public Combination(List<String> requiredLore, double loreMultiplier, Map<String, Double> nameMultipliers) {
            this.requiredLore = requiredLore;
            this.loreMultiplier = loreMultiplier;
            this.nameMultipliers = nameMultipliers;
        }
    }
}