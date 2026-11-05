#!/usr/bin/env node
// Tuning aid for Ascension (P30): how hard is one floor at each Ascension for a given party? Run `node
// validation/showdown/ascension_sim.js --showdown-dir <rig>/showdown`. Plays real Showdown battles with the real
// tower-fx.js; a floor is one battle against 1 + extra enemies. A relative yardstick, not a prediction (no bosses,
// modifiers, items or healing). Constants mirror ascension/AscensionPolicy.java; override with --ev-per and --boon.

const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : fallback;
};
const SHOWDOWN = path.resolve(opt('showdown-dir', process.env.SHOWDOWN_DIR || 'showdown'));
const FX_FILE = path.resolve(__dirname, '../../src/main/resources/assets/cobbletowers/showdown/tower-fx.js');
// Species the sims fight against. Tooling data only: the mod has no ordinary-opponent pools (every floor is a boss).
const POOLS = path.resolve(__dirname, 'sim_opponents');
const TRIALS = Number(opt('trials', 120));
const MAX_A = Number(opt('max-ascension', 30));
const STEP = Number(opt('step', 2));
const LEVEL = Number(opt('level', 100));
const EV_PER = Number(opt('ev-per', 50));            // enemy EVs per stat per Ascension
const BOON_PERCENT = Number(opt('boon', 50));        // the player's matching boon, as a percentage of the enemy's
const EXTRA_EVERY = Number(opt('extra-every', 3));   // one extra opponent per this many Ascensions
const EXTRA_MAX = Number(opt('extra-max', 3));
const BASE_OPPONENTS = Number(opt('opponents', 1));  // what a floor has before Ascension
const POOL = opt('pool', 'neutral_common');
const TIERS = opt('tiers', 'casual,mid,trained').split(',');

globalThis.__TOWER_FX_TEST__ = true;
const BS = require(path.join(SHOWDOWN, 'sim/battle-stream'));
const {Battle} = require(path.join(SHOWDOWN, 'sim/battle'));
const {Dex} = require(path.join(SHOWDOWN, 'sim/dex'));
require(FX_FILE).install({Battle});

// ---- teams
// ---------------------------------------------------------------------------------------------------------

/**
 * Cobblemon's packed set:
 * name|species|uuid|hp|status|statusDuration|item|ability|moves|pp|nature|evs|gender|ivs|?|level|
 */
function pack(m, n) {
  const evs = m.evs.join(',');
  return [m.species, m.species, `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`, '', '', '', 'none', m.ability,
    m.moves.join(','), m.moves.map(() => '32/32').join(','), m.nature, evs, '', m.ivs ? m.ivs.join(',') : '', '',
    String(m.level), ''].join('|');
}

const PLAYER_SPECIES = [
  {species: 'Garchomp', ability: 'roughskin', nature: 'Jolly', moves: ['earthquake', 'outrage', 'stoneedge', 'firefang'], offense: 'atk'},
  {species: 'Dragapult', ability: 'infiltrator', nature: 'Timid', moves: ['shadowball', 'dracometeor', 'flamethrower', 'thunderbolt'], offense: 'spa'},
  {species: 'Kingambit', ability: 'defiant', nature: 'Adamant', moves: ['suckerpunch', 'ironhead', 'kowtowcleave', 'lowkick'], offense: 'atk'},
  {species: 'Gholdengo', ability: 'goodasgold', nature: 'Timid', moves: ['makeitrain', 'shadowball', 'focusblast', 'thunderbolt'], offense: 'spa'},
  {species: 'Landorus-Therian', ability: 'intimidate', nature: 'Adamant', moves: ['earthquake', 'stoneedge', 'uturn', 'knockoff'], offense: 'atk'},
  {species: 'Volcarona', ability: 'flamebody', nature: 'Timid', moves: ['bugbuzz', 'fireblast', 'gigadrain', 'psychic'], offense: 'spa'},
];

/** A party at a training tier: casual = no EVs, mid = 85 in every stat, trained = 252 / 252 / 4. */
function playerTeam(tier) {
  return PLAYER_SPECIES.map(base => {
    let evs = [0, 0, 0, 0, 0, 0];
    if (tier === 'mid') evs = [85, 85, 85, 85, 85, 85];
    if (tier === 'trained') {
      evs = [4, 0, 0, 0, 0, 252];                       // hp atk def spa spd spe
      evs[base.offense === 'atk' ? 1 : 3] = 252;
    }
    return Object.assign({}, base, {evs, level: LEVEL, ivs: [31, 31, 31, 31, 31, 31]});
  });
}

