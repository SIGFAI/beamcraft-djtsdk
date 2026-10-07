package dev.beamcraft;

import dev.beamcraft.mixin.ChatScreenAccessor;
import dev.beamcraft.mixin.CreativeInventoryScreenAccessor;
import dev.beamcraft.mixin.KeyboardInvoker;
import dev.beamcraft.mixin.KeyBindingAccessor;
import dev.beamcraft.mixin.MouseInvoker;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Hand;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.FlatLevelGeneratorPresets;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.level.LevelInfo;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BeamCraftClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("BeamCraft");
    public static final String WORLD_NAME = "BeamCraft";

    private static final int K_FWD = 1, K_BACK = 2, K_LEFT = 4, K_RIGHT = 8, K_JUMP = 16,
            K_SNEAK = 32, K_SPRINT = 64, K_ATTACK = 128, K_USE = 256;

    /** hide | minimize | show - what to do with the Minecraft window while BeamNG is driving it. */
    private static String windowMode = "offscreen";
    private static int savedX = 100, savedY = 100;

    // Latest input from BeamNG (client thread only).
    private static boolean onFoot;
    private static float yaw, pitch;
    private static int keys;
    /** Keys that went down since the last tick, so quick taps (double-tap jump to fly) are never missed. */
    private static int keysLatched;
    private static boolean carTargeted;
    private static double carDistance = Double.MAX_VALUE;

    private static boolean windowHidden;
    private static boolean ownsKeys;
    private static int showGrace;
    private static boolean showRequested;
    private static boolean pausedByUs;
    private static int mouseButtons;
    private static String lastScreen = "";
    private static boolean autoLoadTried;
    private static int tick;
    private static String lastHotbar = "";
    private static int ticksSinceSwing = 100;
    private static ItemStack lastHeld = ItemStack.EMPTY;

    @Override
    public void onInitializeClient() {
        loadConfig();
        WindowsTimers.apply();
        Bridge.start();
        HudChannel.start();
        ClientTickEvents.START_CLIENT_TICK.register(BeamCraftClient::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(BeamCraftClient::endTick);
        ServerLifecycleEvents.SERVER_STARTED.register(ServerSide::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerSide::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(ServerSide::onServerTick);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Bridge.send("respawned"));
        // The invisible ground is made of barriers; never let anyone (even creative) dig through it.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !state.isOf(Blocks.BARRIER));
    }

    private static void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("beamcraft.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            } else {
                props.setProperty("windowMode", windowMode);
                try (OutputStream out = Files.newOutputStream(file)) {
                    props.store(out, "BeamCraft. windowMode: offscreen | hide | minimize | show");
                }
            }
        } catch (IOException e) {
            LOG.warn("BeamCraft config: {}", e.toString());
        }
        windowMode = props.getProperty("windowMode", windowMode).trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- incoming

    private static void startTick(MinecraftClient mc) {
        clientTicks++;
        String line;
        while ((line = Bridge.poll()) != null) {
            try {
                handle(mc, line);
            } catch (RuntimeException e) {
                LOG.warn("BeamCraft: bad message '{}': {}", line, e.toString());
            }
        }
        boolean connected = Bridge.connected();
        if (!connected) onFoot = false;
        updateWindow(mc, connected);
        applyInput(mc);
        unstick(mc);
    }

    private static void handle(MinecraftClient mc, String line) {
        String[] a = line.split(" ");
        switch (a[0]) {
            case "hello" -> {
                Bridge.send("hello 1");
                BlockSync.resendAll();
                lastHotbar = "";
                HudStream.reset();
                autoLoadWorld(mc);
            }
            case "in" -> {
                yaw = Float.parseFloat(a[1]);
                pitch = Float.parseFloat(a[2]);
                keys = Integer.parseInt(a[3]);
                keysLatched |= keys;
                carTargeted = a[4].equals("1");
                carDistance = a.length > 6 ? Double.parseDouble(a[6]) : Double.MAX_VALUE;
                onFoot = a[5].equals("1");
            }
            case "press" -> press(mc, a);
            case "tp" -> ServerSide.teleport(mc, d(a[1]), d(a[2]), d(a[3]), Float.parseFloat(a[4]), true);
            case "park" -> {
                if (!onFoot) ServerSide.teleport(mc, d(a[1]), d(a[2]), d(a[3]), Float.parseFloat(a[4]), false);
            }
            case "terrain", "terrainw" -> ServerSide.terrain(mc, a);
            case "cmd" -> ServerSide.command(mc, line.substring(4));
            case "size" -> {
                resizeWindow(mc, Integer.parseInt(a[1]), Integer.parseInt(a[2]), a.length > 3 ? Integer.parseInt(a[3]) : 70);
                if (a.length > 4) {
                    // match BeamNG's frame rate; render a bit faster than that, but no more (GPU is shared)
                    int fps = Integer.parseInt(a[4]);
                    HudStream.setTargetFps(fps);
                    mc.options.getMaxFps().setValue(MathHelper.clamp((int) Math.ceil(fps * 2.0 / 10.0) * 10, 30, 250));
                }
            }
            case "time" -> ServerSide.setTime(Long.parseLong(a[1]));
            case "cars" -> CarColliders.update(a);
            case "geo" -> GeoColliders.update(a);
            case "geoclear" -> GeoColliders.clear();
            case "carhit" -> ServerSide.carHit(mc, Float.parseFloat(a[1]), d(a[2]), d(a[3]), d(a[4]));
            case "userpath" ->TextureExport.setBeamNGUserFolder(line.substring(9));
            case "chattext" -> chatText(mc, line.length() > 9 ? line.substring(9) : "");
            case "chatsend" -> chatSend(mc);
            case "chars" -> typeChars(mc, a);
            case "key" -> typeKey(mc, Integer.parseInt(a[1]));
            case "mouse" -> mouse(mc, a);
            case "show" -> show(mc, a.length > 1 ? a[1] : "menu");
            case "bye" -> {
                onFoot = false;
                Bridge.disconnect();
            }
            default -> LOG.debug("BeamCraft: unknown message {}", a[0]);
        }
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }

    private static void press(MinecraftClient mc, String[] a) {
        if (a[1].equals("close")) {
            if (mc.currentScreen != null) mc.currentScreen.close();
            return;
        }
        if (!onFoot || mc.player == null || mc.currentScreen != null) return;
        GameOptions o = mc.options;
        LOG.debug("BeamCraft press {} target={}", a[1], mc.crosshairTarget == null ? null : mc.crosshairTarget.getType());
        switch (a[1]) {
            case "attack" -> {
                tap(o.attackKey);
                if (!carTargeted()) ticksSinceSwing = 0;
            }
            case "use" -> {
                usePresses = Math.min(usePresses + 1, 2);
                tap(o.useKey);
            }
            case "inventory" -> tap(o.inventoryKey);
            // chat is drawn into BeamNG's overlay; BeamNG sends the typed text with chattext/chatsend
            case "chat" -> mc.setScreen(new ChatScreen(""));
            case "command" -> mc.setScreen(new ChatScreen("/"));
            case "drop" -> tap(o.dropKey);
            case "swap" -> tap(o.swapHandsKey);
            case "perspective" -> tap(o.togglePerspectiveKey);
            case "slot" -> mc.player.getInventory().selectedSlot = MathHelper.clamp(Integer.parseInt(a[2]), 0, 8);
            case "scroll" -> mc.player.getInventory().scrollInHotbar(Double.parseDouble(a[2]));
            default -> { }
        }
    }

    /** Queue one key press exactly like a real keyboard press would. */
    private static void tap(KeyBinding key) {
        KeyBindingAccessor acc = (KeyBindingAccessor) key;
        acc.beamcraft$setTimesPressed(acc.beamcraft$getTimesPressed() + 1);
    }

    /**
     * {@code mouse x y buttons scroll}: BeamNG's cursor (0..1 across the screen) and buttons
     * (bit 0 left, bit 1 right), fed to Minecraft as real mouse events while a screen is open.
     */
    public static final java.util.concurrent.atomic.AtomicInteger mouseEvents = new java.util.concurrent.atomic.AtomicInteger();

    private static void mouse(MinecraftClient mc, String[] a) {
        mouseEvents.incrementAndGet();
        if (mc.currentScreen == null || !windowHidden) {
            mouseButtons = 0;
            return;
        }
        long handle = mc.getWindow().getHandle();
        MouseInvoker m = (MouseInvoker) mc.mouse;
        m.beamcraft$onCursorPos(handle, Double.parseDouble(a[1]) * mc.getWindow().getWidth(),
                Double.parseDouble(a[2]) * mc.getWindow().getHeight());
        int buttons = Integer.parseInt(a[3]);
        for (int b = 0; b < 2; b++) {
            boolean down = (buttons & (1 << b)) != 0, was = (mouseButtons & (1 << b)) != 0;
            if (down != was) m.beamcraft$onMouseButton(handle, b, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        }
        mouseButtons = buttons;
        double scroll = a.length > 4 ? Double.parseDouble(a[4]) : 0;
        if (scroll != 0) m.beamcraft$onMouseScroll(handle, 0, scroll);
    }

    /**
     * BeamNG asks for a window size (same shape as its screen) and field of view, so the hand,
     * Steve and the GUI Minecraft draws line up with BeamNG's picture.
     */
    private static void resizeWindow(MinecraftClient mc, int width, int height, int fov) {
        width = MathHelper.clamp(width, 320, 3840);
        height = MathHelper.clamp(height, 240, 2160);
        mc.options.getFov().setValue(MathHelper.clamp(fov, 30, 110));
        mc.options.getFovEffectScale().setValue(0.0); // sprint/fly zoom would make the hand drift from BeamNG's view
        if (width == mc.getWindow().getWidth() && height == mc.getWindow().getHeight()) return;
        mc.getWindow().setWindowedSize(width, height);
    }

    /** {@code chattext <text>}: what the player has typed so far in BeamNG's chat box. */
    private static void chatText(MinecraftClient mc, String text) {
        if (mc.currentScreen instanceof ChatScreen chat) {
            ((ChatScreenAccessor) chat).beamcraft$getChatField().setText(text);
        }
    }

    /** {@code chars <codepoint>...}: characters typed in BeamNG while a Minecraft text box has focus. */
    private static void typeChars(MinecraftClient mc, String[] a) {
        if (mc.currentScreen == null) return;
        KeyboardInvoker kb = (KeyboardInvoker) mc.keyboard;
        long handle = mc.getWindow().getHandle();
        for (int i = 1; i < a.length; i++) kb.beamcraft$onChar(handle, Integer.parseInt(a[i]), 0);
    }

    /** {@code key <glfwKey>}: Backspace, Enter, Esc, arrows... pressed while typing. */
    private static void typeKey(MinecraftClient mc, int key) {
        if (mc.currentScreen == null) return;
        long handle = mc.getWindow().getHandle();
        int scancode = GLFW.glfwGetKeyScancode(key);
        mc.keyboard.onKey(handle, key, scancode, GLFW.GLFW_PRESS, 0);
        mc.keyboard.onKey(handle, key, scancode, GLFW.GLFW_RELEASE, 0);
    }

    /** A text box in the open screen has focus (creative search, anvil name...), so keys mean typing. */
    private static boolean typingInScreen(MinecraftClient mc) {
        if (mc.currentScreen instanceof CreativeInventoryScreen creative) {
            TextFieldWidget search = ((CreativeInventoryScreenAccessor) creative).beamcraft$getSearchBox();
            if (search != null && search.isVisible() && search.isFocused()) return true;
        }
        return mc.currentScreen != null && mc.currentScreen.getFocused() instanceof TextFieldWidget field && field.isFocused();
    }

    /** {@code chatsend}: Enter pressed - send the message or run the command, like vanilla. */
    private static void chatSend(MinecraftClient mc) {
        if (mc.currentScreen instanceof ChatScreen chat) {
            chat.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        }
    }

    /** Bring the Minecraft window up for things that need typing: chat, commands, pause menu. */
    private static void show(MinecraftClient mc, String what) {
        if (mc.player == null) return;
        showRequested = true;
        showGrace = 5;
        switch (what) {
            case "inventory" -> tap(mc.options.inventoryKey);
            case "chat" -> mc.setScreen(new ChatScreen(""));
            case "command" -> mc.setScreen(new ChatScreen("/"));
            default -> mc.openGameMenu(false);
        }
    }

    private static void autoLoadWorld(MinecraftClient mc) {
        // A brand-new instance shows the accessibility onboarding screen instead of the title screen.
        boolean onTitle = mc.currentScreen instanceof TitleScreen || mc.currentScreen instanceof AccessibilityOnboardingScreen;
        if (autoLoadTried || mc.world != null || !onTitle) return;
        autoLoadTried = true;
        if (mc.getLevelStorage().levelExists(WORLD_NAME)) {
            LOG.info("BeamCraft: BeamNG connected, opening world '{}'", WORLD_NAME);
            mc.createIntegratedServerLoader().start(WORLD_NAME, () -> mc.setScreen(new TitleScreen()));
        } else {
            LOG.info("BeamCraft: creating void world '{}'", WORLD_NAME);
            LevelInfo info = new LevelInfo(WORLD_NAME, GameMode.CREATIVE, false, Difficulty.NORMAL, true,
                    new GameRules(), DataConfiguration.SAFE_MODE);
            GeneratorOptions options = new GeneratorOptions(GeneratorOptions.getRandomSeed(), false, false);
            mc.createIntegratedServerLoader().createAndStart(WORLD_NAME, info, options, registries -> {
                // Nothing but air: BeamNG provides the ground.
                FlatChunkGeneratorConfig voidConfig = registries.get(RegistryKeys.FLAT_LEVEL_GENERATOR_PRESET)
                        .getOrThrow(FlatLevelGeneratorPresets.THE_VOID).settings();
                return WorldPresets.createDemoOptions(registries).with(registries, new FlatChunkGenerator(voidConfig));
            }, new TitleScreen());
        }
    }

    // ---------------------------------------------------------------- window + input

    private static void updateWindow(MinecraftClient mc, boolean connected) {
        if (showRequested) {
            if (mc.currentScreen != null) showGrace = 0;
            else if (--showGrace < 0) showRequested = false;
        }
        // Minecraft pauses itself when its window loses focus (e.g. when BeamNG starts in front of it).
        // While BeamNG is playing, nobody can see or click that pause menu, so close it.
        if (connected) mc.options.pauseOnLostFocus = false;
        if (connected && !showRequested && mc.currentScreen instanceof GameMenuScreen) mc.setScreen(null);
        String screen = mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName();
        if (!screen.equals(lastScreen)) {
            LOG.info("BeamCraft: screen {}", screen);
            lastScreen = screen;
        }
        if (connected || !(mc.currentScreen instanceof GameMenuScreen)) pausedByUs = false;
        // Screens like the inventory stay hidden too: they are drawn into BeamNG's overlay instead.
        boolean wantHidden = connected && mc.world != null && mc.player != null
                && !showRequested && !windowMode.equals("show");
        long handle = mc.getWindow().getHandle();
        if (wantHidden && !windowHidden) {
            mc.options.pauseOnLostFocus = false;
            // vsync would tie Minecraft to the monitor; BeamNG's "size" message sets the real frame cap
            mc.options.getEnableVsync().setValue(false);
            mc.options.getMaxFps().setValue(90);
            if (windowMode.equals("minimize")) GLFW.glfwIconifyWindow(handle);
            else if (windowMode.equals("hide")) GLFW.glfwHideWindow(handle);
            else {
                // "offscreen" (default): Windows barely lets a hidden OpenGL window present frames,
                // which held Minecraft to ~6 fps. A shown window parked far off the desktop renders
                // at full speed and is never seen.
                int[] x = new int[1], y = new int[1];
                GLFW.glfwGetWindowPos(handle, x, y);
                savedX = x[0];
                savedY = y[0];
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowPos(handle, 20000, 20000);
            }
            windowHidden = true;
        } else if (!wantHidden && windowHidden) {
            if (windowMode.equals("minimize")) GLFW.glfwRestoreWindow(handle);
            else if (windowMode.equals("hide")) GLFW.glfwShowWindow(handle);
            else {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_TRUE);
                GLFW.glfwSetWindowPos(handle, savedX, savedY);
            }
            GLFW.glfwFocusWindow(handle);
            windowHidden = false;
            if (!connected && mc.world != null && mc.currentScreen == null) {
                mc.openGameMenu(false);
                pausedByUs = true;
            }
        }
    }

    /** If Steve is a little inside BeamNG's floor, put him back on top of it (see GeoColliders). */
    private static void unstick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || !beamngPhysics() || !GeoColliders.active() || p.hasVehicle() || p.getAbilities().flying) return;
        double y = GeoColliders.floorToClimbOnto(p.getBoundingBox(), p.getVelocity().y);
        if (Double.isNaN(y)) return;
        p.setPosition(p.getX(), y, p.getZ());
        p.setVelocity(p.getVelocity().x, 0, p.getVelocity().z);
        p.setOnGround(true);
        p.fallDistance = 0;
    }

    private static long clientTicks, lastItemUseTick = -100;

    /** Right-clicks BeamNG reported that haven't been used up yet. */
    private static int usePresses;

    /**
     * Right-click use/place at most once every 4 ticks while BeamNG is driving Steve. Doors,
     * trapdoors, gates, levers and buttons only react to a new click, never to the button being
     * held: BeamNG's input reaches Minecraft with some delay, so a normal click could otherwise
     * last long enough to open the door and shut it again straight away.
     */
    public static boolean allowItemUse() {
        if (!isDriving()) return true;
        if (clientTicks - lastItemUseTick < 4) return false;
        if (aimingAtToggle(MinecraftClient.getInstance())) {
            if (usePresses <= 0) return false;
        }
        usePresses = Math.max(0, usePresses - 1);
        lastItemUseTick = clientTicks;
        return true;
    }

    private static boolean aimingAtToggle(MinecraftClient mc) {
        if (!(mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit)
                || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK || mc.world == null) return false;
        if (mc.player.isSneaking() && !mc.player.getMainHandStack().isEmpty()) return false; // places instead
        net.minecraft.block.Block b = mc.world.getBlockState(hit.getBlockPos()).getBlock();
        return b instanceof net.minecraft.block.DoorBlock || b instanceof net.minecraft.block.TrapdoorBlock
                || b instanceof net.minecraft.block.FenceGateBlock || b instanceof net.minecraft.block.LeverBlock
                || b instanceof net.minecraft.block.ButtonBlock;
    }

    /** Minecraft's window is hidden behind BeamNG: draw only the HUD, never the world. */
    public static boolean hudOnly() {
        return windowHidden && MinecraftClient.getInstance().world != null;
    }

    /** BeamNG is showing the HUD overlay (Steve is on foot). */
    public static boolean streamingHud() {
        return hudOnly() && onFoot;
    }

    /**
     * Steve walks on BeamNG's geometry (not the barrier blocks) the whole time he's on foot - also
     * while a menu is open, or he would settle onto the barriers and fall through when it closes.
     */
    public static boolean beamngPhysics() {
        return onFoot && (windowHidden || windowMode.equals("show")) && MinecraftClient.getInstance().player != null;
    }

    /** True while BeamNG is the one playing (window hidden, Steve on foot). */
    public static boolean isDriving() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return onFoot && (windowHidden || windowMode.equals("show")) && mc.player != null && mc.currentScreen == null;
    }

    private static void applyInput(MinecraftClient mc) {
        boolean drive = isDriving();
        if (!drive && !ownsKeys) return;
        GameOptions o = mc.options;
        int k = drive ? keys | keysLatched : 0;
        keysLatched = 0;
        o.forwardKey.setPressed((k & K_FWD) != 0);
        o.backKey.setPressed((k & K_BACK) != 0);
        o.leftKey.setPressed((k & K_LEFT) != 0);
        o.rightKey.setPressed((k & K_RIGHT) != 0);
        o.jumpKey.setPressed((k & K_JUMP) != 0);
        o.sneakKey.setPressed((k & K_SNEAK) != 0);
        o.sprintKey.setPressed((k & K_SPRINT) != 0);
        o.attackKey.setPressed((k & K_ATTACK) != 0);
        o.useKey.setPressed((k & K_USE) != 0);
        ownsKeys = drive;
        if (drive) {
            mc.player.setYaw(yaw);
            mc.player.setPitch(MathHelper.clamp(pitch, -90f, 90f));
        }
    }

    // ---------------------------------------------------------------- car combat

    /**
     * A BeamNG car is under the crosshair and nothing of Minecraft's is in front of it (BeamNG can't
     * see the player's blocks, e.g. a house wall or door between Steve and his parked car).
     */
    public static boolean carTargeted() {
        if (!carTargeted || !isDriving()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && !mc.world.getBlockState(hit.getBlockPos()).isOf(Blocks.BARRIER)) {
            double toBlock = hit.getPos().distanceTo(mc.player.getEyePos());
            if (toBlock < carDistance) return false;
        }
        return true;
    }

    /**
     * The client never applies held-item attribute bonuses to the player (only the server does),
     * so read attack damage and attack speed straight from the item.
     */
    private static double[] weaponStats(ClientPlayerEntity p, ItemStack held) {
        double damage = p.getAttributeBaseValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        double speed = p.getAttributeBaseValue(EntityAttributes.GENERIC_ATTACK_SPEED);
        double[] add = {0, 0}, mulBase = {0, 0}, mulTotal = {1, 1};
        held.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, mod) -> {
            int i = attribute.equals(EntityAttributes.GENERIC_ATTACK_DAMAGE) ? 0
                    : attribute.equals(EntityAttributes.GENERIC_ATTACK_SPEED) ? 1 : -1;
            if (i < 0) return;
            switch (mod.operation()) {
                case ADD_VALUE -> add[i] += mod.value();
                case ADD_MULTIPLIED_BASE -> mulBase[i] += mod.value();
                case ADD_MULTIPLIED_TOTAL -> mulTotal[i] *= 1 + mod.value();
            }
        });
        return new double[] {
            (damage + add[0]) * (1 + mulBase[0]) * mulTotal[0],
            Math.max(0.1, (speed + add[1]) * (1 + mulBase[1]) * mulTotal[1])
        };
    }

    private static float cooldownProgress(ClientPlayerEntity p) {
        double ticksPerSwing = 20.0 / weaponStats(p, p.getMainHandStack())[1];
        return (float) MathHelper.clamp((ticksSinceSwing + 0.5) / ticksPerSwing, 0, 1);
    }

    /** Called instead of vanilla's attack when the crosshair is on a BeamNG car. */
    public static boolean attackCar(MinecraftClient mc) {
        if (!carTargeted()) return false;
        ClientPlayerEntity p = mc.player;
        ItemStack held = p.getMainHandStack();
        float damage = (float) weaponStats(p, held)[0];
        try {
            var sharpness = p.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.SHARPNESS);
            if (sharpness.isPresent()) {
                int level = EnchantmentHelper.getLevel(sharpness.get(), held);
                if (level > 0) damage += 0.5f * level + 0.5f;
            }
        } catch (RuntimeException ignored) {
            // enchantment registry not ready; plain damage is fine
        }
        float cooldown = cooldownProgress(p);
        damage *= 0.2f + cooldown * cooldown * 0.8f;
        ticksSinceSwing = 0;
        boolean crit = cooldown > 0.9f && p.fallDistance > 0 && !p.isOnGround() && !p.isClimbing()
                && !p.isTouchingWater() && !p.hasVehicle();
        if (crit) damage *= 1.5f;
        Bridge.send(String.format(Locale.ROOT, "hit %.3f %d %s", damage, crit ? 1 : 0,
                Registries.ITEM.getId(held.getItem()).getPath()));
        p.swingHand(Hand.MAIN_HAND);
        p.resetLastAttackedTicks();
        return true;
    }

    // ---------------------------------------------------------------- outgoing

    private static void endTick(MinecraftClient mc) {
        if (!Bridge.connected()) return;
        tick++;
        BlockSync.drainTo(mc);
        ClientPlayerEntity p = mc.player;
        if (p != null && mc.interactionManager != null) {
            // Like vanilla: switching to a different item restarts the attack cooldown.
            ItemStack held = p.getMainHandStack();
            if (!ItemStack.areItemsEqual(held, lastHeld)) ticksSinceSwing = 0;
            else ticksSinceSwing++;
            lastHeld = held.copy();
            Bridge.send(String.format(Locale.ROOT,
                    "st %.4f %.4f %.4f %.2f %.2f %.3f %d %.1f %.1f %d %d %d %d %d %.3f %.2f %d %d %d",
                    p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), p.getStandingEyeHeight(),
                    p.isOnGround() ? 1 : 0, p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(),
                    p.getInventory().selectedSlot, mc.interactionManager.getCurrentGameMode().getId(),
                    mc.options.getPerspective().ordinal(), p.isSneaking() ? 1 : 0,
                    cooldownProgress(p), p.getEntityInteractionRange(), p.experienceLevel,
                    // 0 no screen, 1 a screen (inventory...), 2 chat, 3 a screen with a text box being typed in
                    mc.currentScreen == null ? 0 : mc.currentScreen instanceof ChatScreen ? 2 : typingInScreen(mc) ? 3 : 1,
                    // tick number, so BeamNG can replay positions smoothly however late packets arrive
                    p.age));
            if (tick % 5 == 0) sendHotbar(p);
            if (tick % 20 == 0) GeoColliders.prune(p.getX(), p.getY(), p.getZ());
        } else if (tick % 20 == 0) {
            Bridge.send((mc.world == null ? "status menu " : "status loading ")
                    + (mc.currentScreen == null ? "-" : mc.currentScreen.getClass().getSimpleName()));
        }
        Bridge.flush();
    }

    private static void sendHotbar(ClientPlayerEntity p) {
        StringBuilder sb = new StringBuilder("hb");
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            sb.append(' ');
            if (s.isEmpty()) sb.append('-');
            else sb.append(Registries.ITEM.getId(s.getItem()).getPath()).append(':').append(s.getCount());
        }
        String hb = sb.toString();
        if (!hb.equals(lastHotbar) || tick % 100 == 0) {
            lastHotbar = hb;
            Bridge.send(hb);
        }
    }
}
