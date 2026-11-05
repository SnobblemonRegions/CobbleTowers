#!/usr/bin/env node
// Balance phase 2: checks tower-fx.js's `cap` and `enrage` ops against the upstream pokemon-showdown package (npm),
// for when no Cobblemon rig is at hand. Run from a folder where `pokemon-showdown` is installed:
//   node <repo>/validation/showdown/balance_upstream_test.js
const assert = require('assert');
const path = require('path');
globalThis.__TOWER_FX_TEST__ = true;
const sim = require(require.resolve('pokemon-showdown/dist/sim', {paths: [process.cwd()]}));
const fx = require(path.resolve(__dirname, '../../src/main/resources/assets/cobbletowers/showdown/tower-fx.js'));

function run(towerFx, turns = 1) {
  const team = s => sim.Teams.pack([{species: s, name: s, ability: 'Thick Fat', moves: ['tackle'], level: 50,
    evs: {hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0}, ivs: {hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31}, nature: 'Hardy', item: ''}]);
  const battle = new sim.Battle({formatid: 'gen9customgame', seed: [1, 2, 3, 4]});
  battle.format.towerFx = towerFx;
  fx.install({Battle: Object.getPrototypeOf(battle).constructor});
  battle.setPlayer('p1', {name: 'A', team: team('Snorlax')});
  battle.setPlayer('p2', {name: 'B', team: team('Snorlax')});
  battle.choose('p1', 'team 1'); battle.choose('p2', 'team 1');
  let lost = 0;
  for (let t = 0; t < turns; t++) {
    const before = battle.sides[1].active[0].hp;
    battle.choose('p1', 'move 1'); battle.choose('p2', 'move 1');
    if (t === 0) lost = before - battle.sides[1].active[0].hp;
  }
  return {battle, lost};
}

const base = run([]).lost;
const two = [{op: 'damage', sides: ['p1'], type: 'any', percent: 150}, {op: 'damage', sides: ['p1'], type: 'any', percent: 150}];
const uncapped = run(two).lost;
const capped = run([...two, {op: 'cap', sides: ['p1'], dealt: 135, taken: 80}]).lost;
const floored = run([{op: 'resist', sides: ['p2'], type: 'any', percent: 50}, {op: 'cap', sides: ['p2'], dealt: 135, taken: 80}]).lost;
console.log({base, uncapped, capped, floored});
assert(Math.abs(uncapped / base - 2.25) < 0.1, 'without a cap two x1.5 relics stack to x2.25');
assert(Math.abs(capped / base - 1.35) < 0.06, 'the cap holds them to x1.35');
assert(Math.abs(floored / base - 0.8) < 0.06, 'x0.5 taken is floored at x0.8');
const enraged = run([{op: 'enrage', sides: ['p2'], every: 2}], 4).battle;
console.log({foeAtk: enraged.sides[1].active[0].boosts.atk, p1Atk: enraged.sides[0].active[0].boosts.atk});
assert.strictEqual(enraged.sides[1].active[0].boosts.atk, 2, 'enraged twice in four turns');
assert.strictEqual(enraged.sides[1].active[0].boosts.spa, 2);
assert.strictEqual(enraged.sides[0].active[0].boosts.atk, 0);
assert(enraged.log.some(l => /is enraged!/.test(l)));
console.log('balance fx tests passed');
