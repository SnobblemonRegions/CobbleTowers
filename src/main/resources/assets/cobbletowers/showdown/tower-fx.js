'use strict';
// CobbleTowers' Showdown extension (P23, docs/design/P23-showdown-battle-effects.md), installed by CobbleRaids as
// ext-cobbletowers-fx.js. A battle whose format carries `towerFx` (declarative operations) has each applied at start:
// weather, terrain, stat stages, HP, status, side condition or damage multiplier. Wraps only Battle.prototype.start
// and does nothing otherwise; each operation is validated, bounded and isolated in its own try/catch.

const MAX_OPS = 32;
const STATS = ['atk', 'def', 'spa', 'spd', 'spe', 'accuracy', 'evasion'];
const STATUSES = ['brn', 'par', 'psn', 'tox', 'slp', 'frz'];
// Fixed lists, since the dex cannot tell a terrain from other conditions here and a whitelist is safer. Primal
// weathers are left out: the other side cannot remove them.
const WEATHERS = ['raindance', 'sunnyday', 'sandstorm', 'hail', 'snowscape'];
const TERRAINS = ['electricterrain', 'grassyterrain', 'mistyterrain', 'psychicterrain'];
const SIDE_CONDITIONS = ['tailwind', 'reflect', 'lightscreen', 'auroraveil', 'safeguard', 'mist'];
const SIDE_ID = /^p[1-9]$/;
// Over-the-cap EVs (P30): Cobblemon refuses over 252 and Showdown clamps to 255 when building a team, but neither
// applies to a Pokemon already in a battle (stats use floor(ev / 4)). Scaling is applied to the battle's own copy and
// never written back.
const EV_STATS = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
const MAX_EV_AMOUNT = 2000;   // one operation
const MAX_EV_TOTAL = 4000;    // per stat, however many operations: already +1000 stat points before level scaling

function clamp(value, low, high, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(low, Math.min(high, Math.trunc(n)));
}

/** A line in the battle log the player can read; never throws. */
function note(battle, text) {
  try {
    battle.add('-message', String(text).slice(0, 200));
  } catch (err) {
    /* a log line is never worth losing a battle over */
  }
}

/** The concrete sides an operation names, silently dropping any that do not exist. */
function sidesOf(battle, op) {
  const wanted = Array.isArray(op.sides) ? op.sides : [];
  const sides = [];
  for (const id of wanted) {
    if (typeof id !== 'string' || !SIDE_ID.test(id)) continue;
    const side = battle.sides.find(candidate => candidate && candidate.id === id);
    if (side && !sides.includes(side)) sides.push(side);
  }
  return sides;
}

/** A rule's optional tag, so a later `clear` op can take it back out (a phase swapping the rule the one before it set). */
function tagOf(op) {
  return typeof op.tag === 'string' && op.tag ? op.tag.slice(0, 40) : undefined;
}

function activesOf(sides) {
  const out = [];
  for (const side of sides) {
    for (const pokemon of side.active) if (pokemon && !pokemon.fainted) out.push(pokemon);
  }
  return out;
}

function validType(battle, name) {
  if (name === undefined || name === null || name === 'any') return 'any';
  if (typeof name !== 'string') return null;
  const type = battle.dex.types.get(name);
  return type && type.exists ? type.name : null;
}

// ---- the operations --------------------------------------------------------------------------------------------

function applyWeather(battle, op) {
  const id = String(op.id || '');
  if (!WEATHERS.includes(id)) throw new Error(`${id} is not a supported weather`);
  const condition = battle.dex.conditions.get(id);
  if (!condition.exists) throw new Error(`${id} is not known to this simulator`);
  const source = activesOf(sidesOf(battle, op))[0] || activesOf(battle.sides.filter(Boolean))[0] || null;
  battle.field.setWeather(id, source);
  // 0 is Showdown's "does not expire", which is the default for an armor bonus the player chose to wear.
  if (battle.field.weatherState) battle.field.weatherState.duration = clamp(op.duration, 0, 20, 0);
  if (battle.field.weather !== condition.id) throw new Error(`${id} could not be set`);
}