const poolEntries = JSON.parse(fs.readFileSync(path.join(POOLS, POOL + '.json'), 'utf8')).entries;

/** What Cobblemon gives a wild Pokemon: the last four level-up moves it has learned by its level. */
function enemyOf(entry, level) {
  const id = entry.species.split(':')[1];
  const species = Dex.species.get(id);
  const learnset = (Dex.data.Learnsets[species.id] || {}).learnset || {};
  const known = [];
  for (const [move, sources] of Object.entries(learnset)) {
    for (const source of sources) {
      const found = /^9L(\d+)$/.exec(source) || /^8L(\d+)$/.exec(source);
      if (found && Number(found[1]) <= level) { known.push({move, level: Number(found[1])}); break; }
    }
  }
  known.sort((a, b) => a.level - b.level);
  const moves = [...new Set(known.map(k => k.move))].slice(-4);
  return {species: species.name, ability: Object.keys(species.abilities).map(k => species.abilities[k])[0].toLowerCase().replace(/[^a-z0-9]/g, ''),
    nature: 'Hardy', moves, evs: [0, 0, 0, 0, 0, 0], ivs: [15, 15, 15, 15, 15, 15], level: Math.min(100, level + (entry.level_offset || 0))};
}

// ---- play
// ----------------------------------------------------------------------------------------------------------

function hpOf(condition) {
  const [hp, max] = String(condition).split(' ')[0].split('/').map(Number);
  return {hp: hp || 0, max: max || 1};
}

function effectiveness(moveType, targetTypes) {
  let mod = 1;
  for (const t of targetTypes) {
    const e = Dex.types.get(moveType).damageTaken[t];
    mod *= e === 1 ? 2 : e === 2 ? 0.5 : e === 3 ? 0 : 1;
  }
  return mod;
}

/**
 * The player's choice: forced switch to the healthy Pokemon with the best matchup, else the highest expected-damage
 * move.
 */
function playerChoice(request, enemyTypes) {
  const side = request.side.pokemon;
  const scoreMon = (mon) => {
    const species = Dex.species.get(mon.details.split(',')[0]);
    let best = 0;
    for (const id of mon.moves) {
      const move = Dex.moves.get(id);
      if (!move.basePower) continue;
      const stab = species.types.includes(move.type) ? 1.5 : 1;
      const power = (move.category === 'Physical' ? mon.stats.atk : mon.stats.spa) * move.basePower * stab
        * effectiveness(move.type, enemyTypes) * (move.accuracy === true ? 1 : move.accuracy / 100);
      best = Math.max(best, power);
    }
    return best;
  };
  if (request.forceSwitch) {
    let pick = -1, bestScore = -1;
    side.forEach((mon, i) => {
      if (mon.active || mon.condition.endsWith(' fnt')) return;
      const score = scoreMon(mon);
      if (score > bestScore) { bestScore = score; pick = i; }
    });
    return pick < 0 ? 'pass' : `switch ${pick + 1}`;
  }
  const active = side.find(mon => mon.active);
  const species = Dex.species.get(active.details.split(',')[0]);
  let pick = 1, bestScore = -1;
  request.active[0].moves.forEach((entry, i) => {
    if (entry.disabled) return;
    const move = Dex.moves.get(entry.id);
    let score = 1;
    if (move.basePower) {
      const stab = species.types.includes(move.type) ? 1.5 : 1;
      score = (move.category === 'Physical' ? active.stats.atk : active.stats.spa) * move.basePower * stab
        * effectiveness(move.type, enemyTypes) * (move.accuracy === true ? 1 : move.accuracy / 100);
      if (move.priority > 0) score *= 1.1;
    }
    if (score > bestScore) { bestScore = score; pick = i + 1; }
  });
  return `move ${pick}`;
}

function enemyChoice(request, rng) {
  if (request.forceSwitch) {
    const i = request.side.pokemon.findIndex(mon => !mon.active && !mon.condition.endsWith(' fnt'));
    return i < 0 ? 'pass' : `switch ${i + 1}`;
  }
  const usable = [];
  request.active[0].moves.forEach((entry, i) => { if (!entry.disabled) usable.push(i + 1); });
  return `move ${usable.length ? usable[Math.floor(rng() * usable.length)] : 1}`;
}

