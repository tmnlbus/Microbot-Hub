package net.runelite.client.plugins.microbot.barrows;

import com.google.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.player.Rs2PlayerCache;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemCache;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.JewelleryLocationEnum;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * BarrowsScript v2.0.12
 * Fix 1: inTunnels Y-check now preserves true when player is on plane=3
 *         (prevents next-tick override clearing inTunnels during mound→tunnel transition).
 * Fix 2: outOfSupplies() at top of loop is now guarded by !inTunnels so a low-supply
 *         check can never fire a Ferox teleport the moment we enter the tunnels.
 * Fix 3: inTunnels declared volatile for correct cross-thread visibility.
 *         Without volatile the JVM may cache the field per-thread; BarrowsScript-2
 *         was reading stale false on the tick after BarrowsScript-1 wrote true inside
 *         dialogueEnterTunnels(), which caused the immediate bank trip seen in logs.
 */
public class BarrowsScript extends Script {

    public static final String VERSION = "2.0.12";

    public static volatile boolean inTunnels = false;
    public static boolean outOfPoweredStaffCharges = false;
    public static boolean usingPoweredStaffs = false;
    public static boolean firstRun = false;

    private boolean shouldBank = false;
    private boolean shouldAttackSkeleton = false;
    private boolean varbitCheckEnabled = true;

    public static String WhoisTun = "Unknown";
    public String neededRune = "unknown";

    private int tunnelLoopCount = 0;
    int scriptDelay = Rs2Random.between(300, 600);
    public static int ChestsOpened = 0;
    private int minRuneAmt;
    private int minForgottenBrews = 0;

    long walkerDelay = Rs2Random.between(1000, 2000);

    private WorldPoint FirstLoopTile;
    private WorldPoint Chest = new WorldPoint(3552, 9694, 0);

    private Rs2PrayerEnum NeededPrayer;
    public static List<String> barrowsPieces = new ArrayList<>();
    private ScheduledFuture<?> WalkToTheChestFuture;

    @Inject
    Rs2NpcCache rs2NpcCache;
    @Inject Rs2TileItemCache rs2TileItemCache;
    @Inject Rs2PlayerCache rs2PlayerCache;
    @Inject Rs2TileObjectCache rs2TileObjectCache;

    // -------------------------------------------------------------------------
    // Debug helpers
    // -------------------------------------------------------------------------

    /** Logs the full state of the dialogue system so we can see exactly what
     *  Rs2Dialogue reports at any given moment. Call this anywhere dialogue
     *  is expected but not firing correctly. */
    private void debugLogDialogueState(String context) {
        Microbot.log("[DEBUG][" + context + "] isInDialogue=" + Rs2Dialogue.isInDialogue()
                + " hasContinue=" + Rs2Dialogue.hasContinue()
                + " hasOption(fearless)=" + Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!")
                + " hasText(hidden)=" + Rs2Dialogue.hasDialogueText("You've found a hidden")
                + " playerLoc=" + Rs2Player.getWorldLocation()
                + " plane=" + Rs2Player.getWorldLocation().getPlane()
                + " inTunnels=" + inTunnels
                + " WhoisTun=" + WhoisTun);
    }

    /** Dumps every visible widget text that Rs2Widget can find — useful for
     *  identifying the exact widget/text when dialogue appears stuck. */
    private void debugLogWidgetSnapshot(String context) {
        Microbot.log("[DEBUG][" + context + "] Widget snapshot start ---");
        // Log the three known puzzle-door widget IDs for cross-reference
        int[] knownWidgets = {1638413, 1638415, 1638417};
        for (int wid : knownWidgets) {
            var w = Rs2Widget.getWidget(wid);
            Microbot.log("[DEBUG] widget " + wid + " = " + (w != null ? "present text='" + w.getText() + "'" : "null"));
        }
        Microbot.log("[DEBUG][" + context + "] Widget snapshot end ---");
    }

    // -------------------------------------------------------------------------
    // Main run loop
    // -------------------------------------------------------------------------