function applyTerrain(battle, op) {
  const id = String(op.id || '');
  if (!TERRAINS.includes(id)) throw new Error(`${id} is not a supported terrain`);
  const condition = battle.dex.conditions.get(id);
  if (!condition.exists) throw new Error(`${id} is not known to this simulator`);
  const source = activesOf(sidesOf(battle, op))[0] || activesOf(battle.sides.filter(Boolean))[0] || null;
  battle.field.setTerrain(id, source);
  if (battle.field.terrainState) battle.field.terrainState.duration = clamp(op.duration, 0, 20, 0);
  if (battle.field.terrain !== condition.id) throw new Error(`${id} could not be set`);
}

function applyBoost(battle, op) {
  if (!STATS.includes(op.stat)) throw new Error('boost needs a stat');
  const stages = clamp(op.stages, -6, 6, 0);
  if (stages === 0) return;
  for (const pokemon of activesOf(sidesOf(battle, op))) {
    battle.boost({[op.stat]: stages}, pokemon, pokemon, null, false, true);
  }
}

/** Lowers the leads' HP to a percentage. Never raises it: this is a handicap, not a heal. */
function applyHp(battle, op) {
  const percent = clamp(op.percent, 1, 100, 100);
  for (const pokemon of activesOf(sidesOf(battle, op))) {
    const target = Math.max(1, Math.floor(pokemon.maxhp * percent / 100));
    const difference = pokemon.hp - target;
    if (difference > 0) battle.directDamage(difference, pokemon);
  }
}

function applyStatus(battle, op) {
  if (!STATUSES.includes(op.status)) throw new Error('status needs one of ' + STATUSES.join(', '));
  for (const pokemon of activesOf(sidesOf(battle, op))) pokemon.setStatus(op.status, null, null, false);
}

function applySideCondition(battle, op) {
  const id = String(op.id || '');
  if (!SIDE_CONDITIONS.includes(id)) throw new Error(`${id} is not a supported side condition`);
  for (const side of sidesOf(battle, op)) {
    const source = side.active.find(pokemon => pokemon && !pokemon.fainted) || null;
    side.addSideCondition(id, source);
    if (side.sideConditions[id] && typeof op.duration === 'number') {
      side.sideConditions[id].duration = clamp(op.duration, 0, 20, 5);
    }
  }
}

/**
 * Makes the named sides' Pokemon ignore their held items for this battle (Tideforge's rule). The simulator already
 * skips every item effect for a Pokemon whose ignoringItem() is true (it is how Embargo and Magic Room work), so this
 * replaces that one method on each Pokemon of the side, nothing else; the item is not removed or changed.
 */
function applySuppressItems(battle, op) {
  for (const side of sidesOf(battle, op)) {
    for (const pokemon of side.pokemon) {
      if (!pokemon || typeof pokemon.ignoringItem !== 'function') continue;
      pokemon.ignoringItem = function () { return true; };
    }
  }
}

/** Records a drain rule: the named sides heal `percent` of the damage their moves deal. The wrapper is installed once. */
function addDrainRule(battle, op, rules) {
  const sides = sidesOf(battle, op).map(side => side.id);
  if (sides.length === 0) return;
  rules.drain.push({sides, percent: clamp(op.percent, 1, 50, 10), tag: tagOf(op)});
}

/** Records a damage rule; the wrapper that enforces it is installed once, after every operation has run. */
function addDamageRule(battle, op, rules, key) {
  const type = validType(battle, op.type);
  if (type === null) throw new Error(`${op.type} is not a type`);
  const sides = sidesOf(battle, op).map(side => side.id);
  if (sides.length === 0) return;
  rules[key].push({sides, type, percent: clamp(op.percent, 1, 300, 100), tag: tagOf(op)});
}

/**
 * Raises every Pokemon on the named sides (the whole team, so a switch-in is as strong) by `amount` EVs in one stat
 * or all six, then recomputes stats as the simulator does. Runs before the battle starts so the first switch-in
 * reports the real HP.
 */
