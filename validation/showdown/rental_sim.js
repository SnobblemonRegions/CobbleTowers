#!/usr/bin/env node
// Tuning aid for the Rental Draft (P33): is any set a trap or a guaranteed win? Run `node
// validation/showdown/rental_sim.js --showdown-dir <rig>/showdown`. Plays real Showdown battles: a sampled team of
// six against a row of wild enemies at the rentals' level. A relative yardstick between sets; the greedy player
// under-rates support sets.

const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : fallback;
};
const SHOWDOWN = path.resolve(opt('showdown-dir', process.env.SHOWDOWN_DIR || 'showdown'));
const SETS = path.resolve(__dirname, '../../src/main/resources/data/cobbletowers/cobbletowers/rental_sets');
// Species the sims fight against. Tooling data only: the mod has no ordinary-opponent pools (every floor is a boss).
const POOLS = path.resolve(__dirname, 'sim_opponents');
const TEAMS = Number(opt('teams', 800));
const OPPONENTS = Number(opt('opponents', 3));
const POOL = opt('pool', 'neutral_common');
const LEVEL = Number(opt('level', 50));
const SEED = Number(opt('seed', 7));
const ENEMY_EVS = Number(opt('enemy-evs', 0));   // over-the-cap EVs per stat for the enemy: a stand-in for how deep the floor is
const FX_FILE = path.resolve(__dirname, '../../src/main/resources/assets/cobbletowers/showdown/tower-fx.js');

globalThis.__TOWER_FX_TEST__ = true;
const BS = require(path.join(SHOWDOWN, 'sim/battle-stream'));
const {Dex} = require(path.join(SHOWDOWN, 'sim/dex'));
const {Battle} = require(path.join(SHOWDOWN, 'sim/battle'));
require(FX_FILE).install({Battle});

const RANKS = ['common', 'uncommon', 'rare', 'epic', 'legendary', 'mythic'];
const sets = fs.readdirSync(SETS).filter(f => f.endsWith('.json')).sort()
  .map(f => JSON.parse(fs.readFileSync(path.join(SETS, f), 'utf8')));
const byRarity = r => sets.filter(s => s.rarity === r);

/**
 * Cobblemon's packed set:
 * name|species|uuid|hp|status|statusDuration|item|ability|moves|pp|nature|evs|gender|ivs|?|level|
 */
function pack(m, n) {
  const item = m.item ? m.item.replace('cobblemon:', '').replace(/_/g, '') : 'none';
  return [m.species, m.species, `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`, '', '', '', item, m.ability,
    m.moves.join(','), m.moves.map(() => '32/32').join(','), m.nature, m.evs.join(','), '', (m.ivs || [31, 31, 31, 31, 31, 31]).join(','), '',
    String(m.level || LEVEL), ''].join('|');
}

const poolEntries = JSON.parse(fs.readFileSync(path.join(POOLS, POOL + '.json'), 'utf8')).entries;

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
    nature: 'Hardy', moves, evs: [0, 0, 0, 0, 0, 0], ivs: [15, 15, 15, 15, 15, 15], level};
}

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

// ---- drafts
// --------------------------------------------------------------------------------------------------------

function sample(list, rng, used) {
  const free = list.filter(s => !used.has(s.species));
  const from = free.length ? free : list;
  const pick = from[Math.floor(rng() * from.length)];
  used.add(pick.species);
  return pick;
}

/** One player's draft, simplified from RentalDraw: returns six sets. */
function draft(rng, strategy) {
  const used = new Set();
  const packs = [];
  for (let p = 0; p < 3; p++) {
    const cards = [];
    for (let i = 0; i < 3; i++) cards.push(sample(byRarity('common'), rng, used));
    cards.push(sample(byRarity('uncommon'), rng, used));
    const roll = rng() * 100;
    cards.push(sample(roll < 70 ? byRarity('rare') : roll < 92 ? byRarity('epic') : byRarity('legendary'), rng, used));
    packs.push(cards);
  }
  if (!packs.flat().some(s => RANKS.indexOf(s.rarity) >= 3)) packs[Math.floor(rng() * 3)][4] = sample(byRarity('epic'), rng, used);
  const team = [];
  for (const cards of packs) {
    let order = cards.map((c, i) => i);
    if (strategy === 'best') order.sort((a, b) => RANKS.indexOf(cards[b].rarity) - RANKS.indexOf(cards[a].rarity));
    else if (strategy === 'worst') order.sort((a, b) => RANKS.indexOf(cards[a].rarity) - RANKS.indexOf(cards[b].rarity));
    else order.sort(() => rng() - 0.5);
    for (const i of order) {
      if (team.length >= 6 || team.filter((_, n) => Math.floor(n / 2) === packs.indexOf(cards)).length >= 2) continue;
      const top = [...team, cards[i]].filter(s => RANKS.indexOf(s.rarity) >= 4).length;
      if (top > 2) continue;
      team.push(cards[i]);
    }
  }
  return team;
}

// ---- play
// ----------------------------------------------------------------------------------------------------------

function effectiveness(moveType, targetTypes) {
  let mod = 1;
  for (const t of targetTypes) {
    const e = Dex.types.get(moveType).damageTaken[t];
    mod *= e === 1 ? 2 : e === 2 ? 0.5 : e === 3 ? 0 : 1;
  }
  return mod;
}

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

