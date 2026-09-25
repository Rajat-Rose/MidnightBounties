package com.midnightsmp.midnightbounties;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MidnightBounties extends JavaPlugin implements Listener, CommandExecutor {

    // Target UUID -> List of reward items
    private final Map<UUID, List<ItemStack>> activeBounties = new HashMap<>();

    // Creator UUID -> Target UUID (Active GUI session tracking)
    private final Map<UUID, UUID> activeBountySessions = new HashMap<>();

    private final String GUI_TITLE_PREFIX = ChatColor.DARK_RED + "Set Bounty: " + ChatColor.BOLD;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("bounty").setExecutor(this);
        getLogger().info("MidnightBounties (GUI Vault with Confirm/Cancel) loaded successfully!");
    }

    // --- 1. COMMAND HANDLER ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        // /bounty list
        if (sub.equals("list")) {
            if (activeBounties.isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "There are currently no active item bounties.");
                return true;
            }
            player.sendMessage(ChatColor.GOLD + "=== Active Item Bounties ===");
            for (Map.Entry<UUID, List<ItemStack>> entry : activeBounties.entrySet()) {
                String targetName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                int totalItems = entry.getValue().stream().mapToInt(ItemStack::getAmount).sum();
                player.sendMessage(ChatColor.RED + "• " + targetName + ": " + ChatColor.GREEN + totalItems + " total item(s) in reward pool!");
            }
            return true;
        }

        // /bounty set <Player>
        if (sub.equals("set")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /bounty set <Player>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Player not found or offline!");
                return true;
            }

            if (target.equals(player)) {
                player.sendMessage(ChatColor.RED + "You cannot set a bounty on yourself!");
                return true;
            }

            openBountyGUI(player, target);
            return true;
        }

        sendHelp(player);
        return true;
    }

    private void openBountyGUI(Player creator, Player target) {
        Inventory gui = Bukkit.createInventory(null, 36, GUI_TITLE_PREFIX + target.getName());

        // Fill Divider & Control Rows (Slots 18 - 35)
        ItemStack glass = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 18; i < 36; i++) {
            gui.setItem(i, glass);
        }

        // Cancel Button (Slot 29)
        ItemStack cancelButton = createGuiItem(Material.RED_WOOL, ChatColor.RED + "" + ChatColor.BOLD + "❌ Cancel Bounty",
                ChatColor.GRAY + "Click to return items and cancel.");
        gui.setItem(29, cancelButton);

        // Confirm Button (Slot 33)
        ItemStack confirmButton = createGuiItem(Material.LIME_WOOL, ChatColor.GREEN + "" + ChatColor.BOLD + "✅ Confirm Bounty",
                ChatColor.GRAY + "Click to lock items and place bounty.");
        gui.setItem(33, confirmButton);

        activeBountySessions.put(creator.getUniqueId(), target.getUniqueId());
        creator.openInventory(gui);
        creator.sendMessage(ChatColor.YELLOW + "Place reward items in top slots (0-17) and click Green Wool to confirm!");
    }

    // --- 2. GUI BUTTON CLICK HANDLER ---
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        if (!title.startsWith(GUI_TITLE_PREFIX)) return;

        int slot = event.getRawSlot();

        // Prevent moving UI Control items in bottom row
        if (slot >= 18 && slot < 36) {
            event.setCancelled(true);

            // Handle Cancel Button
            if (slot == 29) {
                returnItemsAndCancel(player, event.getInventory());
                player.closeInventory();
            }

            // Handle Confirm Button
            if (slot == 33) {
                confirmBounty(player, event.getInventory());
                player.closeInventory();
            }
        }
    }

    private void confirmBounty(Player creator, Inventory inventory) {
        UUID creatorUUID = creator.getUniqueId();
        if (!activeBountySessions.containsKey(creatorUUID)) return;

        UUID targetUUID = activeBountySessions.remove(creatorUUID);
        Player target = Bukkit.getPlayer(targetUUID);
        String targetName = (target != null) ? target.getName() : Bukkit.getOfflinePlayer(targetUUID).getName();

        List<ItemStack> depositedItems = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                depositedItems.add(item.clone());
                inventory.setItem(i, null); // Clear item from GUI so close handler doesn't duplicate
            }
        }

        if (depositedItems.isEmpty()) {
            creator.sendMessage(ChatColor.RED + "❌ Cannot set bounty: Reward area was empty!");
            return;
        }

        List<ItemStack> currentRewards = activeBounties.getOrDefault(targetUUID, new ArrayList<>());
        currentRewards.addAll(depositedItems);
        activeBounties.put(targetUUID, currentRewards);

        int count = depositedItems.stream().mapToInt(ItemStack::getAmount).sum();
        Bukkit.broadcastMessage(ChatColor.GOLD + "🎯 " + ChatColor.RED + creator.getName() +
                ChatColor.YELLOW + " placed a bounty of " + ChatColor.GREEN + count + " item(s) " +
                ChatColor.YELLOW + "on " + ChatColor.DARK_RED + targetName + "!");
    }

    private void returnItemsAndCancel(Player creator, Inventory inventory) {
        UUID creatorUUID = creator.getUniqueId();
        activeBountySessions.remove(creatorUUID);

        for (int i = 0; i < 18; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> leftover = creator.getInventory().addItem(item);
                for (ItemStack overflow : leftover.values()) {
                    creator.getWorld().dropItemNaturally(creator.getLocation(), overflow);
                }
                inventory.setItem(i, null);
            }
        }
        creator.sendMessage(ChatColor.RED + "❌ Bounty cancelled. Your items have been returned.");
    }

    // --- 3. SAFETY CLOSE HANDLER (ESC OR DISCONNECT) ---
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (activeBountySessions.containsKey(player.getUniqueId())) {
            returnItemsAndCancel(player, event.getInventory());
        }
    }

    // --- 4. AUTO-CLAIM BOUNTY ON KILL ---
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) return;

        UUID victimUUID = victim.getUniqueId();
        if (activeBounties.containsKey(victimUUID)) {
            List<ItemStack> rewards = activeBounties.remove(victimUUID);

            int totalRewardItems = 0;
            for (ItemStack reward : rewards) {
                totalRewardItems += reward.getAmount();
                HashMap<Integer, ItemStack> leftover = killer.getInventory().addItem(reward);
                for (ItemStack overflow : leftover.values()) {
                    killer.getWorld().dropItemNaturally(killer.getLocation(), overflow);
                }
            }

            Bukkit.broadcastMessage(ChatColor.GOLD + "🎯 " + ChatColor.GREEN + killer.getName() +
                    ChatColor.YELLOW + " claimed the bounty on " + ChatColor.RED + victim.getName() +
                    ChatColor.YELLOW + " and received " + ChatColor.GREEN + totalRewardItems + " reward item(s)!");
        }
    }

    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== MidnightBounties Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/bounty set <Player> " + ChatColor.GRAY + "- Open item deposit GUI to set bounty");
        player.sendMessage(ChatColor.YELLOW + "/bounty list " + ChatColor.GRAY + "- List current active target bounties");
    }
}
