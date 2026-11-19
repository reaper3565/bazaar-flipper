package dev.codex.bazaarflip;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class BazaarCraftFlipper implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FlipData.startTracker();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("bazaarcraftflipper", "controls"));
        KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bazaarcraftflipper.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> handleKey(open, client));
    }

    private static void handleKey(KeyMapping open, Minecraft client) {
        while (open.consumeClick()) {
            if (client.gui.screen() == null) {
                client.gui.setScreen(new FlipScreen());
            }
        }
    }
}