    public boolean run(BarrowsConfig config, BarrowsPlugin plugin) {
        Microbot.log("[DEBUG] BarrowsScript " + VERSION + " starting.");
        Microbot.enableAutoRunOn = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                long startTime = System.currentTimeMillis();

                var inventorySetup = new Rs2InventorySetup(config.inventorySetup().getName(), mainScheduledFuture);

                if (firstRun) {
                    if (!inventorySetup.doesEquipmentMatch()) {
                        while (!inventorySetup.doesEquipmentMatch()) {
                            if (!super.isRunning()) break;
                            if (Rs2Bank.getNearestBank().getWorldPoint().distanceTo(Rs2Player.getWorldLocation()) > 6) {
                                Rs2Bank.walkToBank();
                            }
                            if (Rs2Bank.getNearestBank().getWorldPoint().distanceTo(Rs2Player.getWorldLocation()) <= 6) {
                                inventorySetup.loadEquipment();
                            }
                        }
                    }
                    firstRun = false;
                }

                if (barrowsPieces.isEmpty()) barrowsPieces.add("Nothing yet.");

                // FIX 1: Don't clear inTunnels while player is still on plane=3 (mid-transition).
                // Previously this block unconditionally set inTunnels=false whenever Y was outside
                // 9600-9730, which happened on the very next tick after dialogueEnterTunnels() set
                // inTunnels=true because the player was still physically inside the mound (plane=3)
                // and hadn't walked into the tunnel Y-range yet.
                if (Rs2Player.getWorldLocation().getY() > 9600 && Rs2Player.getWorldLocation().getY() < 9730) {
                    inTunnels = true;
                } else if (inTunnels && Rs2Player.getWorldLocation().getPlane() == 3) {
                    // Still on plane=3 (inside a mound / mid-transition) — preserve inTunnels=true
                    // so the script doesn't fall through to the bank/supply checks this tick.
                    Microbot.log("[DEBUG] inTunnels preserved — player on plane=3 during tunnel transition.");
                } else {
                    if (tunnelLoopCount != 0) tunnelLoopCount = 0;
                    inTunnels = false;
                }

                // powered staffs
                if (Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Trident of the") ||
                        Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Tumeken's") ||
                        Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("sceptre") ||
                        Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Sanguinesti") ||
                        Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Crystal staff")) {
                    usingPoweredStaffs = true;
                } else {
                    usingPoweredStaffs = false;
                    gettheRune();
                    minRuneAmt = config.minRuneAmount();
                    if (!Rs2Magic.getSpellbook().equals(Rs2Spellbook.MODERN)) {
                        swapTheSpellbook();
                        return;
                    }
                }

                minForgottenBrews = config.minForgottenBrew();
                shouldAttackSkeleton = config.shouldGainRP();

                if (usingPoweredStaffs) {
                    if (outOfPoweredStaffCharges) {
                        Microbot.log("No charges left on our staff. Stopping...");
                        super.shutdown();
                    }
                }

                // FIX 2: Only run the top-level supply check when we are NOT already in the tunnels.
                // Previously outOfSupplies() fired unconditionally here, which meant on the tick
                // immediately after entering the tunnels it would evaluate supplies (e.g. prayer pts)
                // and trigger a Ferox teleport before the tunnel loop even had a chance to run.
                // The tunnel loop already calls outOfSupplies() internally, so this guard is safe.
                if (!inTunnels) {
                    outOfSupplies(config);
                }

                if (config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() == ItemID.TELEPORT_TO_HOUSE) {
                    if (!inTunnels && !shouldBank && Rs2Player.getWorldLocation().distanceTo(new WorldPoint(3573, 3296, 0)) > 60) {
                        if (Rs2Bank.isOpen()) {
                            closeBank();
                            return;
                        }
                        if (rs2TileObjectCache.query().withId(4525).nearest() == null) {
                            Rs2Inventory.interact("Teleport to house", "Inside");
                            sleepUntil(() -> Rs2Player.getAnimation() == 4069, Rs2Random.between(2000, 4000));
                            sleepUntil(() -> !Rs2Player.isAnimating(), Rs2Random.between(6000, 10000));
                            sleepUntil(() -> rs2TileObjectCache.query().withId(4525).nearest() != null, Rs2Random.between(6000, 10000));
                        }
                        handlePOH(config);
                        return;
                    }
                }

                if (!inTunnels && !shouldBank) {

                    if (!BreakHandlerScript.lockState.get()) {
                        if (BreakHandlerScript.breakIn < 60 && BreakHandlerScript.breakIn != -1) {
                            Microbot.log("Going on break soon, doing nothing.");
                            return;
                        }
                    }

                    BreakHandlerScript.lockState.set(true);

                    for (BarrowsBrothers brother : BarrowsBrothers.values()) {
                        Rs2WorldArea mound = brother.getHumpWP();
                        NeededPrayer = brother.whatToPray;
                        outOfSupplies(config);
                        if (shouldBank) return;

                        stopFutureWalker();
                        closeBank();

                        if (!usingPoweredStaffs) setAutoCast();

                        Microbot.log("Checking mound for: " + brother.getName());

                        if (everyBrotherWasKilled()) {
                            if (WhoisTun.equals("Unknown")) {
                                Microbot.log("We're not sure who tunnel is, and every brother is dead. Checking all mounds manually");
                                varbitCheckEnabled = false;
                            }
                        } else {
                            if (!varbitCheckEnabled) varbitCheckEnabled = true;
                        }

                        if (!WhoisTun.equals("Unknown")) {
                            if (!varbitCheckEnabled) varbitCheckEnabled = true;
                        }

                        // resume progress from varbits
                        if (varbitCheckEnabled) {
                            if (brother.name.contains("Dharok") && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_DHAROK) == 1) { Microbot.log("We all ready killed Dharok."); continue; }
                            if (brother.name.contains("Guthan") && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_GUTHAN) == 1) { Microbot.log("We all ready killed Guthan."); continue; }
                            if (brother.name.contains("Karil")  && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_KARIL)  == 1) { Microbot.log("We all ready killed Karil.");  continue; }
                            if (brother.name.contains("Torag")  && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_TORAG)  == 1) { Microbot.log("We all ready killed Torag.");  continue; }
                            if (brother.name.contains("Verac")  && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_VERAC)  == 1) { Microbot.log("We all ready killed Verac.");  continue; }
                            if (brother.name.contains("Ahrim")  && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_AHRIM)  == 1) { Microbot.log("We all ready killed Ahrim.");  continue; }
                        }

                        // Enter mound
                        if (Rs2Player.getWorldLocation().getPlane() != 3) {
                            Microbot.log("Entering the mound");
                            handlePOH(config);
                            goToTheMound(mound);
                            digIntoTheMound(mound);
                        }

                        if (Rs2Player.getWorldLocation().getPlane() == 3) {
                            Microbot.log("We're in the mound");

                            if (config.shouldPrayAgainstWeakerBrothers()) {
                                activatePrayer(brother.getWhatToPray());
                            } else {
                                if (!brother.getName().contains("Torag") && !brother.getName().contains("Guthan") && !brother.getName().contains("Verac")) {
                                    activatePrayer(brother.getWhatToPray());
                                }
                            }

                            Rs2TileObjectModel sarc = rs2TileObjectCache.query().withIds(20770, 20720, 20722, 20771, 20721, 20772).nearest();
                            Rs2NpcModel currentBrother = null;
                            Microbot.log("Found the Sarcophagus");

                            while (currentBrother == null) {
                                Microbot.log("Searching the Sarcophagus");
                                if (!super.isRunning()) break;

                                if (sarc.click("Search")) {
                                    sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(1000, 3000));
                                    sleepUntil(() -> !Rs2Player.isMoving() || Rs2Player.isInCombat(), Rs2Random.between(3000, 6000));
                                    sleepUntil(() -> hintNpcModel() != null || Rs2Dialogue.isInDialogue(), Rs2Random.between(750, 1500));
                                }

                                // --- DEBUG: log dialogue state right after sarc search ---
                                debugLogDialogueState("sarc-search[" + brother.name + "]");

                                if (Rs2Dialogue.isInDialogue()) {
                                    Microbot.log("[DEBUG] Dialogue detected after sarc search for " + brother.name);
                                    Microbot.log("[DEBUG] hasContinue=" + Rs2Dialogue.hasContinue()
                                            + " hasText(hidden)=" + Rs2Dialogue.hasDialogueText("You've found a hidden")
                                            + " hasOption(fearless)=" + Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!"));
                                }

                                if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasDialogueText("You've found a hidden")) {
                                    WhoisTun = brother.name;
                                    Microbot.log(brother.name + " is our tunnel");
                                    Microbot.log("[DEBUG] Tunnel identified via sarc-search dialogue. WhoisTun=" + WhoisTun);
                                    break;
                                }

                                if (hintNpcModel() != null) {
                                    currentBrother = hintNpcModel();
                                } else {
                                    break;
                                }

                                if (currentBrother != null) break;
                            }

                            checkForAndFightBrother(config);

                            if (brother.name.equals(WhoisTun) && brother.name.contains("Ahrim")) {
                                Microbot.log("[DEBUG] Ahrim is tunnel brother — checking dialogue before leaving mound.");
                                debugLogDialogueState("ahrim-tun-check");
                                if (Rs2Dialogue.isInDialogue()) {
                                    Microbot.log("[DEBUG] Dialogue is active for Ahrim tunnel — calling dialogueEnterTunnels()");
                                    dialogueEnterTunnels();
                                    return;
                                } else {
                                    Microbot.log("[DEBUG] Ahrim is tunnel but no dialogue active — may need to search sarc again.");
                                }
                            }

                            leaveTheMound();
                        }
                    }
                }

                if (!WhoisTun.equals("Unknown") && !shouldBank && !inTunnels) {
                    int howManyBrothersWereKilled = Microbot.getVarbitValue(Varbits.BARROWS_KILLED_DHAROK)
                            + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_GUTHAN)
                            + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_KARIL)
                            + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_TORAG)
                            + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_VERAC)
                            + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_AHRIM);

                    Microbot.log("[DEBUG] Brothers killed varbits total=" + howManyBrothersWereKilled + " WhoisTun=" + WhoisTun);

                    if (howManyBrothersWereKilled <= 4) {
                        Microbot.log("We seem to have missed someone, checking all mounds again.");
                        return;
                    } else {
                        Microbot.log("Going to the tunnels.");
                    }

                    stopFutureWalker();
                    for (BarrowsBrothers brother : BarrowsBrothers.values()) {
                        if (brother.name.equals(WhoisTun)) {
                            NeededPrayer = brother.getWhatToPray();
                            Rs2WorldArea tunnelMound = brother.getHumpWP();

                            handlePOH(config);
                            goToTheMound(tunnelMound);
                            digIntoTheMound(tunnelMound);

                            Microbot.log("[DEBUG] Entered tunnel mound for " + brother.name + ". Waiting for dialogue.");

                            while (!Rs2Dialogue.isInDialogue()) {
                                Rs2TileObjectModel sarc = rs2TileObjectCache.query().withIds(20770, 20720, 20722, 20771, 20721, 20772).nearest();

                                if (!super.isRunning()) break;

                                if (sarc.click("Search")) {
                                    sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(1000, 3000));
                                    sleepUntil(() -> !Rs2Player.isMoving() || Rs2Player.isInCombat(), Rs2Random.between(3000, 6000));
                                    sleepUntil(() -> Rs2Dialogue.isInDialogue(), Rs2Random.between(3000, 6000));
                                }

                                // --- DEBUG: log state after each sarc click in tunnel loop ---
                                debugLogDialogueState("tunnel-sarc-loop[" + brother.name + "]");
                                debugLogWidgetSnapshot("tunnel-sarc-loop[" + brother.name + "]");

                                if (Rs2Dialogue.isInDialogue()) {
                                    Microbot.log("[DEBUG] Dialogue opened in tunnel sarc loop.");
                                    Microbot.log("[DEBUG] hasContinue=" + Rs2Dialogue.hasContinue()
                                            + " hasOption(fearless)=" + Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!")
                                            + " hasText(hidden)=" + Rs2Dialogue.hasDialogueText("You've found a hidden"));
                                    break;
                                }

                                if (inTunnels) break;
                                if (Rs2Player.getWorldLocation().getPlane() != 3) break;

                                if (!Rs2Dialogue.isInDialogue()) {
                                    Microbot.log("[DEBUG] Still no dialogue — wrong tun mound? Leaving.");
                                    Microbot.log("We're in the wrong tunnel mound. Leaving...");
                                    this.leaveTheMound();
                                    WhoisTun = "Unknown";
                                    return;
                                }
                            }

                            Microbot.log("[DEBUG] About to call dialogueEnterTunnels() for " + brother.name);
                            debugLogDialogueState("pre-dialogueEnterTunnels[" + brother.name + "]");
                            dialogueEnterTunnels();
                            break;
                        }
                    }
                }

                if (inTunnels && !shouldBank) {
                    Microbot.log("In the tunnels");

                    if (Rs2Player.getQuestState(Quest.HIS_FAITHFUL_SERVANTS) != QuestState.FINISHED) {
                        Microbot.showMessage("Complete the 'His Faithful Servants' quest for the webwalker to function correctly");
                        shutdown();
                        return;
                    }

                    if (!varbitCheckEnabled) varbitCheckEnabled = true;

                    leaveTheMound();
                    stuckInTunsCheck();
                    solvePuzzle();
                    checkForAndFightBrother(config);
                    eatFood();
                    outOfSupplies(config);
                    gainRP(config);
                    lootChampionScroll();

                    if (!Rs2Player.isMoving()) startWalkingToTheChest();

                    solvePuzzle();
                    checkForAndFightBrother(config);

                    Rs2TileObjectModel barrowsChest = rs2TileObjectCache.query().withId(20973).nearest();

                    if (barrowsChest != null &&
                            barrowsChest.getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) < 5) {
                        stopFutureWalker();

                        if (barrowsChest.click("Open")) {
                            sleepUntil(() -> hintNpcModel() != null && hintNpcModel().getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) <= 5, Rs2Random.between(4000, 6000));
                        } else {
                            return;
                        }

                        checkForAndFightBrother(config);

                        if (hintNpcModel() == null) {
                            int io = 0;
                            while (io < 2) {
                                if (!super.isRunning()) break;
                                if (barrowsChest.click("Search")) sleep(500, 1500);
                                if (Rs2Widget.hasWidget("Barrows chest")) break;
                                io++;
                            }

                            suppliesCheck(config);

                            if (shouldBank) {
                                Microbot.log("We should bank.");
                                ChestsOpened++;
                                WhoisTun = "Unknown";
                                inTunnels = false;
                            } else {
                                if (config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() == ItemID.BARROWS_TELEPORT) {
                                    Rs2Inventory.interact("Barrows teleport", "Break");
                                    sleepUntil(() -> Rs2Player.getWorldLocation().getY() < 9600 || Rs2Player.getWorldLocation().getY() > 9730, Rs2Random.between(6000, 10000));
                                    ChestsOpened++;
                                    WhoisTun = "Unknown";
                                    inTunnels = false;
                                } else {
                                    if (Rs2Bank.isOpen()) { closeBank(); return; }
                                    Rs2Inventory.interact("Teleport to house", "Inside");
                                    sleepUntil(() -> Rs2Player.getWorldLocation().getY() < 9600 || Rs2Player.getWorldLocation().getY() > 9730, Rs2Random.between(6000, 10000));
                                    ChestsOpened++;
                                    WhoisTun = "Unknown";
                                    inTunnels = false;
                                    handlePOH(config);
                                }
                            }
                        }
                    }
                    tunnelLoopCount++;
                }

                if (shouldBank) {
                    if (!Rs2Bank.isOpen()) {
                        stopFutureWalker();
                        outOfSupplies(config);
                        Rs2Bank.walkToBankAndUseBank(BankLocation.FEROX_ENCLAVE);
                        BreakHandlerScript.lockState.set(false);
                    } else {
                        Rs2Food ourfood = config.food();
                        int ourFoodsID = ourfood.getId();
                        String ourfoodsname = ourfood.getName();

                        if (Rs2Inventory.isFull() || Rs2Inventory.contains(it -> it != null && it.getName().contains("'s") || it.getName().contains("Coins"))) {
                            if (Rs2Inventory.contains(it -> it != null && it.getName().contains("'s"))) {
                                Rs2ItemModel piece = Rs2Inventory.get(it -> it != null && it.getName().contains("'s"));
                                if (piece != null) {
                                    barrowsPieces.add(piece.getName());
                                    if (barrowsPieces.contains("Nothing yet.")) barrowsPieces.remove("Nothing yet.");
                                }
                            }
                            Rs2Bank.depositAllExcept(neededRune, "Moonlight moth", "Moonlight moth mix (2)", "Teleport to house", "Spade",
                                    "Prayer potion(4)", "Prayer potion(3)", "Forgotten brew(4)", "Forgotten brew(3)",
                                    "Barrows teleport", ourfoodsname);
                        }

                        int howtoBank = Rs2Random.between(0, 100);
                        if (!usingPoweredStaffs) {
                            if (howtoBank <= 40) {
                                if (Rs2Inventory.get(neededRune) == null || Rs2Inventory.get(neededRune).getQuantity() <= config.minRuneAmount()) {
                                    if (Rs2Bank.getBankItem(neededRune) != null) {
                                        if (Rs2Bank.getBankItem(neededRune).getQuantity() > config.minRuneAmount()) {
                                            if (Rs2Bank.withdrawX(neededRune, Rs2Random.between(config.minRuneAmount(), Rs2Bank.getBankItem(neededRune).getQuantity()))) {
                                                String therune = neededRune;
                                                sleepUntil(() -> Rs2Inventory.get(therune).getQuantity() > config.minRuneAmount(), Rs2Random.between(2000, 4000));
                                            }
                                        }
                                    } else {
                                        if (neededRune.equals("Wrath rune")) {
                                            if (Rs2Bank.hasItem("Blood rune") && Rs2Bank.count("Blood rune") > config.minRuneAmount()) {
                                                neededRune = "Blood rune";
                                                return;
                                            }
                                        }
                                        Microbot.log("We're out of " + neededRune + "s. stopping...");
                                        super.shutdown();
                                    }
                                }
                            }
                        } else {
                            if (outOfPoweredStaffCharges) {
                                Microbot.log("We're out of staff charges. stopping...");
                                super.shutdown();
                            }
                        }

                        howtoBank = Rs2Random.between(0, 100);
                        if (howtoBank <= 60) {
                            if (Rs2Inventory.count(config.prayerRestoreType().getPrayerRestoreTypeID()) < Rs2Random.between(config.minPrayerPots(), config.targetPrayerPots())) {
                                if (Rs2Bank.getBankItem(config.prayerRestoreType().getPrayerRestoreTypeID()) != null) {
                                    if (Rs2Bank.getBankItem(config.prayerRestoreType().getPrayerRestoreTypeID()).getQuantity() >= config.targetPrayerPots()) {
                                        int amt = Rs2Random.between(config.minPrayerPots(), config.targetPrayerPots()) - Rs2Inventory.count(config.prayerRestoreType().getPrayerRestoreTypeID());
                                        if (amt <= 0) amt = 1;
                                        Microbot.log("Withdrawing " + amt);
                                        if (Rs2Bank.withdrawX(config.prayerRestoreType().getPrayerRestoreTypeID(), amt)) {
                                            sleepUntil(() -> Rs2Inventory.count(config.prayerRestoreType().getPrayerRestoreTypeID()) > Rs2Random.between(4, 8), Rs2Random.between(2000, 4000));
                                        }
                                    } else {
                                        Microbot.log("We're out of " + config.prayerRestoreType().getPrayerRestoreTypeID() + " need at least " + config.targetPrayerPots() + " stopping...");
                                        super.shutdown();
                                    }
                                }
                            }
                        }

                        howtoBank = Rs2Random.between(0, 100);
                        if (howtoBank <= 40) {
                            if (config.minForgottenBrew() > 0) {
                                if (Rs2Inventory.count("Forgotten brew(4)") + Rs2Inventory.count("Forgotten brew(3)") < Rs2Random.between(config.minForgottenBrew(), config.targetForgottenBrew())) {
                                    if (Rs2Bank.getBankItem("Forgotten brew(4)") != null) {
                                        if (Rs2Bank.getBankItem("Forgotten brew(4)").getQuantity() >= config.targetForgottenBrew()) {
                                            int amt = Rs2Random.between(config.minForgottenBrew(), config.targetForgottenBrew()) - (Rs2Inventory.count("Forgotten brew(4)") + Rs2Inventory.count("Forgotten brew(3)"));
                                            if (amt <= 0) amt = 1;
                                            Microbot.log("Withdrawing " + amt);
                                            if (Rs2Bank.withdrawX("Forgotten brew(4)", amt)) {
                                                sleepUntil(() -> Rs2Inventory.count("Forgotten brew(4)") + Rs2Inventory.count("Forgotten brew(3)") > Rs2Random.between(1, 3), Rs2Random.between(2000, 4000));
                                            }
                                        } else {
                                            Microbot.log("We're out of Forgotten brew need at least " + config.targetForgottenBrew() + " stopping...");
                                            super.shutdown();
                                        }
                                    }
                                }
                            }
                        }

                        howtoBank = Rs2Random.between(0, 100);
                        if (howtoBank <= 40) {
                            if (Rs2Inventory.get(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()) == null ||
                                    Rs2Inventory.get(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()).getQuantity() < Rs2Random.between(config.minBarrowsTeleports(), config.targetBarrowsTeleports())) {
                                if (Rs2Bank.getBankItem(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()) != null) {
                                    if (Rs2Bank.getBankItem(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()).getQuantity() >= config.targetBarrowsTeleports()) {
                                        if (Rs2Bank.withdrawX(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID(), Rs2Random.between(config.minBarrowsTeleports(), config.targetBarrowsTeleports()))) {
                                            sleep(Rs2Random.between(300, 750));
                                        }
                                    } else {
                                        Microbot.log("We're out of " + config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() + " need at least " + config.targetBarrowsTeleports() + " stopping...");
                                        super.shutdown();
                                    }
                                } else {
                                    Microbot.log("We're out of " + config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() + " need at least " + config.targetBarrowsTeleports() + " stopping...");
                                    super.shutdown();
                                }
                            }
                        }

                        howtoBank = Rs2Random.between(0, 100);
                        if (howtoBank <= 40) {
                            if (Rs2Inventory.count(ourFoodsID) < config.targetFoodAmount()) {
                                if (Rs2Bank.getBankItem(ourFoodsID) != null) {
                                    if (Rs2Bank.getBankItem(ourFoodsID).getQuantity() >= config.targetFoodAmount()) {
                                        int amt = Rs2Random.between(config.minFood(), config.targetFoodAmount()) - Rs2Inventory.count(ourFoodsID);
                                        if (amt <= 0) amt = 1;
                                        Microbot.log("Withdrawing " + amt);
                                        if (Rs2Bank.withdrawX(ourFoodsID, amt)) {
                                            sleepUntil(() -> Rs2Inventory.count(ourFoodsID) >= 10, Rs2Random.between(2000, 4000));
                                        }
                                    } else {
                                        Microbot.log("We're out of " + ourfoodsname + " need at least " + config.targetFoodAmount() + " stopping...");
                                        super.shutdown();
                                    }
                                }
                            }
                        }

                        howtoBank = Rs2Random.between(0, 100);
                        if (howtoBank <= 40) {
                            if (!Rs2Inventory.contains("Spade")) {
                                if (Rs2Bank.getBankItem("Spade") != null) {
                                    if (Rs2Bank.getBankItem("Spade").getQuantity() >= 1) {
                                        Rs2Bank.withdrawOne("Spade");
                                        sleepUntil(() -> Rs2Inventory.contains("Spade"), Rs2Random.between(2000, 4000));
                                    } else {
                                        Microbot.log("We're out of Spades. stopping...");
                                        super.shutdown();
                                    }
                                }
                            }
                        }

                        howtoBank = Rs2Random.between(0, 100);
                        if (howtoBank <= 40) {
                            if (Rs2Equipment.get(EquipmentInventorySlot.RING) != null) {
                                // ring equipped — fine
                            } else {
                                Microbot.log("Getting the ring of dueling");
                                if (Rs2Bank.count(ItemID.RING_OF_DUELING8) > 0) {
                                    if (!Rs2Inventory.contains(ItemID.RING_OF_DUELING8)) {
                                        if (Rs2Bank.withdrawX(ItemID.RING_OF_DUELING8, 1)) {
                                            sleepUntil(() -> Rs2Inventory.contains(ItemID.RING_OF_DUELING8), Rs2Random.between(5000, 15000));
                                        }
                                    }
                                } else {
                                    Microbot.log("Out of rings of dueling");
                                    super.shutdown();
                                }
                                if (Rs2Inventory.contains(ItemID.RING_OF_DUELING8)) {
                                    if (Rs2Inventory.interact(ItemID.RING_OF_DUELING8, "Wear")) {
                                        sleepUntil(() -> Rs2Equipment.get(EquipmentInventorySlot.RING).getName().contains("dueling"), Rs2Random.between(5000, 15000));
                                    }
                                }
                            }
                        }

                        suppliesCheck(config);

                        if (!shouldBank) {
                            closeBank();
                            if (!Rs2Bank.isOpen()) {
                                reJfount();
                                handlePOH(config);
                            }
                        } else {
                            if (Rs2Player.getRunEnergy() <= 5) {
                                closeBank();
                                if (!Rs2Bank.isOpen()) reJfount();
                            }
                        }
                    }
                }

                scriptDelay = Rs2Random.between(200, 750);
                long endTime = System.currentTimeMillis();
                System.out.println("Total time for loop " + (endTime - startTime));

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, scriptDelay, TimeUnit.MILLISECONDS);
        return true;
    }

    // -------------------------------------------------------------------------
    // Dialogue — fully instrumented
    // -------------------------------------------------------------------------

    public void dialogueEnterTunnels() {
        Microbot.log("[DEBUG] dialogueEnterTunnels() called.");
        debugLogDialogueState("dialogueEnterTunnels-entry");

        if (Rs2Dialogue.isInDialogue()) {
            int safetyCounter = 0;
            while (Rs2Dialogue.isInDialogue()) {
                if (!super.isRunning()) break;
                safetyCounter++;
                if (safetyCounter > 20) {
                    Microbot.log("[DEBUG] dialogueEnterTunnels safety counter hit — breaking to avoid infinite loop.");
                    break;
                }

                Microbot.log("[DEBUG] dialogueEnterTunnels loop iteration " + safetyCounter
                        + " isInDialogue=" + Rs2Dialogue.isInDialogue()
                        + " hasContinue=" + Rs2Dialogue.hasContinue()
                        + " hasOption(fearless)=" + Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!"));

                if (Rs2Dialogue.hasContinue()) {
                    Microbot.log("[DEBUG] Clicking continue.");
                    Rs2Dialogue.clickContinue();
                    sleepUntil(() -> Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!"), Rs2Random.between(2000, 5000));
                    sleep(300, 600);
                    // Re-log state after clicking continue
                    debugLogDialogueState("post-clickContinue");
                }

                if (Rs2Dialogue.hasDialogueOption("Yeah I'm fearless!")) {
                    Microbot.log("[DEBUG] Option 'Yeah I'm fearless!' found — clicking.");
                    if (Rs2Dialogue.clickOption("Yeah I'm fearless!")) {
                        Microbot.log("[DEBUG] Option clicked — waiting for tunnel load.");
                        sleepUntil(() -> Rs2Player.getWorldLocation().getY() > 9600 && Rs2Player.getWorldLocation().getY() < 9730, Rs2Random.between(2500, 6000));
                        sleep(1000, 2000);
                        inTunnels = true;
                        Microbot.log("[DEBUG] inTunnels set to true. PlayerY=" + Rs2Player.getWorldLocation().getY());
                    } else {
                        Microbot.log("[DEBUG] clickOption returned false for 'Yeah I'm fearless!' — retrying next iteration.");
                    }
                } else {
                    Microbot.log("[DEBUG] Option 'Yeah I'm fearless!' not yet visible. hasContinue=" + Rs2Dialogue.hasContinue());
                }

                if (!Rs2Dialogue.isInDialogue()) {
                    Microbot.log("[DEBUG] Dialogue ended naturally.");
                    break;
                }
                if (inTunnels) {
                    Microbot.log("[DEBUG] inTunnels=true — breaking dialogue loop.");
                    break;
                }
                if (Rs2Player.getWorldLocation().getPlane() != 3) {
                    Microbot.log("[DEBUG] Plane changed to " + Rs2Player.getWorldLocation().getPlane() + " — breaking dialogue loop.");
                    break;
                }
            }
        } else {
            Microbot.log("[DEBUG] dialogueEnterTunnels() called but Rs2Dialogue.isInDialogue() is FALSE — nothing to handle.");
            debugLogDialogueState("dialogueEnterTunnels-no-dialogue");
        }
        Microbot.log("[DEBUG] dialogueEnterTunnels() finished. inTunnels=" + inTunnels);
    }

    // -------------------------------------------------------------------------
    // All other methods — unchanged from 2.0.11-debug
    // -------------------------------------------------------------------------

    public void checkForWorldMap() {
        if (Rs2Widget.getWidget(38993938) != null) {
            if (Rs2Widget.getWidget(38993938).getText().contains("Key")) {
                Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            }
        }
    }

    public void closeBank() {
        if (Rs2Bank.isOpen()) {
            while (Rs2Bank.isOpen()) {
                if (!super.isRunning()) break;
                if (Rs2Bank.closeBank()) sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(2000, 4000));
            }
        }
    }

    public void handlePOH(BarrowsConfig config) {
        if (config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() != ItemID.TELEPORT_TO_HOUSE) return;
        Client client = Microbot.getClient();
        if (client == null) return;
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) return;
        if (!worldView.isInstance()) return;
        Rs2TileObjectModel pohThing = rs2TileObjectCache.query().withId(4525).nearestOnClientThread();
        if (pohThing == null) return;
        Microbot.log("We're in our POH");
        Rs2TileObjectModel rejPool = rs2TileObjectCache.query().withIds(29238, 29239, 29241, 29240).nearestOnClientThread();
        if (rejPool != null) {
            if (rejPool.click("Drink")) {
                sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(2000, 4000));
                sleepUntil(() -> !Rs2Player.isMoving(), Rs2Random.between(10000, 15000));
            }
        }
        Rs2TileObjectModel regularPortal = rs2TileObjectCache.query().withIds(37603, 37615, 37591).nearestOnClientThread();
        if (regularPortal != null) {
            for (int pohPortalAttempts = 0; pohPortalAttempts < 40; pohPortalAttempts++) {
                if (!super.isRunning()) break;
                pohThing = rs2TileObjectCache.query().withId(4525).nearestOnClientThread();
                if (pohThing == null) break;
                regularPortal = rs2TileObjectCache.query().withIds(37603, 37615, 37591).nearestOnClientThread();
                if (regularPortal == null) break;
                if (Rs2Player.isMoving()) { sleep(Rs2Random.between(200, 600)); continue; }
                if (regularPortal.click("Enter")) {
                    sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(2000, 4000));
                    sleepUntil(() -> !Rs2Player.isMoving(), Rs2Random.between(10000, 15000));
                    sleepUntil(() -> rs2TileObjectCache.query().withIds(37603, 37615, 37591).nearestOnClientThread() == null, Rs2Random.between(10000, 15000));
                } else {
                    break;
                }
            }
        } else {
            Microbot.log("No nexus support yet, shutting down");
            super.shutdown();
        }
    }

    public boolean everyBrotherWasKilled() {
        return Microbot.getVarbitValue(Varbits.BARROWS_KILLED_DHAROK) == 1
                && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_GUTHAN) == 1
                && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_KARIL) == 1
                && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_TORAG) == 1
                && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_VERAC) == 1
                && Microbot.getVarbitValue(Varbits.BARROWS_KILLED_AHRIM) == 1;
    }

    public void digIntoTheMound(Rs2WorldArea moundArea) {
        while (moundArea.contains(Rs2Player.getWorldLocation()) && Rs2Player.getWorldLocation().getPlane() != 3) {
            checkForWorldMap();
            if (!super.isRunning()) break;
            antiPatternEnableWrongPrayer();
            antiPatternActivatePrayer();
            if (Rs2Inventory.contains("Spade")) {
                if (Rs2Inventory.interact("Spade", "Dig")) {
                    sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() == 3, Rs2Random.between(3000, 5000));
                }
            }
            if (Rs2Player.getWorldLocation().getPlane() == 3) break;
        }
    }

    public void goToTheMound(Rs2WorldArea moundArea) {
        while (!moundArea.contains(Rs2Player.getWorldLocation())) {
            checkForWorldMap();
            int totalTiles = moundArea.toWorldPointList().size();
            WorldPoint randomMoundTile;
            if (!super.isRunning()) break;
            antiPatternEnableWrongPrayer();
            antiPatternActivatePrayer();
            antiPatternDropVials();
            randomMoundTile = moundArea.toWorldPointList().get(Rs2Random.between(0, totalTiles - 1));
            if (Rs2Walker.walkTo(randomMoundTile)) sleepUntil(() -> !Rs2Player.isMoving(), Rs2Random.between(2000, 4000));
            if (moundArea.contains(Rs2Player.getWorldLocation())) {
                if (!Rs2Player.isMoving()) break;
            } else {
                Microbot.log("At the mound, but we can't dig yet.");
                randomMoundTile = moundArea.toWorldPointList().get(Rs2Random.between(0, totalTiles - 1));
                Rs2NpcModel strangeOldMan = rs2NpcCache.query().withName("Strange Old Man").nearestOnClientThread();
                if (strangeOldMan != null && strangeOldMan.getWorldLocation() != null) {
                    while (strangeOldMan.getWorldLocation() == randomMoundTile) {
                        if (!super.isRunning()) break;
                        randomMoundTile = moundArea.toWorldPointList().get(Rs2Random.between(0, totalTiles - 1));
                        sleep(250, 500);
                    }
                }
                Rs2Walker.walkCanvas(randomMoundTile);
                sleepUntil(() -> !Rs2Player.isMoving(), Rs2Random.between(2000, 4000));
            }
        }
    }

    public void leaveTheMound() {
        Rs2TileObjectModel stairs = rs2TileObjectCache.query().withIds(20668, 20669, 20670, 20671, 20672, 20667).nearest();
        if (stairs != null) {
            if (Rs2Walker.canReach(stairs.getWorldLocation())) {
                if (Rs2Player.getWorldLocation().getPlane() == 3) {
                    while (Rs2Player.getWorldLocation().getPlane() == 3) {
                        Microbot.log("Leaving the mound");
                        if (!super.isRunning()) break;
                        if (stairs.click("Climb-up")) {
                            sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() != 3, Rs2Random.between(3000, 6000));
                        }
                        if (Rs2Player.getWorldLocation().getPlane() != 3) {
                            disablePrayer();
                            break;
                        }
                    }
                }
                if (inTunnels) inTunnels = false;
            }
        }
    }

    public void lootChampionScroll() {
        Rs2TileItemModel championScroll = rs2TileItemCache.query().withId(ItemID.SKELETON_CHAMPION_SCROLL).nearest();
        if (championScroll != null && championScroll.isReachable() && championScroll.isLootAble()) {
            while (rs2TileItemCache.query().withId(ItemID.SKELETON_CHAMPION_SCROLL).nearest() != null && !Rs2Inventory.contains(championScroll.getId())) {
                if (!super.isRunning()) break;
                championScroll.click("Take");
                sleepUntil(() -> !Rs2Player.isMoving() && Rs2Inventory.contains(championScroll.getId()), Rs2Random.between(4000, 12000));
            }
        }
    }

    public void gainRP(BarrowsConfig config) {
        if (shouldAttackSkeleton) {
            int RP = Microbot.getVarbitValue(Varbits.BARROWS_REWARD_POTENTIAL);
            if (RP > 870) return;
            Rs2NpcModel skele = rs2NpcCache.query().withName("Skeleton").nearestOnClientThread();
            if (skele == null || skele.isDead()) return;
            if (skele.hasLineOfSight()) {
                stopFutureWalker();
                if (!Rs2Player.isInCombat()) {
                    if (skele.click("Attack")) sleepUntil(() -> Rs2Player.isInCombat() && !Rs2Player.isMoving(), Rs2Random.between(4000, 8000));
                }
                if (Rs2Player.isInCombat()) {
                    while (Rs2Player.isInCombat()) {
                        Microbot.log("Fighting the Skeleton.");
                        if (!super.isRunning()) break;
                        stopFutureWalker();
                        sleep(750, 1500);
                        eatFood();
                        outOfSupplies(config);
                        antiPatternDropVials();
                        if (shouldBank) { Microbot.log("Breaking out we're out of supplies."); break; }
                        if (!Rs2Player.isInCombat()) { Microbot.log("Breaking out we're no longer in combat."); break; }
                        if (skele.isDead()) { Microbot.log("Breaking out the skeleton is dead."); break; }
                        if (Microbot.getVarbitValue(Varbits.BARROWS_REWARD_POTENTIAL) > 870) { Microbot.log("Breaking out we have enough RP."); break; }
                        if (hintNpcModel() != null) {
                            Rs2NpcModel brother = rs2NpcCache.query().withName(hintNpcModel().getName()).nearest();
                            if (brother != null && brother.hasLineOfSight()) { Microbot.log("The brother is here."); break; }
                        }
                    }
                }
            }
        }
    }

    public void suppliesCheck(BarrowsConfig config) {
        if (!usingPoweredStaffs) {
            if (Rs2Inventory.get(neededRune) == null || Rs2Inventory.get(neededRune).getQuantity() <= minRuneAmt) {
                Microbot.log("We have less than 180 " + neededRune);
                shouldBank = true;
                return;
            }
        }
        if (usingPoweredStaffs && outOfPoweredStaffCharges) { Microbot.log("We're out of staff charges."); shouldBank = true; return; }
        if (Rs2Equipment.get(EquipmentInventorySlot.RING) == null) { Microbot.log("We don't have a ring of dueling equipped."); shouldBank = true; return; }
        if (!Rs2Inventory.contains("Spade")) { Microbot.log("We don't have a spade."); shouldBank = true; return; }
        if (Rs2Inventory.count(config.food().getName()) < 1) { Microbot.log("We have less than 1 food."); shouldBank = true; return; }
        if (Rs2Inventory.get(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()) == null) {
            Microbot.log("We don't have a " + config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemName());
            shouldBank = true;
            return;
        }
        if (Rs2Inventory.count(it -> it != null && it.getName().contains("Forgotten brew(")) < minForgottenBrews) {
            Microbot.log("We forgot our Forgotten brew.");
            shouldBank = true;
            return;
        }
        String name = config.prayerRestoreType().getPrayerRestoreTypeName();
        if (name.contains("(")) name = config.prayerRestoreType().getPrayerRestoreTypeName().split("\\(")[0];
        String splitName = name;
        if (Rs2Inventory.count(it -> it != null && it.getName().toLowerCase().contains(splitName.toLowerCase())) < 1) {
            Microbot.log("We don't have enough " + splitName);
            shouldBank = true;
            return;
        }
        if (Rs2Player.getRunEnergy() <= 5) { Microbot.log("We need more run energy "); shouldBank = true; return; }
        shouldBank = false;
    }

    public void stuckInTunsCheck() {
        if (tunnelLoopCount < 1) FirstLoopTile = Rs2Player.getWorldLocation();
        if (tunnelLoopCount >= 15) {
            WorldPoint currentTile = Rs2Player.getWorldLocation();
            if (currentTile != null && FirstLoopTile != null && currentTile.equals(FirstLoopTile)) {
                Microbot.log("We seem to be stuck. Resetting the walker");
                stopFutureWalker();
                tunnelLoopCount = 0;
            }
        }
        if (tunnelLoopCount >= 30) tunnelLoopCount = 0;
    }

    public void swapTheSpellbook() {
        if (!Rs2Magic.getSpellbook().equals(Rs2Spellbook.MODERN)) {
            WorldPoint swapLocation = Rs2Magic.getSpellbook().getSwitchLocation();
            if (Rs2Player.getWorldLocation().distanceTo(swapLocation) > 5) Rs2Walker.walkTo(swapLocation);
            Rs2Spellbook.MODERN.switchTo();
        }
    }

    public void gettheRune() {
        if (!neededRune.equals("unknown")) return;
        neededRune = "unknown";
        int magicLvl = Rs2Player.getRealSkillLevel(Skill.MAGIC);
        if (magicLvl >= 41 && magicLvl < 62) neededRune = "Death rune";
        if (magicLvl >= 62 && magicLvl < 81) neededRune = "Blood rune";
        if (magicLvl >= 81) neededRune = "Wrath rune";
    }

    public void setAutoCast() {
        if (neededRune == "Wrath rune") {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_SURGE) Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_SURGE, false);
        }
        if (neededRune == "Blood rune") {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_WAVE) Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_WAVE, false);
        }
        if (neededRune == "Death rune") {
            if (Rs2Magic.getCurrentAutoCastSpell() != Rs2CombatSpells.WIND_BLAST) Rs2Combat.setAutoCastSpell(Rs2CombatSpells.WIND_BLAST, false);
        }
    }

    public void activatePrayer(Rs2PrayerEnum prayer) {
        if (!Rs2Prayer.isPrayerActive(prayer)) {
            Microbot.log("Turning on Prayer.");
            drinkPrayerPot();
            Rs2Prayer.toggle(prayer);
        }
    }

    public void antiPatternEnableWrongPrayer() {
        if (!Rs2Prayer.isPrayerActive(NeededPrayer)) {
            if (Rs2Random.between(0, 100) <= Rs2Random.between(1, 4)) {
                Rs2PrayerEnum wrongPrayer;
                int random = Rs2Random.between(0, 100);
                if (random <= 50) wrongPrayer = Rs2PrayerEnum.PROTECT_MELEE;
                else if (random < 75) wrongPrayer = Rs2PrayerEnum.PROTECT_RANGE;
                else wrongPrayer = Rs2PrayerEnum.PROTECT_MAGIC;
                drinkPrayerPot();
                Rs2Prayer.toggle(wrongPrayer);
                sleep(0, 750);
            }
        }
    }

    public void antiPatternActivatePrayer() {
        if (!Rs2Prayer.isPrayerActive(NeededPrayer)) {
            if (Rs2Random.between(0, 100) <= Rs2Random.between(1, 8)) {
                drinkPrayerPot();
                Rs2Prayer.toggle(NeededPrayer);
                sleep(0, 750);
            }
        }
    }

    public void antiPatternDropVials() {
        if (Rs2Random.between(0, 100) <= Rs2Random.between(1, 25)) {
            Rs2ItemModel whatToDrop = Rs2Inventory.get(it -> it != null && it.getName().contains("Vial") || it.getName().contains("Butterfly jar"));
            if (whatToDrop != null && Rs2Inventory.contains(whatToDrop.getName())) {
                if (Rs2Inventory.drop(whatToDrop.getName())) sleep(0, 750);
            }
        }
    }

    public void outOfSupplies(BarrowsConfig config) {
        suppliesCheck(config);
        if (!shouldBank) return;
        boolean needFeroxRingTeleport = inTunnels || Rs2Player.getWorldLocation().getPlane() == 3 || isInPlayerOwnedHouse();
        if (!needFeroxRingTeleport) return;
        if (tryFeroxTeleportViaRingOfDueling()) {
            Microbot.log("We're out of supplies. Teleporting to Ferox Enclave.");
            if (inTunnels) inTunnels = false;
            sleepUntil(() -> Rs2Player.isAnimating(), Rs2Random.between(2000, 4000));
            sleepUntil(() -> !Rs2Player.isAnimating(), Rs2Random.between(6000, 10000));
        }
    }

    private boolean isInPlayerOwnedHouse() {
        Client c = Microbot.getClient();
        if (c == null) return false;
        WorldView wv = c.getTopLevelWorldView();
        if (wv == null || !wv.isInstance() || inTunnels) return false;
        return rs2TileObjectCache.query().withId(4525).nearestOnClientThread() != null;
    }

    private boolean tryFeroxTeleportViaRingOfDueling() {
        Rs2ItemModel equippedRing = Rs2Equipment.get(EquipmentInventorySlot.RING);
        if (equippedRing != null && equippedRing.getName() != null && equippedRing.getName().contains("Ring of dueling")) {
            if (Rs2Equipment.interact(EquipmentInventorySlot.RING, "Ferox Enclave")) return true;
        }
        int[] duelingRingIds = {ItemID.RING_OF_DUELING1, ItemID.RING_OF_DUELING2, ItemID.RING_OF_DUELING3,
                ItemID.RING_OF_DUELING4, ItemID.RING_OF_DUELING5, ItemID.RING_OF_DUELING6,
                ItemID.RING_OF_DUELING7, ItemID.RING_OF_DUELING8};
        for (int idx = duelingRingIds.length - 1; idx >= 0; idx--) {
            if (!Rs2Inventory.hasItem(duelingRingIds[idx])) continue;
            if (tryRubInventoryRingToFerox(duelingRingIds[idx])) return true;
        }
        return false;
    }

    private boolean tryRubInventoryRingToFerox(int ringId) {
        String feroxLabel = JewelleryLocationEnum.FEROX_ENCLAVE.getDestination();
        if (Rs2Inventory.interact(ringId, feroxLabel)) return true;
        if (Rs2Inventory.interact(ringId, "Rub")) {
            sleepUntil(() -> Rs2Dialogue.hasDialogueOption(feroxLabel), Rs2Random.between(1500, 3500));
            if (Rs2Dialogue.clickOption(feroxLabel)) return true;
            return Rs2Dialogue.clickOption(feroxLabel, false);
        }
        return false;
    }

    public void disablePrayer() {
        if (Rs2Random.between(0, 100) >= Rs2Random.between(0, 5)) {
            Rs2Prayer.disableAllPrayers();
            sleep(0, 750);
        }
    }

    public void reJfount() {
        int rejat = Rs2Random.between(10, 30);
        int runener = Rs2Random.between(50, 65);
        while (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) < rejat || Rs2Player.getRunEnergy() <= runener) {
            if (!super.isRunning()) break;
            if (Rs2Bank.isOpen()) {
                if (Rs2Bank.closeBank()) sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(2000, 4000));
            } else {
                Rs2TileObjectModel rej = rs2TileObjectCache.query().withId(39651).nearest();
                if (rej == null) break;
                Microbot.log("Drinking");
                if (rej.click("Drink")) {
                    sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(1000, 3000));
                    sleepUntil(() -> !Rs2Player.isMoving(), Rs2Random.between(5000, 10000));
                    sleepUntil(() -> Rs2Player.isAnimating(), Rs2Random.between(1000, 4000));
                    sleepUntil(() -> !Rs2Player.isAnimating(), Rs2Random.between(1000, 4000));
                }
            }
            if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) >= rejat && Rs2Player.getRunEnergy() >= runener) break;
        }
    }

    public void drinkPrayerPot() {
        boolean skipThePot = hintNpcModel() != null && !hintNpcModel().getName().contains("Dharok") && hintNpcModel().getHealthPercentage() < Rs2Random.between(40, 50);
        if (!skipThePot) {
            if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) <= Rs2Random.between(8, 15)) {
                if (Rs2Inventory.contains(it -> it != null && it.getName().contains("Prayer potion") || it.getName().contains("Moonlight moth"))) {
                    Rs2ItemModel prayerpotion = Rs2Inventory.get(it -> it != null && it.getName().contains("Prayer potion") || it.getName().contains("Moonlight moth"));
                    String action = prayerpotion.getName().equals("Moonlight moth") ? "Release" : "Drink";
                    if (Rs2Inventory.interact(prayerpotion, action)) sleep(0, 750);
                }
            }
        }
    }

    public Rs2NpcModel hintNpcModel() {
        Optional<NPC> hintNpc = Microbot.getClientThread().runOnClientThreadOptional(
                () -> Microbot.getClient().getHintArrowNpc()
        );
        return hintNpc.map(Rs2NpcModel::new).orElse(null);
    }

    public void checkForAndFightBrother(BarrowsConfig config) {
        if (hintNpcModel() != null) {
            Rs2NpcModel currentBrother = hintNpcModel();
            Rs2PrayerEnum neededprayer = Rs2PrayerEnum.PROTECT_MELEE;
            if (currentBrother != null) {
                stopFutureWalker();
                if (hintNpcModel().getName().contains("Ahrim")) neededprayer = Rs2PrayerEnum.PROTECT_MAGIC;
                if (hintNpcModel().getName().contains("Karil")) neededprayer = Rs2PrayerEnum.PROTECT_RANGE;
                while (hintNpcModel() != null) {
                    Microbot.log("Fighting the brother.");
                    if (!super.isRunning()) { Microbot.log("Super isn't running!"); break; }
                    if (inTunnels && !currentBrother.hasLineOfSight()) { Microbot.log("No LOS!"); break; }
                    if (config.shouldPrayAgainstWeakerBrothers()) {
                        activatePrayer(neededprayer);
                    } else {
                        if (!hintNpcModel().getName().contains("Torag") && !hintNpcModel().getName().contains("Guthan") && !hintNpcModel().getName().contains("Verac")) {
                            activatePrayer(neededprayer);
                        }
                    }
                    if (hintNpcModel() != null && Rs2Player.getInteracting() != null && !Rs2Player.getInteracting().getName().equals(hintNpcModel().getName())) {
                        if (currentBrother.click("Attack")) sleepUntil(() -> Rs2Player.isInCombat(), Rs2Random.between(3000, 6000));
                    } else {
                        if (!Rs2Player.isInCombat()) {
                            if (currentBrother.click("Attack")) sleepUntil(() -> Rs2Player.isInCombat(), Rs2Random.between(3000, 6000));
                        }
                    }
                    sleep(750, 1500);
                    drinkPrayerPot();
                    eatFood();
                    outOfSupplies(config);
                    antiPatternDropVials();
                    drinkforgottonbrew();
                    if (hintNpcModel() == null) { Microbot.log("Breaking out the brother is null."); disablePrayer(); break; }
                    if (hintNpcModel().isDead()) { Microbot.log("Breaking out the brother is dead."); disablePrayer(); sleepUntil(() -> hintNpcModel() == null, Rs2Random.between(3000, 6000)); break; }
                }
            }
        }
    }

    public void stopFutureWalker() {
        if (WalkToTheChestFuture != null) {
            Rs2Walker.setTarget(null);
            WalkToTheChestFuture.cancel(true);
        }
    }

    private void walkToChest() {
        try {
            if (!inTunnels) { WalkToTheChestFuture.cancel(true); return; }
            Rs2Walker.walkTo(Chest, 2);
        } catch (Exception e) {
            Microbot.log("walkToChest failed: " + e.getMessage());
        }
    }

    private void startWalkingToTheChest() {
        if (WalkToTheChestFuture != null && !WalkToTheChestFuture.isCancelled() && !WalkToTheChestFuture.isDone()) return;
        if (inTunnels) {
            WalkToTheChestFuture = scheduledExecutorService.scheduleWithFixedDelay(this::walkToChest, 0, walkerDelay, TimeUnit.MILLISECONDS);
        }
    }

    public void drinkforgottonbrew() {
        if (Rs2Inventory.contains(it -> it != null && it.getName().contains("Forgotten brew"))) {
            if (Rs2Player.getBoostedSkillLevel(Skill.MAGIC) <= Rs2Player.getRealSkillLevel(Skill.MAGIC) + Rs2Random.between(1, 4)) {
                Microbot.log("Drinking a Forgotten brew.");
                String[] priorityOfBrews = {"Forgotten brew(1)", "Forgotten brew(2)", "Forgotten brew(3)", "Forgotten brew(4)"};
                for (String brew : priorityOfBrews) {
                    if (Rs2Inventory.contains(brew)) {
                        if (Rs2Inventory.interact(brew, "Drink")) { sleep(300, 1000); break; }
                    }
                }
            }
        }
    }

    public void eatFood() {
        if (Rs2Player.getHealthPercentage() <= 60) {
            if (Rs2Inventory.contains(it -> it != null && it.isFood())) {
                Rs2ItemModel food = Rs2Inventory.get(it -> it != null && it.isFood());
                if (Rs2Inventory.interact(food, "Eat")) sleep(0, 750);
            }
        }
    }

    public void solvePuzzle() {
        boolean stoppedTheWalker = false;
        int[] widgets = {1638413, 1638415, 1638417};
        int[] modelIDs = {6725, 6731, 6713, 6719};
        int random = Rs2Random.between(0, 1000);
        int secondRandom = Rs2Random.between(1, 10);
        sleepUntil(() -> Rs2Widget.getWidget(widgets[0]) != null || Rs2Widget.getWidget(widgets[1]) != null || Rs2Widget.getWidget(widgets[2]) != null, Rs2Random.between(300, 800));
        for (int widget : widgets) {
            if (!super.isRunning()) break;
            if (Rs2Widget.getWidget(widget) != null) {
                if (!stoppedTheWalker) { stopFutureWalker(); stoppedTheWalker = true; }
                for (int modelID : modelIDs) {
                    if (!super.isRunning()) break;
                    if (Rs2Widget.getWidget(widget).getModelId() == modelID || random <= secondRandom) {
                        Microbot.log("Solution found");
                        Rs2Widget.clickWidget(widget);
                        break;
                    }
                }
            } else {
                break;
            }
        }
    }

    public enum BarrowsBrothers {
        DHAROK("Dharok the Wretched", new Rs2WorldArea(3573, 3296, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE),
        GUTHAN("Guthan the Infested", new Rs2WorldArea(3575, 3280, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE),
        KARIL("Karil the Tainted", new Rs2WorldArea(3564, 3274, 3, 3, 0), Rs2PrayerEnum.PROTECT_RANGE),
        TORAG("Torag the Corrupted", new Rs2WorldArea(3552, 3282, 2, 2, 0), Rs2PrayerEnum.PROTECT_MELEE),
        VERAC("Verac the Defiled", new Rs2WorldArea(3556, 3297, 3, 3, 0), Rs2PrayerEnum.PROTECT_MELEE),
        AHRIM("Ahrim the Blighted", new Rs2WorldArea(3563, 3288, 3, 3, 0), Rs2PrayerEnum.PROTECT_MAGIC);

        private String name;
        private Rs2WorldArea humpWP;
        private Rs2PrayerEnum whatToPray;

        BarrowsBrothers(String name, Rs2WorldArea humpWP, Rs2PrayerEnum whatToPray) {
            this.name = name;
            this.humpWP = humpWP;
            this.whatToPray = whatToPray;
        }

        public String getName() { return name; }
        public Rs2WorldArea getHumpWP() { return humpWP; }
        public Rs2PrayerEnum getWhatToPray() { return whatToPray; }
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