/** Small deterministic generator, so a run is repeatable. */
function mulberry(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

async function floor(tier, ascension, seed) {
  const rng = mulberry(seed);
  const extra = Math.min(EXTRA_MAX, Math.floor(ascension / EXTRA_EVERY));
  const count = BASE_OPPONENTS + extra;
  const enemies = [];
  for (let i = 0; i < count; i++) {
    const entry = poolEntries[Math.floor(rng() * poolEntries.length)];
    enemies.push(enemyOf(entry, LEVEL));
  }
  const enemyEvs = Math.min(4000, EV_PER * ascension);   // sent as operations of at most 2000, as AscensionFx does
  const boonEvs = Math.floor(enemyEvs * BOON_PERCENT / 100);
  const fx = [];
  const chunks = (side, total) => { for (let left = total; left > 0; left -= 2000) fx.push({op: 'evs', sides: [side], amount: Math.min(left, 2000)}); };
  chunks('p2', enemyEvs);
  chunks('p1', boonEvs);

  const stream = new BS.BattleStream();
  const streams = BS.getPlayerStreams(stream);
  const format = {mod: 'gen9', gameType: 'singles', gen: 9, ruleset: [], effectType: 'Format'};
  if (fx.length) format.towerFx = fx;
  const team = playerTeam(tier);
  let enemyTypes = [];
  let done;
  const finished = new Promise(resolve => { done = resolve; });
  const decide = async (stream, who) => {
    for await (const chunk of stream) {
      for (const line of String(chunk).split('\n')) {
        if (line.startsWith('|request|')) {
          const request = JSON.parse(line.slice(9));
          if (request.wait) continue;
          if (who === 'p1') {
            const foe = stream_battle().sides[1].active[0];
            enemyTypes = foe ? foe.getTypes() : enemyTypes;
            streams.p1.write(playerChoice(request, enemyTypes));
          } else {
            streams.p2.write(enemyChoice(request, rng));
          }
        } else if (line.startsWith('|win|') || line === '|tie') {
          done(line);
        }
      }
    }
  };
  const stream_battle = () => stream.battle;
  decide(streams.p1, 'p1');
  decide(streams.p2, 'p2');
  streams.omniscient.write('>start ' + JSON.stringify({format, seed: [seed & 0xffff, 2, 3, 4]}));
  streams.omniscient.write('>player p1 ' + JSON.stringify({name: 'Player', team: team.map((m, n) => pack(m, n + 1)).join(']')}));
  streams.omniscient.write('>player p2 ' + JSON.stringify({name: 'Foe', team: enemies.map((m, n) => pack(m, n + 101)).join(']')}));
  const result = await Promise.race([finished, new Promise(resolve => setTimeout(() => resolve('timeout'), 20000))]);
  const battle = stream.battle;
  const mine = battle.sides[0].pokemon;
  const left = mine.reduce((s, p) => s + p.hp, 0) / mine.reduce((s, p) => s + p.maxhp, 0);
  const won = result === '|win|Player';
  return {won, left: won ? left : 0, turns: battle.turn, timeout: result === 'timeout'};
}

(async () => {
  const levels = [];
  for (let a = 0; a <= MAX_A; a += STEP) levels.push(a);
  console.log(`pool ${POOL}, party level ${LEVEL}, ${TRIALS} floors per cell, enemy +${EV_PER} EVs/stat per Ascension, boon ${BOON_PERCENT}%`);
  console.log('Ascension  opponents  ' + TIERS.map(t => (t + ' win%/hp%').padEnd(18)).join(''));
  for (const a of levels) {
    const cells = [];
    for (const tier of TIERS) {
      let wins = 0, hp = 0, timeouts = 0;
      for (let t = 0; t < TRIALS; t++) {
        const r = await floor(tier, a, 1000 * a + t + 1);
        if (r.won) { wins++; hp += r.left; }
        if (r.timeout) timeouts++;
      }
      cells.push(`${(100 * wins / TRIALS).toFixed(0)}% / ${wins ? (100 * hp / wins).toFixed(0) : '-'}%${timeouts ? '!' + timeouts : ''}`.padEnd(18));
    }
    console.log(String(a).padEnd(11) + String(BASE_OPPONENTS + Math.min(EXTRA_MAX, Math.floor(a / EXTRA_EVERY))).padEnd(11) + cells.join(''));
  }
  process.exit(0);
})().catch(err => { console.error(err); process.exit(1); });
