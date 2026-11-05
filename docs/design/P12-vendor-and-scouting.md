# P12: giving the last two reserved ids something to do

Written before the code it describes, as the TDS gate requires.

Two ids have sat reserved since P1's very first sentence about content scope: "Modifiers, vendor
services, scouting profiles and regional pools are reserved ids only until P8-P11 give them
behaviour." Modifiers got P8. Regional pools got P10. Vendor services and scouting profiles were
pushed past P11 by name, in the conversation that scoped that phase, rather than resolved by accident
-- this is the phase that was reserved for them.

Both land on the same real gap: TDS #18 calls for players to "spend individual CobbleDollar wallets,"
but P9 -- the phase actually named `Economy/Vendor/Reward Transactions` in the TDS's own dependency
graph -- deliberately shipped rewards as direct item grants with no currency at all. Its own doc says
so explicitly: "there is nothing to award a balance in." A vendor cannot exist without something to
charge for a heal, so P12 is also the phase that finally answers what a CobbleDollar actually is.

## Decisions taken into P12

| Decision | Choice | Why |
|---|---|---|
| Scope | **Vendor services and scouting profiles, and nothing else.** No physical NPC entity, no preparation screen, no infinite-mode content | Matches what P11's own scoping conversation reserved for this phase. TDS #16 calls the vendor a "non-combat utility NPC," but a spawned, pathfinding mob is new entity/AI machinery this codebase has never needed and none of P9's economy or P8's draft required one either -- the draft is voted on entirely through `RunsCommand`, with no physical interaction point at all. P12 follows that exact precedent: the vendor's *function* (buy a service for CobbleDollars) is what TDS #16 actually locks, not a mob standing in a room. A visual NPC presence is content, the same way P10 shipped a regional framework without real jersey rosters. |
| CobbleDollars | **A reserved item id, wallet-credited** | Reward tables grant a CobbleDollar entry exactly like any other item -- `RewardTableDefinition`, `RewardValuation`'s growth step, `RewardDraw`'s rolling, and the ledger's at-risk/banked/forfeited rules all apply unchanged, because none of them care what an item id *means*, only that it is one. Only the delivery boundary (`RewardDelivery`) changes: recognizing the one reserved id and crediting a wallet instead of inserting an `ItemStack`. This satisfies TDS #18's literal "wallet" (can't be dropped, traded, or lost with a death) without a second parallel earning pipeline next to the one P9 already built and proved. |
| Wallet scope | **Per player, not per run** | TDS #18 says "individual CobbleDollar wallets," not "a run's wallet." A `TowerWalletStore` keyed by player UUID, the same `SavedData` shape `TowerPendingRewardStore` already is, outlives any one run -- CobbleDollars earned in a run that later gets abandoned are not lost, the same way a bank account is not tied to one shopping trip. |
| Vendor purchase interface | **A real client screen**, opened by command | Reuses P11's screen infrastructure (`RewardRevealScreen`'s shape) rather than a third presentation style next to chat and titles. Command-triggered because there is no physical interaction point to trigger it from (see Scope above) -- the same way the draft is opened by a transition, not a location. |
| Scouting reveal | **A real client screen**, shown when a floor's opponents are known | Chosen over the plain-title route P11 used for jersey encounters: a jersey title is one line about one already-visible opponent, but a scouting reveal is several categories about opponent(s) not yet fought, closer in shape to the reward reveal's itemized list than to a one-line title card. |
| What "difficulty" means for concealment | **Floor depth**, the same axis every other scaling rule in this codebase already reads | TDS #22's "higher difficulty may conceal information" has nothing else to attach to: there is no standalone difficulty field anywhere in `RulesetDefinition` or elsewhere, and floor depth is what `TowerLevelPolicy`, `RegionalWeighting` and the reward table's growth step already treat as the one axis difficulty rises on. A sixth "difficulty" concept living somewhere else would be the second one, the mistake `RegionalWeighting`'s own doc comment already warns its own file against being a third pattern of. |
| Scouting modifiers | **One more `ModifierEffects` field**, not a new mechanism | TDS #49's "scouting modifiers may reveal additional data" is exactly the shape `levelOffset`, `rewardPercent` and every other drafted effect already take: summed once in `ModifierEffects.of`, read once by the encounter-side function that needs it. A modifier that wants to reveal more of a floor is not a new kind of thing to build support for. |
| Vendor availability | **Per-run purchase counts, not a global cooldown** | TDS #19's "controlled finite availability where appropriate" is a property of one run's trip through the tower, the same lifetime `PersistedRun.ledger()` and `lastBankedFloor()` already have -- a new run starts with a full shop again, the same way it starts with an empty ledger. |
| When the vendor is open | **`INTERMISSION` only** | TDS #16's "no automatic free *between-floor* healing" frames this as strictly a between-floor transaction. `RunState.INTERMISSION` is already the one state that means exactly that; no floor-active purchase path is built, so a vendor cannot be used to stall or bail out mid-fight. |