function applyEvs(battle, op) {
  const amount = clamp(op.amount, 1, MAX_EV_AMOUNT, 0);
  if (amount === 0) throw new Error('evs needs a positive amount');
  let stats = EV_STATS;
  if (op.stat !== undefined && op.stat !== null && op.stat !== 'all') {
    if (!EV_STATS.includes(op.stat)) throw new Error(`${op.stat} is not a stat`);
    stats = [op.stat];
  }
  for (const side of sidesOf(battle, op)) {
    for (const pokemon of side.pokemon) {
      if (!pokemon || !pokemon.set || !pokemon.species) continue;
      const before = pokemon.maxhp;
      for (const stat of stats) {
        pokemon.set.evs[stat] = Math.min(MAX_EV_TOTAL, (Number(pokemon.set.evs[stat]) || 0) + amount);
      }
      restat(battle, pokemon);
      (battle.towerFxEvLog = battle.towerFxEvLog || []).push(`${side.id}:${pokemon.species.name} +${amount} hp ${before}->${pokemon.maxhp} atk ${pokemon.storedStats.atk}`);
    }
  }
}

/** Recomputes a Pokemon's stored stats from its (possibly raised) EVs, keeping its HP at the same fraction. */
function restat(battle, pokemon) {
  const stats = battle.spreadModify(pokemon.species.baseStats, pokemon.set);
  pokemon.baseStoredStats = Object.assign({}, stats);
  for (const name of Object.keys(pokemon.storedStats)) {
    pokemon.storedStats[name] = stats[name];
    if (pokemon.modifiedStats) pokemon.modifiedStats[name] = stats[name];
  }
  pokemon.speed = pokemon.storedStats.spe;
  if (pokemon.species.maxHP) return;   // a fixed-HP species (Shedinja) keeps its one HP
  const oldMax = pokemon.maxhp || 1;
  const newMax = stats.hp;
  pokemon.hp = pokemon.hp > 0 ? Math.max(1, Math.min(newMax, Math.round(pokemon.hp * newMax / oldMax))) : 0;
  pokemon.baseMaxhp = newMax;
  pokemon.maxhp = newMax;
}

/** Operations that must be in place before the first switch-in is announced. */
const PRE_START = {
  evs: (battle, op) => applyEvs(battle, op),
};

function applyPreStart(battle) {
  const fx = battle.format && battle.format.towerFx;
  if (!Array.isArray(fx)) return;
  let applied = 0;
  for (const op of fx.slice(0, MAX_OPS)) {
    const name = op && typeof op === 'object' ? op.op : undefined;
    if (typeof name !== 'string' || !Object.prototype.hasOwnProperty.call(PRE_START, name)) continue;
    try {
      PRE_START[name](battle, op);
      applied++;
    } catch (err) {
      note(battle, `A tower effect (${name}) could not be applied: ${err && err.message}`);
    }
  }
  battle.towerFxPreApplied = applied;
  // Said in the server log, like the main summary: only this proves the raised stats are the ones in the battle.
  try {
    if (battle.towerFxEvLog) console.log(`[CobbleTowers] EVs raised: ${battle.towerFxEvLog.join('; ')}`);
  } catch (err) {
    /* logging is never worth a battle */
  }
}

const OPERATIONS = {
  weather: (battle, op) => applyWeather(battle, op),
  terrain: (battle, op) => applyTerrain(battle, op),
  boost: (battle, op) => applyBoost(battle, op),
  hp: (battle, op) => applyHp(battle, op),
  status: (battle, op) => applyStatus(battle, op),
  sidecondition: (battle, op) => applySideCondition(battle, op),
  damage: (battle, op, rules) => addDamageRule(battle, op, rules, 'dealt'),
  resist: (battle, op, rules) => addDamageRule(battle, op, rules, 'taken'),
  suppress_items: (battle, op) => applySuppressItems(battle, op),
  drain: (battle, op, rules) => addDrainRule(battle, op, rules),
  enrage: (battle, op, rules) => addEnrageRule(battle, op, rules),
  cap: (battle, op, rules) => addCapRule(battle, op, rules),
  heal: (battle, op) => applyHealTo(battle, op),
  cure: (battle, op) => applyCure(battle, op),
  clear: (battle, op, rules) => applyClear(battle, op, rules),
  phase: (battle, op, rules) => addPhase(battle, op, rules),
};

// ---- boss phases (P58) -----------------------------------------------------------------------------------------
// A `phase` op names the side it watches (the boss), a trigger and a bundle of the operations above. The watcher fires
// each phase once, from the action hook (HP) and the turn hook (turn number), and says so in the battle log and the
// server log. Everything here fails open: a phase that throws is skipped, never a hung battle.