async function fight(team, seed) {
  const rng = mulberry(seed);
  const enemies = [];
  for (let i = 0; i < OPPONENTS; i++) enemies.push(enemyOf(poolEntries[Math.floor(rng() * poolEntries.length)], LEVEL));
  const stream = new BS.BattleStream();
  const streams = BS.getPlayerStreams(stream);
  const format = {mod: 'gen9', gameType: 'singles', gen: 9, ruleset: [], effectType: 'Format'};
  if (ENEMY_EVS > 0) {
    format.towerFx = [];
    for (let left = ENEMY_EVS; left > 0; left -= 2000) format.towerFx.push({op: 'evs', sides: ['p2'], amount: Math.min(left, 2000)});
  }
  let enemyTypes = [];
  let done;
  const finished = new Promise(resolve => { done = resolve; });
  const decide = async (s, who) => {
    for await (const chunk of s) {
      for (const line of String(chunk).split('\n')) {
        if (line.startsWith('|request|')) {
          const request = JSON.parse(line.slice(9));
          if (request.wait) continue;
          if (who === 'p1') {
            const foe = stream.battle.sides[1].active[0];
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
  decide(streams.p1, 'p1');
  decide(streams.p2, 'p2');
  streams.omniscient.write('>start ' + JSON.stringify({format, seed: [seed & 0xffff, 2, 3, 4]}));
  streams.omniscient.write('>player p1 ' + JSON.stringify({name: 'Player', team: team.map((m, n) => pack(m, n + 1)).join(']')}));
  streams.omniscient.write('>player p2 ' + JSON.stringify({name: 'Foe', team: enemies.map((m, n) => pack(m, n + 101)).join(']')}));
  const result = await Promise.race([finished, new Promise(resolve => setTimeout(() => resolve('timeout'), 20000))]);
  const mine = stream.battle.sides[0].pokemon;
  const won = result === '|win|Player';
  const left = mine.reduce((s, p) => s + p.hp, 0) / mine.reduce((s, p) => s + p.maxhp, 0);
  return {won, left: won ? left : 0, timeout: result === 'timeout'};
}

// ---- the report
// ----------------------------------------------------------------------------------------------------

const pct = (n, d) => d ? (100 * n / d).toFixed(0) + '%' : '-';

(async () => {
  const rng = mulberry(SEED);
  const perSet = new Map(sets.map(s => [s.species, {wins: 0, games: 0}]));
  const byTops = {0: {w: 0, n: 0}, 1: {w: 0, n: 0}, 2: {w: 0, n: 0}};
  let wins = 0, games = 0, timeouts = 0, hp = 0;
  const strategies = {random: {w: 0, n: 0}, best: {w: 0, n: 0}, worst: {w: 0, n: 0}};
  for (let t = 0; t < TEAMS; t++) {
    const strategy = t % 4 === 0 ? 'best' : t % 4 === 1 ? 'worst' : 'random';
    const team = draft(rng, strategy);
    if (team.length !== 6) continue;
    const r = await fight(team, 5000 + t);
    games++;
    if (r.won) { wins++; hp += r.left; }
    if (r.timeout) timeouts++;
    const tops = team.filter(s => RANKS.indexOf(s.rarity) >= 4).length;
    byTops[tops].n++;
    if (r.won) byTops[tops].w++;
    strategies[strategy].n++;
    if (r.won) strategies[strategy].w++;
    for (const s of team) {
      const cell = perSet.get(s.species);
      cell.games++;
      if (r.won) cell.wins++;
    }
  }
  console.log(`${games} drafted teams vs ${OPPONENTS} level-${LEVEL} opponents from ${POOL}: ${pct(wins, games)} won, ${wins ? (100 * hp / wins).toFixed(0) : '-'}% health left on a win${timeouts ? ', ' + timeouts + ' timed out' : ''}`);
  console.log(`  how the picks were made: random ${pct(strategies.random.w, strategies.random.n)}, always the rarest ${pct(strategies.best.w, strategies.best.n)}, always the least rare ${pct(strategies.worst.w, strategies.worst.n)}`);
  console.log(`  by legendaries on the team: 0 -> ${pct(byTops[0].w, byTops[0].n)} (${byTops[0].n}), 1 -> ${pct(byTops[1].w, byTops[1].n)} (${byTops[1].n}), 2 -> ${pct(byTops[2].w, byTops[2].n)} (${byTops[2].n})`);
  const overall = wins / Math.max(1, games);
  console.log('\nset win rate when on a team, against the overall rate (' + pct(wins, games) + '):');
  const rows = [...perSet.entries()].map(([species, c]) => ({species, rarity: sets.find(s => s.species === species).rarity, rate: c.games ? c.wins / c.games : 0, games: c.games}))
    .sort((a, b) => b.rate - a.rate);
  for (const row of rows) {
    const flag = row.games < 30 ? ' (few games)' : row.rate - overall > 0.15 ? '  <-- strong' : overall - row.rate > 0.15 ? '  <-- weak' : '';
    console.log(`  ${row.species.padEnd(12)} ${row.rarity.padEnd(10)} ${(100 * row.rate).toFixed(0).padStart(3)}%  (${row.games})${flag}`);
  }
  process.exit(0);
})().catch(err => { console.error(err); process.exit(1); });
