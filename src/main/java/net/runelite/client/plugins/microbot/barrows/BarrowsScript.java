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


public class BarrowsScript extends Script {

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
    int scriptDelay = Rs2Random.between(300,600);
    public static int ChestsOpened = 0;
    private int minRuneAmt;
    private int minForgottenBrews = 0;

    long walkerDelay = Rs2Random.between(1000,2000);

    private WorldPoint FirstLoopTile;
    private WorldPoint Chest = new WorldPoint(3552,9694,0);

    private Rs2PrayerEnum NeededPrayer;
    public static List<String> barrowsPieces = new ArrayList<>();
    private ScheduledFuture<?> WalkToTheChestFuture;

    @Inject
    Rs2NpcCache rs2NpcCache;
    @Inject Rs2TileItemCache rs2TileItemCache;
    @Inject Rs2PlayerCache rs2PlayerCache;
    @Inject Rs2TileObjectCache rs2TileObjectCache;



    public boolean run(BarrowsConfig config, BarrowsPlugin plugin) {
        Microbot.enableAutoRunOn = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                long startTime = System.currentTimeMillis();

                var inventorySetup = new Rs2InventorySetup(config.inventorySetup().getName(), mainScheduledFuture);

                if(firstRun) {
                    if (!inventorySetup.doesEquipmentMatch()) {
                        while(!inventorySetup.doesEquipmentMatch()) {
                            if(!super.isRunning()){ break; }
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

                if(barrowsPieces.isEmpty()) barrowsPieces.add("Nothing yet.");

                if(Rs2Player.getWorldLocation().getY() > 9600 && Rs2Player.getWorldLocation().getY() < 9730) {
                    inTunnels = true;
                } else {
                    if(tunnelLoopCount != 0){
                        tunnelLoopCount = 0;
                    }
                    inTunnels = false;
                }

                //powered staffs
                if(Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Trident of the") ||
                        Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Tumeken's") ||
                            Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("sceptre") ||
                                Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Sanguinesti") ||
                                    Rs2Equipment.get(EquipmentInventorySlot.WEAPON).getName().contains("Crystal staff")) {
                    usingPoweredStaffs = true;
                } else {
                    usingPoweredStaffs = false;
                    gettheRune();
                    minRuneAmt = config.minRuneAmount();
                    if(!Rs2Magic.getSpellbook().equals(Rs2Spellbook.MODERN)){
                        swapTheSpellbook();
                        return;
                    }
                }

                minForgottenBrews = config.minForgottenBrew();
                shouldAttackSkeleton = config.shouldGainRP();

                if(usingPoweredStaffs) {
                    if (outOfPoweredStaffCharges) {
                        Microbot.log("No charges left on our staff. Stopping...");
                        super.shutdown();
                    }
                }

                // Only check supplies when NOT in tunnels — avoids banking immediately after tunnel entry
                if (!inTunnels) {
                    outOfSupplies(config);
                }

                if(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() == ItemID.TELEPORT_TO_HOUSE) {
                    if (!inTunnels && !shouldBank && Rs2Player.getWorldLocation().distanceTo(new WorldPoint(3573, 3296, 0)) > 60) {
                        if(Rs2Bank.isOpen()){
                            closeBank();
                            return;
                        }
                        if(rs2TileObjectCache.query().withId(4525).nearest() == null){
                            Rs2Inventory.interact("Teleport to house", "Inside");
                            sleepUntil(() -> Rs2Player.getAnimation() == 4069, Rs2Random.between(2000, 4000));
                            sleepUntil(() -> !Rs2Player.isAnimating(), Rs2Random.between(6000, 10000));
                            sleepUntil(() -> rs2TileObjectCache.query().withId(4525).nearest() != null, Rs2Random.between(6000, 10000));
                        }
                        handlePOH(config);
                        return;
                    }
                }

                if(!inTunnels && !shouldBank) {

                    if(!BreakHandlerScript.lockState.get()){
                        if(BreakHandlerScript.breakIn < 60 && BreakHandlerScript.breakIn != -1){
                            Microbot.log("Going on break soon, doing nothing.");
                            return;
                        }
                    }

                    BreakHandlerScript.lockState.set(true);

                    for (BarrowsBrothers brother : BarrowsBrothers.values()) {
                        Rs2WorldArea mound = brother.getHumpWP();
                        NeededPrayer = brother.whatToPray;
                        outOfSupplies(config);
                        if(shouldBank){
                            return;
                        }

                        stopFutureWalker();
                        closeBank();

                        if(!usingPoweredStaffs) setAutoCast();

                        Microbot.log("Checking mound for: " + brother.getName());

                        if(everyBrotherWasKilled()){
                            if(WhoisTun.equals("Unknown")){
                                Microbot.log("We're not sure who tunnel is, and every brother is dead. Checking all mounds manually");
                                varbitCheckEnabled = false;
                            }
                        } else {
                            if(!varbitCheckEnabled){
                                varbitCheckEnabled = true;
                            }
                        }

                        if(!WhoisTun.equals("Unknown")){
                            if(!varbitCheckEnabled){
                                varbitCheckEnabled = true;
                            }
                        }

                        if(varbitCheckEnabled) {
                            if (brother.name.contains("Dharok")) {
                                if (Microbot.getVarbitValue(Varbits.BARROWS_KILLED_DHAROK) == 1) {
                                    Microbot.log("We all ready killed Dharok.");
                                    continue;
                                }
                            }
                            if (brother.name.contains("Guthan")) {
                                if (Microbot.getVarbitValue(Varbits.BARROWS_KILLED_GUTHAN) == 1) {
                                    Microbot.log("We all ready killed Guthan.");
                                    continue;
                                }
                            }
                            if (brother.name.contains("Karil")) {
                                if (Microbot.getVarbitValue(Varbits.BARROWS_KILLED_KARIL) == 1) {
                                    Microbot.log("We all ready killed Karil.");
                                    continue;
                                }
                            }
                            if (brother.name.contains("Torag")) {
                                if (Microbot.getVarbitValue(Varbits.BARROWS_KILLED_TORAG) == 1) {
                                    Microbot.log("We all ready killed Torag.");
                                    continue;
                                }
                            }
                            if (brother.name.contains("Verac")) {
                                if (Microbot.getVarbitValue(Varbits.BARROWS_KILLED_VERAC) == 1) {
                                    Microbot.log("We all ready killed Verac.");
                                    continue;
                                }
                            }
                            if (brother.name.contains("Ahrim")) {
                                if (Microbot.getVarbitValue(Varbits.BARROWS_KILLED_AHRIM) == 1) {
                                    Microbot.log("We all ready killed Ahrim.");
                                    continue;
                                }
                            }
                        }

                        if (Rs2Player.getWorldLocation().getPlane() != 3) {
                            Microbot.log("Entering the mound");

                            handlePOH(config);

                            goToTheMound(mound);

                            digIntoTheMound(mound);

                        }

                        if (Rs2Player.getWorldLocation().getPlane() == 3) {
                            Microbot.log("We're in the mound");

                            if(config.shouldPrayAgainstWeakerBrothers()){
                                activatePrayer(brother.getWhatToPray());
                            } else {
                                if(!brother.getName().contains("Torag") && !brother.getName().contains("Guthan") && !brother.getName().contains("Verac")){
                                    activatePrayer(brother.getWhatToPray());
                                }
                            }

                            Rs2TileObjectModel sarc = rs2TileObjectCache.query().withIds(20770,20720,20722,20771,20721,20772).nearest();
                            Rs2NpcModel currentBrother = null;
                            Microbot.log("Found the Sarcophagus");
                            while(currentBrother == null) {
                                Microbot.log("Searching the Sarcophagus");
                                if (!super.isRunning()) break;


                                if (sarc.click("Search")) {
                                    sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(1000, 3000));
                                    sleepUntil(() -> !Rs2Player.isMoving() || Rs2Player.isInCombat(), Rs2Random.between(3000, 6000));
                                    sleepUntil(() -> hintNpcModel() != null || Rs2Dialogue.isInDialogue(), Rs2Random.between(750, 1500));
                                }

                                if(Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasDialogueText("You've found a hidden")){
                                    WhoisTun = brother.name;
                                    Microbot.log(brother.name+" is our tunnel");
                                    break;
                                }

                                if(hintNpcModel() != null) {
                                    currentBrother = hintNpcModel();
                                } else {
                                    break;
                                }

                                if (currentBrother != null) break;
                            }

                            checkForAndFightBrother(config);

                            if(brother.name.equals(WhoisTun) && brother.name.contains("Ahrim")) {
                                if (Rs2Dialogue.isInDialogue()) {
                                    dialogueEnterTunnels();
                                    return;
                                }
                            }

                            leaveTheMound();
                        }
                    }
                }

                if(!WhoisTun.equals("Unknown") && !shouldBank && !inTunnels){
                    int howManyBrothersWereKilled = Microbot.getVarbitValue(Varbits.BARROWS_KILLED_DHAROK) + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_GUTHAN) + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_KARIL) + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_TORAG) + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_VERAC) + Microbot.getVarbitValue(Varbits.BARROWS_KILLED_AHRIM);
                    if(howManyBrothersWereKilled <= 4){
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

                            while(!Rs2Dialogue.isInDialogue()) {
                                Rs2TileObjectModel sarc = rs2TileObjectCache.query().withIds(20770,20720,20722,20771,20721,20772).nearest();

                                if (!super.isRunning()) break;

                                if (sarc.click("Search")) {
                                    sleepUntil(() -> Rs2Player.isMoving(), Rs2Random.between(1000, 3000));
                                    sleepUntil(() -> !Rs2Player.isMoving() || Rs2Player.isInCombat(), Rs2Random.between(3000, 6000));
                                    sleepUntil(() -> Rs2Dialogue.isInDialogue(), Rs2Random.between(3000, 6000));
                                }

                                if(Rs2Dialogue.isInDialogue()) break;

                                if (inTunnels) break;

                                if (Rs2Player.getWorldLocation().getPlane() != 3) break;

                                if(!Rs2Dialogue.isInDialogue()){
                                    Microbot.log("We're in the wrong tunnel mound. Leaving...");
                                    this.leaveTheMound();
                                    WhoisTun = "Unknown";
                                    return;
                                }

                            }

                            dialogueEnterTunnels();
                            return;
                        }
                    }
                }


                if(inTunnels && !shouldBank) {
                    Microbot.log("In the tunnels");

                    if (Rs2Player.getQuestState(Quest.HIS_FAITHFUL_SERVANTS) != QuestState.FINISHED) {
                        Microbot.showMessage("Complete the 'His Faithful Servants' quest for the webwalker to function correctly");
                        shutdown();
                        return;
                    }

                    if(!varbitCheckEnabled) varbitCheckEnabled=true;

                    leaveTheMound();
                    stuckInTunsCheck();
                    solvePuzzle();
                    checkForAndFightBrother(config);
                    eatFood();
                    // NOTE: outOfSupplies() is intentionally NOT called here.
                    // Supply checking mid-tunnel caused the script to bank immediately on tunnel entry.
                    // Supplies are checked before every mound visit and after the chest is looted.
                    gainRP(config);
                    lootChampionScroll();

                    if(!Rs2Player.isMoving()) startWalkingToTheChest();

                    solvePuzzle();
                    checkForAndFightBrother(config);

                    Rs2TileObjectModel barrowsChest = rs2TileObjectCache.query().withId(20973).nearest();

                    if(barrowsChest != null &&
                            (barrowsChest.getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) < 5)){
                        stopFutureWalker();

                        if(barrowsChest.click("Open")){
                            sleepUntil(()-> hintNpcModel()!=null && hintNpcModel().getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) <= 5, Rs2Random.between(4000,6000));
                        } else {
                            return;
                        }

                        checkForAndFightBrother(config);

                        if(hintNpcModel()==null) {
                            int io = 0;
                            while (io < 2) {

                                if (!super.isRunning()) {
                                    break;
                                }

                                if(barrowsChest.click("Search")){
                                    sleep(500, 1500);
                                }

                                if (Rs2Widget.hasWidget("Barrows chest")) {
                                    break;
                                }

                                io++;
                            }

                            // suppliesCheck is the correct place to decide banking — after chest loot
                            suppliesCheck(config);

                            if(shouldBank){
                                Microbot.log("We should bank.");
                                ChestsOpened++;
                                WhoisTun = "Unknown";
                                inTunnels = false;
                            } else {
                                if(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID() == ItemID.BARROWS_TELEPORT){
                                    Rs2Inventory.interact("Barrows teleport", "Break");
                                    sleepUntil(() -> Rs2Player.getWorldLocation().getY() < 9600 || Rs2Player.getWorldLocation().getY() > 9730, Rs2Random.between(6000, 10000));
                                    ChestsOpened++;
                                    WhoisTun = "Unknown";
                                    inTunnels = false;
                                } else {
                                    if(Rs2Bank.isOpen()){
                                        closeBank();
                                        return;
                                    }
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

                if(shouldBank){
                    // Safety guard: never teleport to bank while inside the barrows region.
                    // inTunnels covers Y 9600-9730; plane==3 covers mound interiors.
                    // A stale shouldBank=true must not pull the player out mid-run.
                    boolean inBarrowsRegion = inTunnels || Rs2Player.getWorldLocation().getPlane() == 3;
                    if (inBarrowsRegion) {
                        Microbot.log("[shouldBank] Blocked: player is still in barrows region. Resetting shouldBank.");
                        shouldBank = false;
                        return;
                    }

                    if(!Rs2Bank.isOpen()){
                        stopFutureWalker();
                        outOfSupplies(config);
                        Rs2Bank.walkToBankAndUseBank(BankLocation.FEROX_ENCLAVE);
                        BreakHandlerScript.lockState.set(false);
                    } else {
                        Rs2Food ourfood = config.food();
                        int ourFoodsID = ourfood.getId();
                        String ourfoodsname = ourfood.getName();

                        if(Rs2Inventory.isFull() || Rs2Inventory.contains(it->it!=null&&it.getName().contains("'s") || it.getName().contains("Coins"))){
                            if(Rs2Inventory.contains(it->it!=null&&it.getName().contains("'s"))){
                                Rs2ItemModel piece = Rs2Inventory.get(it->it!=null&&it.getName().contains("'s"));

                                if(piece!=null){
                                    barrowsPieces.add(piece.getName());
                                    if(barrowsPieces.contains("Nothing yet.")){
                                        barrowsPieces.remove("Nothing yet.");
                                    }
                                }

                            }
                            Rs2Bank.depositAllExcept(neededRune, "Moonlight moth", "Moonlight moth mix (2)", "Teleport to house", "Spade", "Prayer potion(4)", "Prayer potion(3)", "Forgotten brew(4)", "Forgotten brew(3)", "Barrows teleport",
                                    ourfoodsname);
                        }

                        int howtoBank = Rs2Random.between(0,100);
                        if(!usingPoweredStaffs) {
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
                                        if(neededRune.equals("Wrath rune")){
                                            if(Rs2Bank.hasItem("Blood rune") && Rs2Bank.count("Blood rune") > config.minRuneAmount()){
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
                            if(outOfPoweredStaffCharges){
                                Microbot.log("We're out of staff charges. stopping...");
                                super.shutdown();
                            }
                        }

                        howtoBank = Rs2Random.between(0,100);
                        if(howtoBank<= 60){
                            if(Rs2Inventory.count(config.prayerRestoreType().getPrayerRestoreTypeID()) < Rs2Random.between(config.minPrayerPots(),config.targetPrayerPots())){
                                if(Rs2Bank.getBankItem(config.prayerRestoreType().getPrayerRestoreTypeID())!=null){
                                    if(Rs2Bank.getBankItem(config.prayerRestoreType().getPrayerRestoreTypeID()).getQuantity()>=config.targetPrayerPots()){
                                        int amt = ((Rs2Random.between(config.minPrayerPots(),config.targetPrayerPots())) - (Rs2Inventory.count(config.prayerRestoreType().getPrayerRestoreTypeID())));
                                        if(amt <= 0){
                                            amt = 1;
                                        }
                                        Microbot.log("Withdrawing "+amt);
                                        if(Rs2Bank.withdrawX(config.prayerRestoreType().getPrayerRestoreTypeID(), amt)){
                                            sleepUntil(()-> Rs2Inventory.count(config.prayerRestoreType().getPrayerRestoreTypeID()) > Rs2Random.between(4,8), Rs2Random.between(2000,4000));
                                        }
                                    } else {
                                        Microbot.log("We're out of "+config.prayerRestoreType().getPrayerRestoreTypeID()+" need at least "+config.targetPrayerPots()+" stopping...");
                                        super.shutdown();
                                    }
                                }
                            }
                        }

                        howtoBank = Rs2Random.between(0,100);
                        if(howtoBank<= 40){
                            if(config.minForgottenBrew() > 0) {
                                if (Rs2Inventory.count("Forgotten brew(4)") + Rs2Inventory.count("Forgotten brew(3)") < Rs2Random.between(config.minForgottenBrew(), config.targetForgottenBrew())) {
                                    if (Rs2Bank.getBankItem("Forgotten brew(4)") != null) {
                                        if (Rs2Bank.getBankItem("Forgotten brew(4)").getQuantity() >= config.targetForgottenBrew()) {
                                            int amt = ((Rs2Random.between(config.minForgottenBrew(), config.targetForgottenBrew())) - (Rs2Inventory.count("Forgotten brew(4)") + Rs2Inventory.count("Forgotten brew(3)")));
                                            if (amt <= 0) {
                                                amt = 1;
                                            }
                                            Microbot.log("Withdrawing " + amt);
                                            if (Rs2Bank.withdrawX("Forgotten brew(4)", amt)) {
                                                sleepUntil(() -> Rs2Inventory.count("Forgotten brew(4)") + Rs2Inventory.count("Forgotten brew(3)") > Rs2Random.between(1, 3), Rs2Random.between(2000, 4000));
                                            }
                                        } else {
                                            Microbot.log("We're out of " + " Forgotten brew " + " need at least " + config.targetForgottenBrew() + " stopping...");
                                            super.shutdown();
                                        }
                                    }
                                }
                            }
                        }
                        howtoBank = Rs2Random.between(0,100);
                        if(howtoBank<= 40){
                            if(Rs2Inventory.get(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID())==null || Rs2Inventory.get(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()).getQuantity() < Rs2Random.between(config.minBarrowsTeleports(),config.targetBarrowsTeleports())){
                                if(Rs2Bank.getBankItem(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID())!=null){
                                    if(Rs2Bank.getBankItem(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()).getQuantity()>=config.targetBarrowsTeleports()){
                                        if(Rs2Bank.withdrawX(config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID(), Rs2Random.between(config.minBarrowsTeleports(),config.targetBarrowsTeleports()))){
                                            sleep(Rs2Random.between(300,750));
                                        }
                                    } else {
                                        Microbot.log("We're out of "+config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()+" need at least "+config.targetBarrowsTeleports()+" stopping...");
                                        super.shutdown();
                                    }
                                } else {
                                    Microbot.log("We're out of "+config.selectedToBarrowsTPMethod().getToBarrowsTPMethodItemID()+" need at least "+config.targetBarrowsTeleports()+" stopping...");
                                    super.shutdown();
                                }
                            }
                        }
                        howtoBank = Rs2Random.between(0,100);
                        if(howtoBank<= 40){

                            if(Rs2Inventory.count(ourFoodsID) < config.targetFoodAmount()){
                                if(Rs2Bank.getBankItem(ourFoodsID)!=null){
                                    if(Rs2Bank.getBankItem(ourFoodsID).getQuantity()>=config.targetFoodAmount()){
                                        int amt = (Rs2Random.between(config.minFood(),config.targetFoodAmount()) - (Rs2Inventory.count(ourFoodsID)));
                                        if(amt <= 0){
                                            amt = 1;
                                        }
                                        Microbot.log("Withdrawing "+amt);
                                        if(Rs2Bank.withdrawX(ourFoodsID, amt)){
                                            sleepUntil(()-> Rs2Inventory.count(ourFoodsID) >= 10, Rs2Random.between(2000,4000));
                                        }
                                    } else {
                                        Microbot.log("We're out of "+ourfoodsname+" need at least "+config.targetFoodAmount()+" stopping...");
                                        super.shutdown();
                                    }
                                }
                            }
                        }

                        shouldBank = false;
                    }
                }

                long endTime = System.currentTimeMillis();
                long totalTime = endTime - startTime;
                System.out.println("Total time for loop " + totalTime);

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, scriptDelay, TimeUnit.MILLISECONDS);
        return true;
    }

