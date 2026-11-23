package com.desgraciamon.badges;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Inyecta de forma limpia el botón "Mis Insignias (Badges)" dentro del menú F7
 * de desgraciamon4-discord-bridge (DiscordSettingsScreen) sin necesidad de recompilar
 * ni modificar discord-bridge.
 */
public final class F7ScreenIntegration {
    private static Field childrenField;
    private static Field renderablesField;
    private static Field narratablesField;

    private F7ScreenIntegration() {}

    @SuppressWarnings("unchecked")
    public static void onDiscordSettingsScreenInit(Screen screen) {
        try {
            try {
                Field hasBadges = screen.getClass().getDeclaredField("hasBadgesTile");
                if (hasBadges != null && hasBadges.getBoolean(screen)) {
                    return; // Ya integrado en la cuadrícula nativa de F7
                }
            } catch (Throwable ignored) {}

            if (childrenField == null) {
                // En Mojang mappings / Intermediary buscamos las listas de widgets de Screen
                for (Field f : Screen.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        if (childrenField == null) childrenField = f;
                        else if (renderablesField == null) renderablesField = f;
                        else if (narratablesField == null) narratablesField = f;
                    }
                }
            }
            List<GuiEventListener> children = (List<GuiEventListener>) childrenField.get(screen);
            Button doneBtn = null;
            for (GuiEventListener child : children) {
                if (child instanceof Button b) {
                    doneBtn = b; // El último botón añadido en DiscordSettingsScreen es "Hecho"
                }
            }
            if (doneBtn == null) return;

            int buttonWidth = doneBtn.getWidth();
            int x = doneBtn.getX();
            // Colocamos el botón de Insignias en la fila anterior al botón Hecho y bajamos Hecho 22px
            int badgesY = doneBtn.getY() - 6;
            doneBtn.setY(doneBtn.getY() + 18);

            Component label = BadgesClientState.f7ButtonSummary();
            Button badgesBtn = new Button(x, badgesY, buttonWidth, 20, label, b -> {
                BadgesClientState.openBadgesScreen(screen);
            }, java.util.function.Supplier::get) {
                @Override
                protected void renderWidget(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                    int bg = this.isHovered() ? 0xFF414A43 : 0xFF29312C;
                    graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), bg);
                    graphics.fill(this.getX(), this.getY(), this.getX() + 3, this.getY() + this.getHeight(), 0xFFFFD04E);
                    graphics.drawCenteredString(
                        net.minecraft.client.Minecraft.getInstance().font,
                        BadgesClientState.f7ButtonSummary(),
                        this.getX() + this.getWidth() / 2,
                        this.getY() + 6,
                        0xFFF4F1E8
                    );
                }
            };

            // Insertar antes del último botón (doneBtn) para que DiscordSettingsScreen siga reconociendo doneBtn como lastOrNull()
            int lastIdx = children.lastIndexOf(doneBtn);
            if (lastIdx >= 0) {
                children.add(lastIdx, badgesBtn);
            } else {
                children.add(badgesBtn);
            }
            if (renderablesField != null) {
                List<Object> renderables = (List<Object>) renderablesField.get(screen);
                renderables.add(badgesBtn);
            }
            if (narratablesField != null) {
                List<NarratableEntry> narratables = (List<NarratableEntry>) narratablesField.get(screen);
                narratables.add(badgesBtn);
            }
        } catch (Throwable t) {
            BadgesMod.Companion.getLOGGER().warn("No se pudo adjuntar el botón de Insignias en F7", t);
        }
    }
}

