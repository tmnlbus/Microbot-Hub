package net.runelite.client.plugins.microbot.vardorvis;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.vardorvis.enums.State;
import net.runelite.client.plugins.microbot.vardorvis.enums.StateBank;
import net.runelite.client.plugins.microbot.vardorvis.enums.StatePOH;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.vardorvis.VardorvisScript.*;

@PluginDescriptor(
        name = PluginConstants.DEFAULT_PREFIX + "Vardorvis",
        description = "Vardorvis killer",
        tags = {"vardorvis", "boss", "microbot"},
        enabledByDefault = PluginConstants.DEFAULT_ENABLED
)
@Slf4j
public class VardorvisPlugin extends Plugin {

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;
    private ExecutorService walkingExecutor;

    private static final int RANGE_PROJECTILE = 1343;

    private final Set<Integer> trackedWidgets = Set.of(
            54591499, 54591498, 54591497, 54591496, 54591495, 54591494);

    private int tickCounter = 0;

    public static boolean oppositeAxe = false;
    public static int oppositeAxeCounter = 0;
    public static int currentRunningTicks = 0;
    public static Rs2PrayerEnum currentPrayer = null;
    public static boolean axeOnSafeTile = false;
    private static int axeOnSafeTileTick = 0;

    @Inject private VardorvisConfig config;

