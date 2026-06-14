# Vardorvis Killer

A Microbot Hub plugin that fully automates the Vardorvis boss fight, including banking, travel, and loot collection.

## Features

- Full fight automation (attack, prayer switching, tile movement)
- Automatic prayer flicking: Protect from Melee default, switches to Protect from Missiles on ranged projectile
- Axe avoidance: detects axes spawning on the safe tile and opposite side, moves accordingly
- Blood splat clicking with randomised delay (30-90 ms)
- Spec weapon support (Voidwaker spec -> switch back to main weapon)
- Dual-combo eating: Shark + Cooked Karambwan within the same tick
- Emergency teleport when food runs out and HP < 30
- Banking via Grand Exchange (Ring of Shadows teleport)
- POH restore via Ornate Rejuvenation Pool

## Requirements

| Slot | Item |
|------|------|
| Weapon | Your main melee weapon (ID 29796) |
| Spec weapon | Voidwaker (ID 27690) |
| Ring | Ring of Shadows |
| Inventory slot 27 | Teleport to house (tablet) |

**Inventory loaded per trip:**
- 1x Super combat potion(4)
- 2x Prayer potion(4)
- 11x Shark
- 11x Cooked Karambwan
- Ring of Shadows (slot 26)
- Teleport to house (slot 27)

## Quick prayers

Set your quick prayers to **Protect from Melee** (and optionally Piety). The script enables Quick Prayers on fight start and manages individual prayer toggles during the fight.

## States

| State | Description |
|-------|-------------|
| `UNKNOWN` | Outside of known regions - script idles |
| `POH` | Inside player-owned house - uses pool, then teleports to GE |
| `BANK` | Grand Exchange bank - restocks supplies |
| `WALK_TO_BOSS` | Walking through Stranglewood to the cave entrance |
| `IN_BOSS` | Inside instance, preparing to fight |
| `FIGHTING` | Active fight loop |
| `AFTER_FIGHT` | Boss dead - loot then teleport home |

## Notes

- Targets NPC ID **12223** (Vardorvis); avoids axes NPC IDs **12225** / **12227**
- Safe tile: `(1129, 3423)` - avoid tile: `(1131, 3421)`
- Adapted from [tmnlbus/OSRS-Vardorvis](https://github.com/tmnlbus/OSRS-Vardorvis) for the current Microbot Hub API