const MAX_PHASES = 6;
const MAX_PHASE_OPS = 8;
/** What a phase may do. `phase` itself is left out: a phase cannot create phases. */
const PHASE_OPS = ['weather', 'terrain', 'boost', 'status', 'sidecondition', 'heal', 'cure', 'damage', 'resist', 'drain',
  'enrage', 'clear'];

/** Restores the named sides' actives up to `to_percent` of their maximum HP, never lowers them. */
function applyHealTo(battle, op) {
  const percent = clamp(op.to_percent, 1, 100, 0);
  if (percent === 0) throw new Error('heal needs to_percent');
  for (const pokemon of activesOf(sidesOf(battle, op))) {
    const target = Math.floor(pokemon.maxhp * percent / 100);
    if (target > pokemon.hp) battle.heal(target - pokemon.hp, pokemon, pokemon);
  }
}

/** Takes the named sides' actives back to zero on every stat stage that is below it. */
function applyCure(battle, op) {
  for (const pokemon of activesOf(sidesOf(battle, op))) {
    let cleared = false;
    for (const stat of Object.keys(pokemon.boosts)) {
      if (pokemon.boosts[stat] < 0) {
        pokemon.boosts[stat] = 0;
        cleared = true;
      }
    }
    if (cleared) battle.add('-clearnegativeboost', pokemon, '[silent]');
  }
}

/** Removes every damage, resist, drain and enrage rule that carries `tag`. */
function applyClear(battle, op, rules) {
  const tag = tagOf(op);
  if (!tag) throw new Error('clear needs a tag');
  for (const key of ['dealt', 'taken', 'drain', 'enrage']) rules[key] = rules[key].filter(rule => rule.tag !== tag);
}

function addPhase(battle, op, rules) {
  if (rules.phases.length >= MAX_PHASES) throw new Error('too many phases');
  const watches = sidesOf(battle, op).map(side => side.id);
  if (!watches.length) throw new Error('a phase needs the side it watches');
  const when = op.when && typeof op.when === 'object' ? op.when : {};
  const hpBelow = when.hp_below === undefined ? 0 : clamp(when.hp_below, 1, 99, 0);
  const turn = when.turn === undefined ? 0 : clamp(when.turn, 2, 50, 0);
  if (!hpBelow && !turn) throw new Error('a phase needs when.hp_below or when.turn');
  const todo = (Array.isArray(op.do) ? op.do : []).slice(0, MAX_PHASE_OPS)
    .filter(inner => inner && typeof inner === 'object' && PHASE_OPS.includes(inner.op));
  rules.phases.push({id: String(op.id || 'phase').slice(0, 40), watches, hpBelow, turn,
    announce: String(op.announce || '').slice(0, 160), todo, fired: false});
}

function firePhase(battle, rules, phase) {
  phase.fired = true;   // first, so a throwing effect can never fire it twice
  if (phase.announce) note(battle, phase.announce);
  let applied = 0;
  for (const inner of phase.todo) {
    try {
      OPERATIONS[inner.op](battle, inner, rules);
      applied++;
    } catch (err) {
      note(battle, `A phase effect (${inner.op}) could not be applied: ${err && err.message}`);
    }
  }
  try {
    console.log(`[CobbleTowers] Phase ${phase.id} fired (${applied} of ${phase.todo.length} effect(s))`);
  } catch (err) {
    /* logging is never worth a battle */
  }
}

/** The lowest HP percentage among the watched sides' Pokemon still standing, or 100 if none is. */
function lowestHpPercent(battle, phase) {
  let lowest = 100;
  for (const pokemon of activesOf(battle.sides.filter(side => side && phase.watches.includes(side.id)))) {
    lowest = Math.min(lowest, pokemon.hp * 100 / pokemon.maxhp);
  }
  return lowest;
}

function checkPhases(battle, rules) {
  for (const phase of rules.phases) {
    if (phase.fired) continue;
    try {
      const due = (phase.turn && battle.turn >= phase.turn) || (phase.hpBelow && lowestHpPercent(battle, phase) < phase.hpBelow);
      if (due) firePhase(battle, rules, phase);
    } catch (err) {
      phase.fired = true;   // a phase that cannot even be checked is dropped, not retried every action
    }
  }
}