    @Provides
    VardorvisConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(VardorvisConfig.class);
    }

    @Inject private OverlayManager overlayManager;
    @Inject private VardorvisOverlay vardorvisOverlay;
    @Inject VardorvisScript vardorvisScript;

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) overlayManager.add(vardorvisOverlay);
        vardorvisScript.run(config);
        executor        = Executors.newSingleThreadExecutor();
        scheduler       = Executors.newScheduledThreadPool(1);
        walkingExecutor = Executors.newSingleThreadExecutor();
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        currentRunningTicks++;

        if (Microbot.getClient().isPrayerActive(Prayer.PROTECT_FROM_MELEE)
                && currentPrayer != Rs2PrayerEnum.PROTECT_MELEE) {
            currentPrayer = Rs2PrayerEnum.PROTECT_MELEE;
        } else if (Microbot.getClient().isPrayerActive(Prayer.PROTECT_FROM_MISSILES)
                && currentPrayer != Rs2PrayerEnum.PROTECT_RANGE) {
            currentPrayer = Rs2PrayerEnum.PROTECT_RANGE;
        }

        // Guard against null return from getInventoryFood on older API versions
        List<?> food = Rs2Inventory.getInventoryFood();
        if ((food == null || food.isEmpty())
                && Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < 30) {
            Microbot.log("Emergency teleport - low HP and no food!");
            Rs2Inventory.interact("Teleport to house", "Break");
            state = State.POH;
            sleep(4000);
        }

        if (Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < 20) handleHealingAndPrayer();
        if (Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) < 10)  handleHealingAndPrayer();

        if (axeOnSafeTile && ++axeOnSafeTileTick >= 3) {
            axeOnSafeTileTick = 0;
            axeOnSafeTile = false;
        }

        if (oppositeAxe && ++oppositeAxeCounter == 9) oppositeAxe = false;

        if (currentPrayer != Rs2PrayerEnum.PROTECT_MELEE
                && !VardorvisScript.isProjectileActive
                && VardorvisScript.inFight
                && VardorvisScript.inInstance) {
            Microbot.log("Toggling Protect Melee ON | tick=" + currentRunningTicks);
            walkingExecutor.submit(() -> Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true));
        }

        WorldPoint avoidTile   = new WorldPoint(1131, 3421, 0);
        WorldPoint defaultTile = new WorldPoint(1129, 3423, 0);
        WorldPoint pos = Rs2Player.getWorldLocation();

        if (pos.equals(avoidTile)) {
            // on avoid tile - nothing extra needed
        } else if (pos.equals(defaultTile)) {
            int pose = Rs2Player.getPoseAnimation();
            if (pose == 1205 || pose == 1210) {
                Microbot.log("Healing | pose=" + pose + " tick=" + currentRunningTicks);
                handleHealingAndPrayer();
                walkingExecutor.submit(() -> {
                    boolean ok = sleepUntil(() ->
                            !Rs2Player.isWalking()
                            && !Rs2Player.isAnimating(200)
                            && !isProjectileActive
                            && !oppositeAxe, 2400);
                    Microbot.getClientThread().invokeLater(() -> {
                        if (ok) {
                            if (config.useSpec() && Rs2Combat.getSpecEnergy() >= 500) {
                                Microbot.log("Speccing after heal | tick=" + currentRunningTicks);
                                specVardorvis();
                            } else {
                                Microbot.log("Attacking after heal | tick=" + currentRunningTicks);
                                Rs2Npc.interact(12223, "Attack");
                            }
                        }
                    });
                });
            }
        } else if (VardorvisScript.inFight) {
            NPC vard = Rs2Npc.getNpc("Vardorvis");
            if (vard != null && vard.getHealthRatio() != -1) {
                Microbot.log("Off safe tile - walking back | tick=" + currentRunningTicks);
                walkingExecutor.submit(() -> Rs2Walker.walkFastCanvas(defaultTile));
                handleHealingAndPrayer();
            }
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick) {
        if (isBloodSplatsVisible()) clickingBloodSplats();

        WorldPoint avoidTile   = new WorldPoint(1131, 3421, 0);
        WorldPoint defaultTile = new WorldPoint(1129, 3423, 0);
        WorldPoint pos = Rs2Player.getWorldLocation();

        if (pos.equals(avoidTile)) tickCounter++;
        else if (pos.equals(defaultTile)) tickCounter = 0;

        if (tickCounter >= 30 && !axeOnSafeTile) {
            Microbot.log("Returning to default tile | tick=" + currentRunningTicks);
            walkingExecutor.submit(() -> Rs2Walker.walkFastCanvas(defaultTile));
            tickCounter = 0;
        }

        checkAxeLocations();
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (npc.getId() != 12225) return;
        int lx = npc.getLocalLocation().getX();
        int ly = npc.getLocalLocation().getY();
        // Local coords 8384/6080 = axe on safe tile; 8384/4800 = opposite axe
        if (lx == 8384 && ly == 6080) {
            Microbot.log("Axe on safe tile - dodging | tick=" + currentRunningTicks);
            walkingExecutor.submit(() -> Rs2Walker.walkFastCanvas(new WorldPoint(1131, 3421, 0)));
            axeOnSafeTile = true;
        }
        if (lx == 8384 && ly == 4800) oppositeAxe = true;
    }

    @Subscribe
    public void onChatMessage(ChatMessage message) {
        if (!message.getMessage().contains("manage to escape")) return;
        handleHealingAndPrayer();
        if (!VardorvisScript.isProjectileActive && Rs2Player.isAnimating() && Rs2Player.isWalking()) {
            Microbot.log("Attacking after blood splats | tick=" + currentRunningTicks);
            Rs2Npc.interact(12223, "Attack");
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        if (VardorvisScript.inFight) handleProjectile(event);
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        if (!Objects.equals(event.getNpc().getName(), "Vardorvis")) return;
        if (!inFight || !inInstance) return;
        Microbot.log("Vardorvis dead!");
        VardorvisScript.inFight = false;
        VardorvisScript.inInstance = false;
        Rs2Prayer.toggleQuickPrayer(false);
        state = State.AFTER_FIGHT;
    }

    private void specVardorvis() {
        int currentSpec = Rs2Combat.getSpecEnergy();
        walkingExecutor.submit(() -> {
            Rs2Inventory.wield(config.specWeaponId());
            Rs2Combat.setSpecState(true);
            sleep(50);
            Rs2Npc.interact(12223, "Attack");
            sleepUntil(() -> Rs2Combat.getSpecEnergy() != currentSpec);
            Rs2Inventory.wield(config.mainWeaponId());
        });
    }

    private boolean isBloodSplatsVisible() {
        for (int id : trackedWidgets) {
            if (Rs2Widget.isWidgetVisible(id)) return true;
        }
        return false;
    }

    public void clickWidgetWithDelay(Widget widget, int delayMs) {
        executor.submit(() -> {
            try {
                Thread.sleep(delayMs);
                if (Rs2Widget.isWidgetVisible(widget.getId())) {
                    Microbot.log("Clicking widget | tick=" + currentRunningTicks);
                    Rs2Widget.clickWidget(widget);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public void clickingBloodSplats() {
        for (int id : trackedWidgets) {
            if (Rs2Widget.isWidgetVisible(id)) {
                Widget w = Rs2Widget.getWidget(id);
                if (w != null) clickWidgetWithDelay(w, Rs2Random.between(30, 90));
            }
        }
    }

    public void handleProjectile(ProjectileMoved event) {
        final Projectile p = event.getProjectile();
        final int cycles  = p.getRemainingCycles();

        if (cycles >= 10 && cycles < 25) {
            VardorvisScript.isProjectileActive = true;
            if (p.getId() == RANGE_PROJECTILE
                    && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE)
                    && currentPrayer != Rs2PrayerEnum.PROTECT_RANGE
                    && (cycles == 20 || cycles == 10)) {
                Microbot.log("Protect Range ON | cycles=" + cycles + " tick=" + currentRunningTicks);
                walkingExecutor.submit(() -> Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true));
            }
        } else if (cycles >= 0 && cycles <= 2 && isProjectileActive) {
            Microbot.log("Projectile done - back to melee | tick=" + currentRunningTicks);
            VardorvisScript.isProjectileActive = false;
            walkingExecutor.submit(() -> Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true));
        }
    }

    public void checkAxeLocations() {
        Rs2Npc.getNpcs(12227)
                .filter(npc -> npc.getLocalLocation().getX() == 8384
                                && npc.getLocalLocation().getY() == 5568)
                .forEach(this::handleAxeAtLocation);
    }

    public void handleAxeAtLocation(NPC axe) {
        if (!oppositeAxe) return;
        Microbot.log("Dodging opposite axe | tick=" + currentRunningTicks);
        walkingExecutor.submit(() -> Rs2Walker.walkFastCanvas(new WorldPoint(1131, 3421, 0)));
        oppositeAxe = false;
        oppositeAxeCounter = 0;
    }

    public void handleHealingAndPrayer() {
        Microbot.log("Healing | tick=" + currentRunningTicks);
        int hp     = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
        int prayer = Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER);
        if (prayer < maxPrayer - 30) Rs2Inventory.interact("prayer potion", "drink");
        if (hp < maxHealth - 45) {
            // Use getSlot() to find the slot index of each food item by ID
            int sharkSlot = Rs2Inventory.getSlot(385);
            int karambwanSlot = Rs2Inventory.getSlot(3144);
            if (sharkSlot != -1) {
                Microbot.doInvoke(
                        new NewMenuEntry("Eat", sharkSlot, 9764864,
                                MenuAction.CC_OP.getId(), 2, 385, "Shark"),
                        new Rectangle(1, 1));
            }
            if (karambwanSlot != -1) {
                int delay = Rs2Random.between(30, 60);
                scheduler.schedule(() ->
                        Microbot.doInvoke(
                                new NewMenuEntry("Eat", karambwanSlot, 9764864,
                                        MenuAction.CC_OP.getId(), 2, 3144, "Cooked karambwan"),
                                new Rectangle(1, 1)),
                        delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    protected void shutDown() {
        vardorvisScript.shutdown();
        overlayManager.remove(vardorvisOverlay);
        shutdownExecutor(scheduler);
        shutdownExecutor(executor);
        shutdownExecutor(walkingExecutor);
        tickCounter = 0;
        oppositeAxeCounter = 0;
        currentRunningTicks = 0;
        currentPrayer = null;
        axeOnSafeTile = false;
        axeOnSafeTileTick = 0;
        VardorvisScript.inFight = false;
        VardorvisScript.inInstance = false;
        VardorvisScript.state = State.UNKNOWN;
        VardorvisScript.bankState = StateBank.UNKNOWN;
        VardorvisScript.POHState = StatePOH.UNKNOWN;
        VardorvisScript.isProjectileActive = false;
        VardorvisScript.maxHealth = 0;
        VardorvisScript.maxPrayer = 0;
    }

    private void shutdownExecutor(ExecutorService es) {
        if (es != null && !es.isShutdown()) es.shutdownNow();
    }
}
