package com.example.physicsrod;

import org.bukkit.plugin.java.JavaPlugin;

public final class PhysicsRodPlugin extends JavaPlugin {

    private PhysicsListener physicsListener;

    @Override
    public void onEnable() {
        physicsListener = new PhysicsListener(this);
        getCommand("physrod").setExecutor(new RodCommand());
        getServer().getPluginManager().registerEvents(physicsListener, this);
        getLogger().info("PhysicsRod enabled");
    }

    @Override
    public void onDisable() {
        if (physicsListener != null) {
            physicsListener.cleanup();
        }
        getLogger().info("PhysicsRod disabled");
    }
}
