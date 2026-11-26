package ru.stepanyaa.economyGUI.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ServerScheduler {
    private static final boolean IS_FOLIA = ServerPlatform.isFolia();
    private static final String FOLIA = "io.papermc.paper.threadedregions.scheduler.";
    private ServerScheduler() {}

    private static Object scheduler(String getter) throws ReflectiveOperationException {
        return Bukkit.class.getMethod(getter).invoke(null);
    }

    private static Object invoke(String api, Object target, String method, Class<?>[] signature, Object... args)
            throws ReflectiveOperationException {
        Method call = Class.forName(FOLIA + api).getMethod(method, signature);
        return call.invoke(target, args);
    }

    private static IllegalStateException unavailable(ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException
                ? ((InvocationTargetException) failure).getCause() : failure;
        return new IllegalStateException("Folia scheduler unavailable/incompatible. Refusing unsafe legacy scheduler fallback.", cause);
    }

    public static void runTask(Plugin plugin, Runnable task) {
        if (!IS_FOLIA) { Bukkit.getScheduler().runTask(plugin, task); return; }
        try {
            invoke("GlobalRegionScheduler", scheduler("getGlobalRegionScheduler"), "run",
                    new Class<?>[]{Plugin.class, Consumer.class}, plugin, (Consumer<Object>) ignored -> task.run());
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }

    public static void runTaskLater(Plugin plugin, Runnable task, long ticks) {
        if (!IS_FOLIA) { Bukkit.getScheduler().runTaskLater(plugin, task, ticks); return; }
        try {
            invoke("GlobalRegionScheduler", scheduler("getGlobalRegionScheduler"), "runDelayed",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class}, plugin,
                    (Consumer<Object>) ignored -> task.run(), Math.max(1L, ticks));
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }

    public static void runTask(Plugin plugin, Player player, Runnable task) {
        if (player == null) { runTask(plugin, task); return; }
        if (!player.isOnline()) return;
        if (!IS_FOLIA) { Bukkit.getScheduler().runTask(plugin, task); return; }
        try {
            Object scheduler = Player.class.getMethod("getScheduler").invoke(player);
            invoke("EntityScheduler", scheduler, "run", new Class<?>[]{Plugin.class, Consumer.class, Runnable.class},
                    plugin, (Consumer<Object>) ignored -> task.run(), (Runnable) () -> {});
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }

    public static void runTaskLater(Plugin plugin, Player player, Runnable task, long ticks) {
        if (player == null) { runTaskLater(plugin, task, ticks); return; }
        if (!player.isOnline()) return;
        if (!IS_FOLIA) { Bukkit.getScheduler().runTaskLater(plugin, task, ticks); return; }
        try {
            Object scheduler = Player.class.getMethod("getScheduler").invoke(player);
            invoke("EntityScheduler", scheduler, "runDelayed",
                    new Class<?>[]{Plugin.class, Consumer.class, Runnable.class, long.class},
                    plugin, (Consumer<Object>) ignored -> task.run(), (Runnable) () -> {}, Math.max(1L, ticks));
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (!IS_FOLIA) { Bukkit.getScheduler().runTaskAsynchronously(plugin, task); return; }
        try {
            invoke("AsyncScheduler", scheduler("getAsyncScheduler"), "runNow", new Class<?>[]{Plugin.class, Consumer.class},
                    plugin, (Consumer<Object>) ignored -> task.run());
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }

    public static void runAsyncLater(Plugin plugin, Runnable task, long ticks) {
        if (!IS_FOLIA) { Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks); return; }
        try {
            invoke("AsyncScheduler", scheduler("getAsyncScheduler"), "runDelayed",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class, TimeUnit.class},
                    plugin, (Consumer<Object>) ignored -> task.run(), Math.max(0L, ticks) * 50L, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }

    public static void runTaskAsync(Plugin plugin, Runnable task) { runAsync(plugin, task); }

    public static void runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        if (!IS_FOLIA) { Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period); return; }
        try {
            invoke("AsyncScheduler", scheduler("getAsyncScheduler"), "runAtFixedRate",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class},
                    plugin, (Consumer<Object>) ignored -> task.run(), Math.max(0L, delay) * 50L,
                    Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException e) { throw unavailable(e); }
    }
}
