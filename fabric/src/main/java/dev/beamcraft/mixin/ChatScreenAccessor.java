package dev.beamcraft.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** BeamNG types the chat text; this lets us put it into Minecraft's chat box. */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Accessor("chatField")
    TextFieldWidget beamcraft$getChatField();
}