## 1. CobbleDollars and the wallet

`com.cobbletowers.economy.CobbleDollars` -- new internal domain, one constant:

```
public static final ResourceLocation ITEM_ID = ResourceLocation.fromNamespaceAndPath("cobbletowers", "cobble_dollar");
```

Not a registered Minecraft `Item`. It never becomes an `ItemStack`, so it needs no model, no
recipe, no registry entry -- only an id a reward table can name and `RewardDelivery` can recognize.
A `reward_tables/*.json` entry naming it is authored exactly like any other entry (species-shaped
`{"item": "cobbletowers:cobble_dollar", "min_amount": ..., "max_amount": ...}`); `RewardValuation`
does not know or care that this one is special.

`com.cobbletowers.persistence.TowerWalletStore` -- a `SavedData`, the same shape
`TowerPendingRewardStore` already is: `Map<UUID, Long> balances`, `credit(playerId, amount)`,
`debit(playerId, amount)` returning whether the player could afford it (never allowing a negative
balance), `balanceOf(playerId)`. Long, not int: a wallet accumulates across every run a player ever
finishes, and TDS #9's own "infinite rewards must use bounded/diminishing economic scaling" is a
promise about growth *rate*, not a promise the total stays inside 32 bits forever.

`RewardDelivery.deliver`'s per-reward loop gains one branch, right where it currently calls `give`:

```
for (PendingTowerReward reward : queue) {
    if (reward.item().equals(CobbleDollars.ITEM_ID)) {
        TowerWalletStore.get(server).credit(player.getUUID(), reward.amount());
        delivered.add(reward);
    } else if (give(player, reward.item(), reward.amount())) {
        delivered.add(reward);
    }
}
```

Both branches still land in `delivered`, so the existing chat summary and P11's `RewardRevealPayload`
show a CobbleDollar grant exactly like any other line -- a player sees "cobbletowers:cobble_dollar x40"
the same way they would see any other reward, which is honest about what this phase does and does not
do for its own presentation (a wallet-balance display is not part of this phase; see Assumptions).

## 2. `VendorServiceDefinition` -- the tenth registry kind

Data-driven, from `data/<ns>/cobbletowers/vendor_services/*.json`, the tenth folder under the same
registry `load()`/digest/cross-reference discipline every other kind already follows.

```
VendorServiceDefinition(
    id, schemaVersion, revision, displayName,
    priceCobbleDollars: int,
    effect: VendorEffect,     // FULL_HEAL, CURE_STATUS, MYSTERY_BAG  (TDS #20 C: recovery, not exportable items; the bag is the one exception)
    maxPurchasesPerRun: int)  // 0 means unlimited
```

TDS #20 C rules out selling temporary items outright ("primarily direct healing services rather than
exportable temporary items, preventing tower-shop exploitation"), so `VendorEffect` is a closed enum of
what a service actually *does* to a target's live party, not an item stack handed over -- there is
nothing here to export, because nothing here is a possession.

