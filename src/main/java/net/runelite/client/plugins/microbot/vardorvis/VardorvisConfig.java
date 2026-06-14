package net.runelite.client.plugins.microbot.vardorvis;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("Vardorvis")
public interface VardorvisConfig extends Config {

    @ConfigSection(
            name = "Weapons",
            description = "Weapon item IDs",
            position = 0
    )
    String weaponSection = "weapons";

    @ConfigSection(
            name = "Food",
            description = "Food settings",
            position = 1
    )
    String foodSection = "food";

    @ConfigItem(
            keyName = "mainWeaponId",
            name = "Main weapon ID",
            description = "Item ID of your main melee weapon (default: 29796)",
            section = "weapons",
            position = 0
    )
    default int mainWeaponId() {
        return 29796;
    }

    @ConfigItem(
            keyName = "specWeaponId",
            name = "Spec weapon ID",
            description = "Item ID of your spec weapon (default: 27690 = Voidwaker)",
            section = "weapons",
            position = 1
    )
    default int specWeaponId() {
        return 27690;
    }

    @ConfigItem(
            keyName = "useSpec",
            name = "Use spec weapon",
            description = "Enable spec weapon usage during the fight",
            section = "weapons",
            position = 2
    )
    default boolean useSpec() {
        return true;
    }

    @ConfigItem(
            keyName = "sharkAmount",
            name = "Sharks per trip",
            description = "Number of Sharks to withdraw per banking trip",
            section = "food",
            position = 0
    )
    default int sharkAmount() {
        return 11;
    }

    @ConfigItem(
            keyName = "karambwanAmount",
            name = "Karambwan per trip",
            description = "Number of Cooked Karambwan to withdraw per banking trip",
            section = "food",
            position = 1
    )
    default int karambwanAmount() {
        return 11;
    }
}
