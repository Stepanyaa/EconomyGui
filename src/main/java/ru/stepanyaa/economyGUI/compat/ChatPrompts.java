package ru.stepanyaa.economyGUI.compat;

import org.bukkit.entity.Player;
import java.lang.reflect.Array;

public final class ChatPrompts {
    private ChatPrompts() {}

    public static void send(Player player, String prompt, String cancelLabel) {
        if (!tryClickable(player, prompt, cancelLabel)) {
            player.sendMessage(prompt + " " + cancelLabel + " /economygui reset");
        }
    }

    private static boolean tryClickable(Player player, String prompt, String cancelLabel) {
        try {
            ClassLoader loader = ChatPrompts.class.getClassLoader();
            Class<?> base = Class.forName("net.md_5.bungee.api.chat.BaseComponent", false, loader);
            Class<?> text = Class.forName("net.md_5.bungee.api.chat.TextComponent", false, loader);
            Class<?> click = Class.forName("net.md_5.bungee.api.chat.ClickEvent", false, loader);
            Class<?> action = Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action", false, loader);
            Object message = text.getConstructor(String.class).newInstance(prompt + " ");
            Object cancel = text.getConstructor(String.class).newInstance(cancelLabel);
            Object event = click.getConstructor(action, String.class)
                    .newInstance(action.getField("RUN_COMMAND").get(null), "/economygui reset");
            base.getMethod("setClickEvent", click).invoke(cancel, event);
            base.getMethod("addExtra", base).invoke(message, cancel);
            java.lang.reflect.Method spigotMethod = Player.class.getMethod("spigot");
            Object spigot = spigotMethod.invoke(player);
            if (spigot == null) return false;
            Object components = Array.newInstance(base, 1);
            Array.set(components, 0, message);
            spigotMethod.getReturnType().getMethod("sendMessage", components.getClass()).invoke(spigot, components);
            return true;
        } catch (ReflectiveOperationException | LinkageError | SecurityException unsupported) {
            return false;
        }
    }
}