**Reviewed exception, 2026-11-05: the goblin's Mystery Bag** (`vendor_services/mystery_bag.json`, 500 CobbleDollars, 2 per run). It is the one service that
hands over items: a random AscensionLib currency, rolled on the server (`MysteryBag`, odds out of 1000) and paid through the pending-reward queue, so a full
inventory holds the prize for `/tower claim`. Resonance Dust is two bags in three (67.6%) and pays 40 to 60; every other currency pays one; the epic and legendary
currencies together are 5.4% and the Unique Catalyst is the jackpot at 0.1% (one bag in a thousand). Bags may be traded
(owner decision, 2026-11-05), which is why the good materials are kept very rare. The wallet it spends is earned only in the tower and already trimmed by the daily completion budget, so the bag converts tower earnings and
opens no new income. It refuses (and refunds) when AscensionLib is not installed. The Tower Mark, boss-loot and respec currencies are low-weighted on purpose.

`VendorServices.apply(effect, ServerPlayer target)` -- for `FULL_HEAL`, every `Pokemon` in
`Cobblemon.INSTANCE.getStorage().getParty(target)` has its current health set to its max; for
`CURE_STATUS`, every persistent status condition is cleared. The same live-party read
`recallParties`/`levelsOf` already use, not a new way of reaching a player's party.

## 3. The vendor purchase

`PersistedRun` gains one more field, the same shape `lastBankedFloor` already is:
`vendorPurchases: Map<ResourceLocation, Integer>`, service id to times bought this run. Checked
against `maxPurchasesPerRun` before a purchase is allowed; `0` skips the check entirely.

`VendorPurchaseService.purchase(server, runId, payingPlayerId, targetPlayerId, serviceId)`:

1. Refuses unless the run is in `RunState.INTERMISSION` -- TDS #16's "between floors," enforced at the
   one place that matters rather than trusted to whoever calls in.
2. Refuses if the service's per-run cap is already spent.
3. `TowerWalletStore.debit(payingPlayerId, service.priceCobbleDollars())`; refuses if it returns
   insufficient funds, spending nothing.
4. Applies the service's effect to `targetPlayerId` -- TDS #18's "may pay for recovery targeted at
   teammates," so `payingPlayerId` and `targetPlayerId` are independent parameters, not the same
   argument twice with a same-player shortcut layered on top.
5. Increments `vendorPurchases` and writes the run. Not a forced checkpoint (TDS #28's list of
   checkpoint-worthy transitions does not include a vendor sale): the money has already moved by step
   3, and a crash between it and this write costs a lost purchase count, not a duplicated one --
   `TowerWalletStore`'s own debit is the actual point of no return, the same asymmetry P9's own
   grant-then-store ordering reasoned through for exactly this kind of two-file write.

## 4. Public API and the shop screen

The vendor catalog is not part of the public API (a `VendorServiceView` was planned and never built).
`com.cobbletowers.network.VendorCatalogPayload` (S2C) carries a run's
resolved catalog when requested; `com.cobbletowers.network.VendorPurchasePayload` (C2S) names a
service and a target player, mirroring `CycleTeammatePayload`'s posture: the server re-validates every
condition in §3 itself and never trusts a client's claim about affordability or eligibility.

`/cobbletowers vendor` (new subcommand, `RunsCommand` or a sibling), available to a player in a run,
sends `VendorCatalogPayload` and the client opens `VendorScreen` -- a list of services, price, and a
buy button per teammate they could target, the same read-only-until-clicked shape `RewardRevealScreen`
established, except a button here does something: sends `VendorPurchasePayload` and waits for the next
`VendorCatalogPayload` (refreshed remaining-purchases count) to confirm it landed.

## 5. `ScoutingProfileDefinition` -- the eleventh registry kind

Data-driven, from `data/<ns>/cobbletowers/scouting_profiles/*.json`.

