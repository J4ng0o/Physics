package com.example.physicsrod;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class RodCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }
        if (!player.hasPermission("physicsrod.give")) {
            player.sendMessage("§cNo permission.");
            return true;
        }

        ItemStack rod = new ItemStack(Material.BREEZE_ROD);
        ItemMeta meta = rod.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bPhysics Rod");
            rod.setItemMeta(meta);
        }
        player.getInventory().addItem(rod);
        player.sendMessage("§aPhysics Rod received.");
        return true;
    }
}
