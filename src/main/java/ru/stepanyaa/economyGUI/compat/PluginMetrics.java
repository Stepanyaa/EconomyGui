package ru.stepanyaa.economyGUI.compat;

import dev.faststats.ErrorTracker;
import dev.faststats.bukkit.BukkitContext;
import dev.faststats.data.Metric;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginMetrics {
    private PluginMetrics() {}
    public static Runnable start(JavaPlugin plugin) {
        Runnable shutdown = () -> {};
        try {
            BukkitContext context = new BukkitContext.Factory(plugin, "8a0c8b55ce2b568bd56821f8b4db9418")
                    .errorTrackerService(ErrorTracker.contextAware())
                    .metrics(factory -> factory.addMetric(Metric.string("server_version", () -> plugin.getServer().getVersion())).create())
                    .create();
            context.ready();
            shutdown = context::shutdown;
        } catch (RuntimeException | LinkageError unavailable) {
            plugin.getLogger().fine("FastStats unavailable: " + unavailable);
        }
        try { new Metrics(plugin, 27776); }
        catch (RuntimeException | LinkageError unavailable) { plugin.getLogger().fine("bStats unavailable: " + unavailable); }
        return shutdown;
    }
}
