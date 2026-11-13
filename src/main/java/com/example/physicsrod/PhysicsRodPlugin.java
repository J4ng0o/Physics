package com.example.physicsrod;

import org.bukkit.plugin.java.JavaPlugin;

public final class PhysicsRodPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getCommand("physrod").setExecutor(new RodCommand());
        getServer().getPluginManager().registerEvents(new PhysicsListener(this), this);
        getLogger().info("PhysicsRod enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("PhysicsRod disabled");
    }
}