/** Watches the battle for the phases' triggers: after every action (HP) and after every turn change (turn number). */
function wrapPhases(battle, rules) {
  const run = battle.runAction;
  if (typeof run === 'function') {
    battle.runAction = function () {
      const result = run.apply(this, arguments);
      try {
        checkPhases(this, rules);
      } catch (err) {
        /* see checkPhases */
      }
      return result;
    };
  }
  const hook = typeof battle.endTurn === 'function' ? 'endTurn' : 'nextTurn';
  const turnHook = battle[hook];
  if (typeof turnHook === 'function') {
    battle[hook] = function () {
      const result = turnHook.apply(this, arguments);
      try {
        checkPhases(this, rules);
      } catch (err) {
        /* see checkPhases */
      }
      return result;
    };
  }
}

/**
 * Balance phase 2: a `cap` op bounds what a side's damage rules can add up to, in percent. Relics stack
 * multiplicatively, so without one three offense relics made a boss trivial; past the cap more relics change nothing.
 * A side without a cap op is unbounded, as before.
 */
function addCapRule(battle, op, rules) {
  for (const side of sidesOf(battle, op)) {
    rules.caps[side.id] = {dealt: clamp(op.dealt, 100, 300, 300), taken: clamp(op.taken, 1, 100, 1)};
  }
}

/** Records an enrage rule: every `every` turns the named sides' actives gain +1 Attack and Special Attack. */
function addEnrageRule(battle, op, rules) {
  const sides = sidesOf(battle, op).map(side => side.id);
  if (!sides.length) throw new Error('enrage needs a side');
  rules.enrage.push({sides, every: clamp(op.every, 1, 20, 4), tag: tagOf(op)});
}

/**
 * Wraps the turn hook on this battle only (endTurn in current Showdown, nextTurn in older builds); fails open, since a
 * missed enrage is a small bug and a throw hangs the battle.
 */
function wrapEnrage(battle, rules) {
  const hook = typeof battle.endTurn === 'function' ? 'endTurn' : 'nextTurn';
  const original = battle[hook];
  if (typeof original !== 'function') {
    note(battle, 'Tower enrage effects are not available in this simulator.');
    return;
  }
  battle[hook] = function () {
    const result = original.apply(this, arguments);
    try {
      const turn = this.turn;
      for (const rule of rules.enrage) {
        if (!(turn > 1) || (turn - 1) % rule.every !== 0) continue;
        for (const pokemon of activesOf(this.sides.filter(side => side && rule.sides.includes(side.id)))) {
          this.add('-message', `${pokemon.name} is enraged!`);
          this.boost({atk: 1, spa: 1}, pokemon, pokemon, null, false, true);
        }
      }
    } catch (err) {
      /* see above */
    }
    return result;
  };
}

function matches(rule, sideId, type) {
  return rule.sides.includes(sideId) && (rule.type === 'any' || rule.type === type);
}

/**
 * Scales damage for this battle only, on its own actions object. Fails open to the engine's number: a wrong
 * multiplier is a small bug, an exception in damage calculation hangs the battle.
 */
function wrapDamage(battle, rules) {
  const actions = battle.actions;
  const original = actions && actions.modifyDamage;
  if (typeof original !== 'function') {
    note(battle, 'Tower damage effects are not available in this simulator.');
    return;
  }
  actions.modifyDamage = function (baseDamage, pokemon, target, move) {
    const damage = original.apply(this, arguments);
    try {
      if (typeof damage !== 'number' || !(damage > 0) || !pokemon || !target) return damage;
      const type = move && move.type;
      // The product of every matching rule, in percent, then clamped (a side's cap op); one modify call, so
      // the rounding no longer depends on how many relics are held.
      let dealt = 100;
      for (const rule of rules.dealt) {
        if (matches(rule, pokemon.side.id, type)) dealt = dealt * rule.percent / 100;
      }
      let taken = 100;
      for (const rule of rules.taken) {
        if (matches(rule, target.side.id, type)) taken = taken * rule.percent / 100;
      }
      const attackerCap = rules.caps[pokemon.side.id];
      const targetCap = rules.caps[target.side.id];
      if (attackerCap) dealt = Math.min(dealt, attackerCap.dealt);
      if (targetCap) taken = Math.max(taken, targetCap.taken);
      const percent = Math.round(dealt * taken / 100);
      if (percent === 100) return damage;
      return Math.max(1, battle.modify(damage, percent, 100));
    } catch (err) {
      return damage;
    }
  };
}