```
ScoutingProfileDefinition(
    id, schemaVersion, revision,
    categories: List<RevealCategory>)

RevealCategory(name: String, concealedFromFloor: int)   // -1: never concealed
```

A tower names one via a new `TowerDefinition.scoutingProfile` field (`Optional<ResourceLocation>`,
resolved the same "names X, which is not loaded" cross-reference shape `regional_theme` already is).
Neutral can name none, the same way it names no jersey theme -- a tower with nothing to say about
scouting reveals everything by default, per TDS #49's "observable information reveals naturally"
baseline.

## 6. `ScoutingReveal` -- the fifth pure floor-scaling function

`com.cobbletowers.encounter.ScoutingReveal`, pure, no Minecraft -- the fifth number in this codebase
that moves with floor depth, after level, reward growth, jersey weighting and (now) this.

```
public static boolean isRevealed(RevealCategory category, int floorIndex, int scoutingBonus) {
    return category.concealedFromFloor() < 0 || floorIndex < category.concealedFromFloor() + scoutingBonus;
}
```

`scoutingBonus` comes from `ModifierEffects.scoutingBonus`, a new field summed the same way
`levelOffset` already is (offsets add, TDS #56); a drafted scouting modifier pushes concealment
deeper rather than un-concealing something already decided, keeping the same "no downstream code
asks which modifiers a run carries" promise every other effect already keeps.

Feeding it required a sixth `ModifierType`. None of the five TDS #56 originally named (enemy,
encounter, player constraint, field, reward) has anything to do with revealing information, and that
decision's own list ends "...etc.," leaving room for exactly this. `ModifierType.SCOUTING` joins them
the same way `PLAYER_CONSTRAINT` and `FIELD` did in P1 -- declared, validated by
`ModifierDefinition.validateEffectMatchesType`, and effective the moment the phase that needs it
arrives. One real modifier (`keen_eye.json`, `scouting_bonus: 2`) ships with it, proving the type the
same way the other five are proven by shipped content rather than by schema alone.

What a revealed category actually *shows* is read straight off what already exists -- `typing` from
Cobblemon's own species data for the drawn `EncounterSnapshot.species()`, `threat_level` from the
snapshot's `level()` relative to the party's own average, `field_conditions` from the floor's own
`FloorDefinition.modifierIds`. Nothing new is computed to answer TDS #22's list; a scouting profile
only decides *when* a fact already sitting in this codebase is allowed to reach a player, exactly what
P10's own assumptions section predicted for this phase: "every fact a scouting reveal would show...
already exists... a scouting profile is a reveal-threshold config... not a new source of gameplay
data."

## 7. The scouting screen

`com.cobbletowers.network.ScoutingRevealPayload` (S2C): floor index and the revealed
category/value pairs for that floor's draw, built once `EncounterDraw` has run for the floor (the
same point `TowerEncounters.sendNextOpponent` already resolves a theme and a snapshot) and sent
alongside the encounter starting rather than gating it -- a slow or absent scouting screen must never
delay a floor the way a stuck draft already cannot (P8's own non-blocking posture for anything that is
not the encounter itself). `ScoutingScreen` (client) lists what came through; a concealed category is
simply absent from the list, not shown-and-blanked, so a player cannot infer a threat level exists just
because a screen has an empty row for it.

## 8. Cross-reference validation

Two more entries in `TowerContent.of`'s problem list, the established "names X, which is not loaded"
shape:

- A tower's `scouting_profile` id that does not resolve.
- A vendor service's `effect` naming an unknown `VendorEffect` -- reported the same way an out-of-range
  `RulesetDefinition` bound already is, at construction, not at use.

`TowerContent.EMPTY` and `of(...)` gain the tenth and eleventh maps (`vendorServices`,
`scoutingProfiles`), the same one-argument growth every prior phase's new kind has been.

## 9. Commands

- **`/cobbletowers runs vendor`** -- opens the shop screen for the caller's own run (§4). Nested under
  `runs` rather than a new top-level literal, matching where `reward show` and `draft show` already
  sit: one player-facing namespace, not two.
- **`/cobbletowers runs vendor credit <player> <amount>`** and **`/cobbletowers runs vendor buy <run>
  <service> <payer> <target>`** -- operator tools, permission 2, added for the same reason `runs grant`
  was: a live test has no client to earn CobbleDollars realistically (the reward table's own entry is
  a probabilistic roll) or to send a purchase over the wire at all, so these are the only way to pin
  either down outside a real Minecraft client. `buy` calls `VendorPurchaseService.purchase` directly
  and reports its `Result`, exercising the exact same code path a real purchase would.
- **`/cobbletowers definitions`**'s per-kind count line gains vendor services and scouting profiles,
  the two kinds this phase adds, the same place P10 added regional themes to and P9's reward tables
  should already be on.
- No new admin command for wallets: `/cobbletowers runs show` already prints a run's committed
  transactions and ledger; a wallet balance is a player fact, not a run fact, so it belongs on a
  player-facing surface (the vendor screen shows the caller's own balance) rather than bolted onto a
  run-scoped command for something a run does not own.

## 10. Performance impact

- **Wallet reads/writes** are one more `SavedData` map lookup, the same cost class
  `TowerPendingRewardStore` already pays, bounded by players who have ever earned a CobbleDollar.
- **`ScoutingReveal.isRevealed`** is a comparison per category per floor draw -- bounded by a profile's
  category count, the same arithmetic cost `RegionalWeighting` already pays per pool entry.
- **No new per-tick work.** Both the vendor and scouting payloads are sent from existing event-driven
  call sites (a command, a floor's encounter start), not polled.

## 11. Assumptions and constraints

- **One common vendor catalog, shared by every tower.** TDS #19's "milestone/tower-specific inventory
  behavior" is not built: `VendorCatalogPayload` sends every loaded `VendorServiceDefinition` to every
  run, regardless of tower. A themed or milestone-specific shop is content variation for a later pass,
  the same "framework now, per-tower variation later" shape P10 left Tideforge/Rootvale/Duskvale's
  actual rosters in.
- **The vendor screen's buy buttons target the local player only.** TDS #18's "recovery targeted at
  teammates" is carried by the wire protocol (`VendorPurchasePayload.targetPlayerId` is independent of
  the sender), but a teammate picker in `VendorScreen` is a client-side enhancement this phase does not
  build -- the protocol does not need to change for one to be added later.
- **No physical vendor NPC.** TDS #16's function is built; its visual presence in the world is
  content for a later pass, the same way P10 left Tideforge/Rootvale/Duskvale's actual rosters
  unbuilt.
- **No wallet-balance HUD.** A player learns their balance from the vendor screen when they open it,
  not from an always-on display -- P11's `SpectatorHud` is scoped to spectating, and giving every
  player a permanent balance overlay is a presentation decision this phase does not make on its own.
- **No preparation-time purchases.** The vendor is `INTERMISSION`-only; a player cannot spend before a
  run's first floor or after its last, matching TDS #16's "between floors" literally rather than
  extending it.
- **`VendorEffect` has two members.** `FULL_HEAL` and `CURE_STATUS` are what TDS #20 C actually asks
  for ("direct healing services"); a revive-early service is deliberately not built, because the
  existing revival rule ("if at least one teammate clears the floor") is a stated design decision this
  phase does not reopen, not an oversight.
- **No Ascension/infinite-mode scouting or vendor content.** Same reservation P10 left for infinite
  mode's own regional pool: unbuilt until infinite mode itself has a design doc to decide it in.
- **CobbleDollars are visible as an oddly-named "item" in reward summaries.** The chat line and P11's
  reveal screen show `cobbletowers:cobble_dollar x40` like any other grant, because neither was built
  to special-case a currency. A friendlier reward-summary rendering is a presentation refinement, not
  a behavior this phase is wrong to skip.