/**
 * Heals the attacker by a share of the damage its move dealt, for the sides a drain rule names. Wraps spreadDamage on
 * this battle only and fails open: whatever goes wrong, the damage is the engine's own number.
 */
function wrapDrain(battle, rules) {
  const original = battle.spreadDamage;
  if (typeof original !== 'function') {
    note(battle, 'Tower drain effects are not available in this simulator.');
    return;
  }
  battle.spreadDamage = function (damage, targetArray, source, effect) {
    const result = original.apply(this, arguments);
    try {
      if (!source || !source.side || !Array.isArray(result) || !effect || effect.effectType !== 'Move') return result;
      let percent = 0;
      for (const rule of rules.drain) if (rule.sides.includes(source.side.id)) percent += rule.percent;
      if (percent <= 0) return result;
      let dealt = 0;
      for (const value of result) if (typeof value === 'number' && value > 0) dealt += value;
      const heal = Math.floor(dealt * Math.min(percent, 100) / 100);
      if (heal > 0 && source.hp > 0 && source.hp < source.maxhp) battle.heal(heal, source, source, 'drain');
    } catch (err) {
      /* a missed heal is a small bug; an exception here would hang the battle */
    }
    return result;
  };
}

/** Applies every operation in battle.format.towerFx once. Exported for the tests. */
function applyTowerFx(battle) {
  const fx = battle.format && battle.format.towerFx;
  if (!Array.isArray(fx) || battle.towerFxApplied) return;
  battle.towerFxApplied = true;
  if (fx.length > MAX_OPS) note(battle, `Only the first ${MAX_OPS} tower effects were applied.`);

  const rules = {dealt: [], taken: [], drain: [], enrage: [], caps: {}, phases: []};
  let applied = 0;
  for (const op of fx.slice(0, MAX_OPS)) {
    const name = op && typeof op === 'object' ? op.op : undefined;
    const handler = typeof name === 'string' && Object.prototype.hasOwnProperty.call(OPERATIONS, name) ? OPERATIONS[name] : null;
    if (!handler) continue;   // an operation this version does not know is ignored, never guessed at (pre-start ones already ran)
    try {
      handler(battle, op, rules);
      applied++;
    } catch (err) {
      note(battle, `A tower effect (${name}) could not be applied: ${err && err.message}`);
    }
  }
  // A phase can add rules later, so with any phase present every wrapper is installed now (each is cheap and fails open).
  const phased = rules.phases.length > 0;
  if (phased || rules.dealt.length || rules.taken.length) wrapDamage(battle, rules);
  if (phased || rules.drain.length) wrapDrain(battle, rules);
  if (phased || rules.enrage.length) wrapEnrage(battle, rules);
  if (phased) wrapPhases(battle, rules);
  applied += battle.towerFxPreApplied || 0;
  if (applied > 0) note(battle, 'Tower effects are in play.');
  // To the server log, not just the battle log: the Java side logs what it HANDED to Showdown, and only this line
  // proves the JavaScript received it and applied it (it did not, for a whole phase, and nothing said so).
  try {
    console.log(`[CobbleTowers] Applied ${applied} of ${Math.min(fx.length, MAX_OPS)} tower effect(s) to a battle.`);
  } catch (err) {
    /* logging is never worth a battle */
  }
}

function install(sim) {
  const Battle = sim.Battle;
  const oldStart = Battle.prototype.start;
  Battle.prototype.start = function () {
    // Over-the-cap EVs go in before anything is announced; a battle that has started or been restored is left alone.
    if (!this.started && !this.deserialized) {
      try {
        applyPreStart(this);
      } catch (err) {
        note(this, 'Tower effects could not be prepared.');
      }
    }
    const result = oldStart.apply(this, arguments);
    if (this.deserialized) return result;   // a restored battle must not re-apply what already happened
    try {
      applyTowerFx(this);
    } catch (err) {
      note(this, 'Tower effects could not be applied.');
    }
    return result;
  };
}

module.exports = {install, applyTowerFx, applyPreStart, MAX_OPS};

// Loaded by Showdown (through CobbleRaids' extension loader): install against the simulator beside this file. The
// tests set the flag and call install() themselves against a simulator they loaded.
if (!globalThis.__TOWER_FX_TEST__) install({Battle: require('./sim/battle').Battle});
