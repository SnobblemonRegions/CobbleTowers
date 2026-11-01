#!/usr/bin/env node
// Tests tower-fx.js (P23) against the real Showdown simulator, no Minecraft server: node
// validation/showdown/tower_fx_test.js --showdown-dir <rig>/showdown. Asserts what the simulator did, not what the
// patch asked for. Not in ci_local.sh; run after touching tower-fx.js or upgrading Cobblemon.

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {spawnSync} = require('child_process');

const args = process.argv.slice(2);
const dirArg = args.indexOf('--showdown-dir');
const raidArg = args.indexOf('--raid-patch');
const SHOWDOWN = path.resolve(dirArg >= 0 ? args[dirArg + 1] : (process.env.SHOWDOWN_DIR || 'showdown'));
// The CobbleRaids raid-patch.js to test the extension loader against. The one in a server's showdown/ directory is
// only
// replaced when the server next starts, so after changing the loader pass the source copy from the CobbleRaids repo.
const RAID_PATCH = raidArg >= 0 ? path.resolve(args[raidArg + 1]) : path.join(SHOWDOWN, 'raid-patch.js');
const FX_FILE = path.resolve(__dirname, '../../src/main/resources/assets/cobbletowers/showdown/tower-fx.js');
if (!fs.existsSync(path.join(SHOWDOWN, 'sim', 'battle.js'))) {
  console.error('no Showdown simulator at ' + SHOWDOWN + ' (pass --showdown-dir)');
  process.exit(2);
}

globalThis.__TOWER_FX_TEST__ = true;
const BS = require(path.join(SHOWDOWN, 'sim/battle-stream'));
const {Battle} = require(path.join(SHOWDOWN, 'sim/battle'));
const fx = require(FX_FILE);

// ---- driving a battle ----------------------------------------------------------------------------------------

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Cobblemon's packed team: name|species|uuid|currentHealth|status|statusDuration|item|ability|moves|pp|nature|... */
function pack(species, ability, moves, level, item = 'none') {
  return [species, species, '00000000-0000-0000-0000-00000000000' + species.length, '', '', '', item, ability,
    moves.join(','), moves.map(() => '16/16').join(','), 'Hardy', '', '', '', '', String(level), ''].join('|');
}

const BLASTOISE = (item) => pack('Blastoise', 'torrent', ['hydropump', 'icebeam', 'surf', 'earthquake'], 50, item);
const CHARIZARD = (item) => pack('Charizard', 'blaze', ['flamethrower', 'airslash', 'dragonclaw', 'roost'], 50, item);

/**
 * Runs a battle to the end of turn one, with `towerFx` on the format object where CobbleRaids' provider puts it.
 * Returns the log, the stream's battle and anything the stream threw.
 */
async function battle({towerFx, moves = ['move 2', 'move 4'], extraFormat = {}, turns = 1, items = ['none', 'none']} = {}) {
  const stream = new BS.BattleStream();
  const lines = [];
  const errors = [];
  (async () => {
    try {
      for await (const chunk of stream) lines.push(...String(chunk).split('\n'));
    } catch (err) {
      errors.push(err);
    }
  })();
  const format = Object.assign({mod: 'gen9', gameType: 'singles', gen: 9, ruleset: [], effectType: 'Format'}, extraFormat);
  if (towerFx !== undefined) format.towerFx = towerFx;
  stream.write('>start ' + JSON.stringify({format, seed: [1, 2, 3, 4]}));
  stream.write('>player p1 ' + JSON.stringify({name: 'A', team: BLASTOISE(items[0])}));
  stream.write('>player p2 ' + JSON.stringify({name: 'B', team: CHARIZARD(items[1])}));
  await sleep(250);
  for (let turn = 0; turn < turns && !stream.battle.ended; turn++) {
    stream.write('>p1 ' + moves[0]);
    stream.write('>p2 ' + moves[1]);
    await sleep(250);
  }
  return {lines, stream, battle: stream.battle, errors};
}

const hp = (b, side) => b.battle.sides[side].active[0].hp;
const maxhp = (b, side) => b.battle.sides[side].active[0].maxhp;
const stage = (b, side, stat) => b.battle.sides[side].active[0].boosts[stat];
const started = b => b.lines.some(line => line.startsWith('|switch|'));
const said = (b, pattern) => b.lines.some(line => pattern.test(line));

// ---- tests ---------------------------------------------------------------------------------------------------

const tests = [];
const test = (name, fn) => tests.push({name, fn});

let baseline;   // computed BEFORE the patch is installed, so "identical when absent" compares against the real engine

test('a battle with no towerFx is identical to one run with the patch absent (same seed, same log)', async () => {
  const b = await battle({});
  const strip = log => log.filter(line => !line.startsWith('|t:') && !line.startsWith('|request|'));
  assert.deepStrictEqual(strip(b.lines), strip(baseline.lines));
  assert.strictEqual(hp(b, 1), hp(baseline, 1));
  assert.strictEqual(hp(b, 0), hp(baseline, 0));
});

test('weather: sets what the field actually holds, with no expiry by default', async () => {
  const b = await battle({towerFx: [{op: 'weather', id: 'raindance', sides: ['p1']}]});
  assert.strictEqual(b.battle.field.weather, 'raindance');
  assert.strictEqual(b.battle.field.weatherState.duration, 0);
  assert.ok(said(b, /\|-weather\|RainDance/), 'the log shows the weather');
});

test('weather: a duration is honoured and clamped', async () => {
  const short = await battle({towerFx: [{op: 'weather', id: 'sunnyday', duration: 3}], turns: 0});
  assert.strictEqual(short.battle.field.weatherState.duration, 3);
  const huge = await battle({towerFx: [{op: 'weather', id: 'sunnyday', duration: 9999}], turns: 0});
  assert.strictEqual(huge.battle.field.weatherState.duration, 20);
});

test('terrain: sets the terrain the field holds', async () => {
  const b = await battle({towerFx: [{op: 'terrain', id: 'electricterrain', sides: ['p1']}]});
  assert.strictEqual(b.battle.field.terrain, 'electricterrain');
});

test('boost: raises a stat stage on the named side only', async () => {
  const b = await battle({towerFx: [{op: 'boost', sides: ['p1'], stat: 'atk', stages: 2}]});
  assert.strictEqual(stage(b, 0, 'atk'), 2);
  assert.strictEqual(stage(b, 1, 'atk'), 0);
  assert.ok(said(b, /\|-boost\|.*\|atk\|2/));
});

test('boost: stages are clamped to +-6 and a zero or non-number does nothing', async () => {
  const high = await battle({towerFx: [{op: 'boost', sides: ['p1'], stat: 'spe', stages: 99}]});
  assert.strictEqual(stage(high, 0, 'spe'), 6);
  const none = await battle({towerFx: [{op: 'boost', sides: ['p1'], stat: 'spe', stages: 'lots'}]});
  assert.strictEqual(stage(none, 0, 'spe'), 0);
});

// P30: over-the-cap EVs. The expected stat is Showdown's own formula, computed here from the species' base stats.
const statOf = (base, ev, level = 50) => Math.trunc(Math.trunc(2 * base + 31 + Math.trunc(ev / 4)) * level / 100 + 5);
const hpStatOf = (base, ev, level = 50) => Math.trunc(Math.trunc(2 * base + 31 + Math.trunc(ev / 4) + 100) * level / 100 + 10);

test('evs: raises every stat of the named side beyond the 255 cap, and the other side is untouched', async () => {
  const b = await battle({towerFx: [{op: 'evs', sides: ['p2'], amount: 1000}], turns: 0});
  const foe = b.battle.sides[1].active[0];
  const mine = b.battle.sides[0].active[0];
  assert.strictEqual(foe.set.evs.atk, 1000, 'the raised value is held, not clamped to 255');
  assert.strictEqual(foe.storedStats.atk, statOf(84, 1000));
  assert.strictEqual(foe.storedStats.spe, statOf(100, 1000));
  assert.strictEqual(foe.maxhp, hpStatOf(78, 1000));
  assert.strictEqual(foe.hp, foe.maxhp, 'a full-health Pokemon stays full');
  assert.strictEqual(mine.set.evs.atk, 0);
  assert.strictEqual(mine.storedStats.atk, statOf(83, 0));
});

test('evs: the first switch-in line already reports the raised max HP (it runs before the battle starts)', async () => {
  const b = await battle({towerFx: [{op: 'evs', sides: ['p2'], amount: 1000}], turns: 0});
  const max = hpStatOf(78, 1000);
  assert.ok(b.lines.some(line => line.startsWith('|switch|p2a') && line.includes(`${max}/${max}`)),
    'switch line: ' + b.lines.filter(l => l.startsWith('|switch|p2a')).join(' '));
});

test('evs: one stat only when asked, and the HP stat scales HP', async () => {
  const b = await battle({towerFx: [{op: 'evs', sides: ['p2'], amount: 400, stat: 'hp'}], turns: 0});
  const foe = b.battle.sides[1].active[0];
  assert.strictEqual(foe.maxhp, hpStatOf(78, 400));
  assert.strictEqual(foe.storedStats.atk, statOf(84, 0), 'the other stats are untouched');
});

test('evs: a stronger Pokemon really deals more damage (same seed, same moves)', async () => {
  const plain = await battle({moves: ['move 2', 'move 1']});
  const strong = await battle({towerFx: [{op: 'evs', sides: ['p2'], amount: 2000}], moves: ['move 2', 'move 1']});
  const lost = b => maxhp(b, 0) - hp(b, 0);
  assert.ok(lost(strong) > lost(plain), `lost ${lost(strong)} vs ${lost(plain)}`);
});

test('evs: bounded - one operation at most 2000, a stat at most 4000 in total', async () => {
  const b = await battle({towerFx: [
    {op: 'evs', sides: ['p2'], amount: 99999}, {op: 'evs', sides: ['p2'], amount: 99999}, {op: 'evs', sides: ['p2'], amount: 99999},
  ], turns: 0});
  assert.strictEqual(b.battle.sides[1].active[0].set.evs.spa, 4000);
});

test('evs: a bad stat or amount is reported and skipped, and the battle still starts', async () => {
  const b = await battle({towerFx: [{op: 'evs', sides: ['p2'], amount: 100, stat: 'luck'}, {op: 'evs', sides: ['p2'], amount: 'lots'}], turns: 0});
  assert.ok(started(b));
  assert.strictEqual(b.battle.sides[1].active[0].set.evs.atk, 0);
  assert.ok(said(b, /could not be applied/));
});

test('hp: lowers the named side to a percentage and never raises it', async () => {
  const b = await battle({towerFx: [{op: 'hp', sides: ['p2'], percent: 50}], moves: ['move 4', 'move 4']});
  const full = maxhp(b, 1);
  assert.ok(hp(b, 1) <= Math.floor(full / 2) + 1, `foe at ${hp(b, 1)}/${full}`);
  assert.strictEqual(hp(b, 0), maxhp(b, 0), 'the other side is untouched');
  const noRaise = await battle({towerFx: [{op: 'hp', sides: ['p2'], percent: 100}]});
  assert.ok(hp(noRaise, 1) <= maxhp(noRaise, 1));
});

test('status: puts the status on the named side', async () => {
  const b = await battle({towerFx: [{op: 'status', sides: ['p2'], status: 'par'}], turns: 0});
  assert.strictEqual(b.battle.sides[1].active[0].status, 'par');
  assert.strictEqual(b.battle.sides[0].active[0].status, '');
});

test("status: the simulator's own immunities are respected (a Fire type cannot be burned)", async () => {
  const b = await battle({towerFx: [{op: 'status', sides: ['p2'], status: 'brn'}], turns: 0});
  assert.strictEqual(b.battle.sides[1].active[0].status, '', 'Charizard stays unburned, and the battle carried on');
  assert.ok(started(b));
  const blastoise = await battle({towerFx: [{op: 'status', sides: ['p1'], status: 'brn'}], turns: 0});
  assert.strictEqual(blastoise.battle.sides[0].active[0].status, 'brn');
});

test('sidecondition: adds Tailwind to the named side', async () => {
  const b = await battle({towerFx: [{op: 'sidecondition', sides: ['p1'], id: 'tailwind', duration: 4}]});
  assert.ok(b.battle.sides[0].sideConditions.tailwind, 'p1 has tailwind');
  assert.ok(!b.battle.sides[1].sideConditions.tailwind);
});

test('damage: scales what the named side deals, only for the named type', async () => {
  const base = await battle({});
  const baseLoss = maxhp(base, 1) - hp(base, 1);
  const boosted = await battle({towerFx: [{op: 'damage', sides: ['p1'], type: 'Ice', percent: 150}]});
  const boostedLoss = maxhp(boosted, 1) - hp(boosted, 1);
  assert.ok(Math.abs(boostedLoss - Math.floor(baseLoss * 1.5)) <= 3, `loss ${baseLoss} -> ${boostedLoss}`);
  const wrongType = await battle({towerFx: [{op: 'damage', sides: ['p1'], type: 'Fire', percent: 150}]});
  assert.strictEqual(hp(wrongType, 1), hp(base, 1), 'a Fire boost does nothing to a Water move');
  const any = await battle({towerFx: [{op: 'damage', sides: ['p1'], type: 'any', percent: 50}]});
  assert.ok(maxhp(any, 1) - hp(any, 1) < baseLoss, '"any" applies to every move');
});

test('resist: scales what the named side takes', async () => {
  const base = await battle({});
  const baseLoss = maxhp(base, 1) - hp(base, 1);
  const tough = await battle({towerFx: [{op: 'resist', sides: ['p2'], type: 'any', percent: 50}]});
  const toughLoss = maxhp(tough, 1) - hp(tough, 1);
  assert.ok(Math.abs(toughLoss - Math.floor(baseLoss / 2)) <= 3, `loss ${baseLoss} -> ${toughLoss}`);
  assert.strictEqual(hp(tough, 0), hp(base, 0), 'damage dealt to p1 is not changed by p2\'s resistance');
});

test('cap: bounds what a side\'s damage rules add up to (balance phase 2)', async () => {
  const base = await battle({});
  const baseLoss = maxhp(base, 1) - hp(base, 1);
  const stacked = [{op: 'damage', sides: ['p1'], type: 'any', percent: 150}, {op: 'damage', sides: ['p1'], type: 'any', percent: 150}];
  const capped = await battle({towerFx: [...stacked, {op: 'cap', sides: ['p1'], dealt: 135, taken: 80}]});
  const cappedLoss = maxhp(capped, 1) - hp(capped, 1);
  assert.ok(Math.abs(cappedLoss - Math.floor(baseLoss * 1.35)) <= 3, `two x1.5 relics capped at x1.35: ${baseLoss} -> ${cappedLoss}`);
  const tough = await battle({towerFx: [{op: 'resist', sides: ['p2'], type: 'any', percent: 50}, {op: 'cap', sides: ['p2'], dealt: 135, taken: 80}]});
  const toughLoss = maxhp(tough, 1) - hp(tough, 1);
  assert.ok(Math.abs(toughLoss - Math.floor(baseLoss * 0.8)) <= 3, `x0.5 taken floored at x0.8: ${baseLoss} -> ${toughLoss}`);
  const otherSide = await battle({towerFx: [...stacked, {op: 'cap', sides: ['p2'], dealt: 100, taken: 100}]});
  assert.ok(maxhp(otherSide, 1) - hp(otherSide, 1) > cappedLoss, 'a cap on p2 does not bound p1');
});

test('enrage: the named side gains +1 Atk and SpA every N turns', async () => {
  const b = await battle({towerFx: [{op: 'enrage', sides: ['p2'], every: 2}], turns: 4});
  const foe = b.battle.sides[1].active[0];
  assert.strictEqual(foe.boosts.atk, 2, 'enraged after turns 2 and 4');
  assert.strictEqual(foe.boosts.spa, 2);
  assert.strictEqual(b.battle.sides[0].active[0].boosts.atk, 0, 'p1 is not enraged');
  assert.ok(b.lines.some(line => /is enraged!/.test(line)), 'the log says so');
});

test('a damage rule is local to its battle: a later battle without it is unaffected', async () => {
  await battle({towerFx: [{op: 'damage', sides: ['p1'], type: 'any', percent: 300}]});
  const after = await battle({});
  assert.strictEqual(hp(after, 1), hp(baseline, 1));
});

// Region rules (P38).
test('suppress_items: the named side loses its item effects (Life Orb recoil), the other side keeps them, the item stays', async () => {
  const b = await battle({towerFx: [{op: 'suppress_items', sides: ['p1']}], items: ['lifeorb', 'lifeorb'], moves: ['move 1', 'move 1']});
  assert.ok(b.battle.sides[0].active[0].ignoringItem(), 'p1 ignores its item');
  assert.ok(!b.battle.sides[1].active[0].ignoringItem(), 'p2 does not');
  const p1Orb = b.lines.filter(line => /\|-damage\|p1a.*\[from\] item: Life Orb/.test(line));
  const p2Orb = b.lines.filter(line => /\|-damage\|p2a.*\[from\] item: Life Orb/.test(line));
  assert.strictEqual(p1Orb.length, 0, 'no Life Orb recoil on the suppressed side');
  assert.ok(p2Orb.length > 0, 'the other side still takes Life Orb recoil');
  assert.strictEqual(b.battle.sides[0].active[0].item, 'lifeorb', 'the item is not removed');
});

test('drain: the named side heals a share of the damage it deals, never over its max HP', async () => {
  // p1 (Blastoise) moves second, after taking a hit, so it has something to heal.
  const none = await battle({moves: ['move 2', 'move 1']});
  const drained = await battle({towerFx: [{op: 'drain', sides: ['p1'], percent: 20}], moves: ['move 2', 'move 1']});
  assert.ok(said(drained, /\|-heal\|p1a.*\[from\] drain/), 'p1 healed from drain');
  assert.ok(hp(drained, 0) > hp(none, 0), 'the drained side ends higher: ' + hp(drained, 0) + ' vs ' + hp(none, 0));
  assert.ok(hp(drained, 0) <= maxhp(drained, 0));
  assert.strictEqual(hp(drained, 1), hp(none, 1), 'the other side takes the same damage as without drain');
});

test('drain: a side not named does not heal, and an oversized percent is clamped', async () => {
  const b = await battle({towerFx: [{op: 'drain', sides: ['p1'], percent: 9999}], moves: ['move 2', 'move 1']});
  assert.ok(!said(b, /\|-heal\|p2a.*\[from\] drain/), 'p2 never drains');
  assert.ok(b.errors.length === 0);
});

test('several operations together all apply', async () => {
  const b = await battle({towerFx: [
    {op: 'weather', id: 'raindance'}, {op: 'boost', sides: ['p1'], stat: 'spa', stages: 1},
    {op: 'status', sides: ['p2'], status: 'par'}, {op: 'sidecondition', sides: ['p1'], id: 'reflect'}]});
  assert.strictEqual(b.battle.field.weather, 'raindance');
  assert.strictEqual(stage(b, 0, 'spa'), 1);
  assert.strictEqual(b.battle.sides[1].active[0].status, 'par');
  assert.ok(b.battle.sides[0].sideConditions.reflect);
});

test('one bad operation is reported and skipped; the ones around it still apply', async () => {
  const b = await battle({towerFx: [
    {op: 'boost', sides: ['p1'], stat: 'atk', stages: 1},
    {op: 'weather', id: 'notaweather'},
    {op: 'boost', sides: ['p1'], stat: 'def', stages: 1}]});
  assert.strictEqual(stage(b, 0, 'atk'), 1);
  assert.strictEqual(stage(b, 0, 'def'), 1);
  assert.ok(said(b, /could not be applied/), 'the failure is said out loud in the log');
});

test('hostile input: the battle always starts, nothing throws, nothing unexpected is applied', async () => {
  const hostile = [
    'not an array', 42, null, {op: 'weather'}, [], [null], [42], ['weather'], [[]],
    [{}], [{op: 7}], [{op: '__proto__'}], [{op: 'constructor'}], [{op: 'toString'}], [{op: 'eval', code: '1+1'}],
    [{op: 'boost', sides: 'p1', stat: 'atk', stages: 1}], [{op: 'boost', sides: [1, null, {}], stat: 'atk', stages: 1}],
    [{op: 'boost', sides: ['p1'], stat: '__proto__', stages: 1}], [{op: 'boost', sides: ['p9'], stat: 'atk', stages: 1}],
    [{op: 'weather', id: '../../etc/passwd'}], [{op: 'weather', id: {}}], [{op: 'weather', id: 'raindance', duration: 'x'}],
    [{op: 'status', sides: ['p1'], status: 'fnt'}], [{op: 'sidecondition', sides: ['p1'], id: 'stealthrock'}],
    [{op: 'damage', sides: ['p1'], type: 'Pizza', percent: 150}], [{op: 'damage', sides: ['p1'], percent: 1e308}],
    [{op: 'damage', sides: ['p1'], percent: -50}], [{op: 'hp', sides: ['p1'], percent: 'NaN'}],
    Array.from({length: 1000}, () => ({op: 'boost', sides: ['p1'], stat: 'atk', stages: 1})),
    [{op: 'boost', sides: ['p1'], stat: 'atk', stages: 1, extra: {deep: {deeper: [1, 2, 3]}}}],
  ];
  for (const payload of hostile) {
    const b = await battle({towerFx: payload});
    assert.ok(started(b), 'the battle started for ' + JSON.stringify(payload).slice(0, 80));
    assert.deepStrictEqual(b.errors, [], 'nothing threw for ' + JSON.stringify(payload).slice(0, 80));
    assert.ok(stage(b, 0, 'atk') <= 6 && stage(b, 0, 'atk') >= -6, 'stages stay in range');
  }
});

test('a thousand operations apply only the first 32 and say so', async () => {
  const b = await battle({towerFx: Array.from({length: 1000}, () => ({op: 'boost', sides: ['p1'], stat: 'atk', stages: 1}))});
  assert.strictEqual(stage(b, 0, 'atk'), 6, 'clamped at +6 long before 32 raises');
  assert.ok(said(b, /Only the first 32 tower effects/));
});

test('damage multipliers are bounded: 1e308 percent is clamped to 300, never infinite or NaN', async () => {
  const base = await battle({});
  const b = await battle({towerFx: [{op: 'damage', sides: ['p1'], type: 'any', percent: 1e308}]});
  const loss = maxhp(b, 1) - hp(b, 1);
  assert.ok(Number.isFinite(hp(b, 1)) && hp(b, 1) >= 0);
  assert.ok(loss <= (maxhp(base, 1) - hp(base, 1)) * 3 + 3, 'at most three times the damage');
});

test('towerFx on a battle that is a restored (deserialized) battle is not applied a second time', async () => {
  const b = await battle({towerFx: [{op: 'boost', sides: ['p1'], stat: 'atk', stages: 1}]});
  b.battle.towerFxApplied = true;   // the guard a restored battle relies on
  fx.applyTowerFx(b.battle);
  assert.strictEqual(stage(b, 0, 'atk'), 1, 'not applied twice');
});

/**
 * Cobblemon runs Showdown in GraalJS, which has no Node built-ins (require('fs') throws). This preload makes plain
 * Node match for raid-patch.js and ext-*.js, so a leaning extension fails here rather than silently not loading on a
 * server.
 */
const NO_NODE_BUILTINS = `
  const Module = require('module');
  const blocked = new Set(['fs', 'path', 'os', 'child_process', 'util', 'crypto', 'stream', 'events']);
  const original = Module.prototype.require;
  Module.prototype.require = function (id) {
    const file = (this && this.filename) || '';
    if (/[\\\\/](raid-patch|ext-[a-z0-9_-]+|extensions)\\.js$/.test(file) && blocked.has(String(id).replace(/^node:/, ''))) {
      throw new TypeError("Cannot load module: '" + id + "'");
    }
    return original.apply(this, arguments);
  };
`;

function writeLoaderRig(tmp, raidPatch, ids) {
  for (const name of ['sim', 'data', 'lib', 'config', 'tools']) {
    if (fs.existsSync(path.join(SHOWDOWN, name))) fs.symlinkSync(path.join(SHOWDOWN, name), path.join(tmp, name), 'junction');
  }
  fs.copyFileSync(raidPatch, path.join(tmp, 'raid-patch.js'));
  fs.writeFileSync(path.join(tmp, 'extensions.js'), 'module.exports = ' + JSON.stringify(ids) + ';\n');
  fs.writeFileSync(path.join(tmp, 'no-builtins.js'), NO_NODE_BUILTINS);
}

test('the extension loader: a module that throws is skipped, a good one still loads, unlisted files are ignored', async () => {
  const raidPatch = RAID_PATCH;
  if (!fs.existsSync(raidPatch) || !/extensions\.js/.test(fs.readFileSync(raidPatch, 'utf8'))) {
    console.log('    (skipped: no raid-patch.js with the manifest-based extension loader at ' + raidPatch + '; pass --raid-patch)');
    return;
  }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'towerfx-loader-'));
  try {
    writeLoaderRig(tmp, raidPatch, ['aaa-bad', 'zzz-good', 'Bad_Id', '../escape', 7]);
    fs.writeFileSync(path.join(tmp, 'ext-aaa-bad.js'), "throw new Error('this extension is broken');\n");
    fs.writeFileSync(path.join(tmp, 'ext-zzz-good.js'), "globalThis.__extGood = true;\n");
    fs.writeFileSync(path.join(tmp, 'ext-unlisted.js'), "globalThis.__extUnlisted = true;\n");
    fs.writeFileSync(path.join(tmp, 'extra.js'), "globalThis.__extExtra = true;\n");
    const result = spawnSync(process.execPath, ['-r', './no-builtins.js', '-e',
      "require('./raid-patch.js'); console.log(JSON.stringify({good: !!globalThis.__extGood, unlisted: !!globalThis.__extUnlisted, extra: !!globalThis.__extExtra}));"],
    {cwd: tmp, encoding: 'utf8'});
    assert.strictEqual(result.status, 0, 'raid-patch.js loaded despite the broken extension: ' + result.stderr);
    assert.match(result.stdout, /Showdown extension aaa-bad failed to load and was skipped/);
    assert.match(result.stdout, /Showdown extension zzz-good loaded/);
    assert.doesNotMatch(result.stdout, /Could not load Showdown extensions|Cannot load module/, 'no built-in was needed');
    assert.match(result.stdout, /\{"good":true,"unlisted":false,"extra":false\}/);
  } finally {
    fs.rmSync(tmp, {recursive: true, force: true});
  }
});

test('the loader works with no manifest at all (an install that registered nothing)', async () => {
  const raidPatch = RAID_PATCH;
  if (!fs.existsSync(raidPatch) || !/extensions\.js/.test(fs.readFileSync(raidPatch, 'utf8'))) return;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'towerfx-nomanifest-'));
  try {
    writeLoaderRig(tmp, raidPatch, []);
    fs.rmSync(path.join(tmp, 'extensions.js'));
    const result = spawnSync(process.execPath, ['-r', './no-builtins.js', '-e', "require('./raid-patch.js'); console.log('loaded');"], {cwd: tmp, encoding: 'utf8'});
    assert.strictEqual(result.status, 0, result.stderr);
    assert.match(result.stdout, /loaded/);
    assert.doesNotMatch(result.stdout, /Could not load/);
  } finally {
    fs.rmSync(tmp, {recursive: true, force: true});
  }
});

test('the old directory-scanning loader would have failed this harness (the harness can see the bug it missed)', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'towerfx-selfcheck-'));
  try {
    fs.writeFileSync(path.join(tmp, 'no-builtins.js'), NO_NODE_BUILTINS);
    fs.writeFileSync(path.join(tmp, 'raid-patch.js'), "const fs = require('fs'); module.exports = fs.readdirSync(__dirname);\n");
    const result = spawnSync(process.execPath, ['-r', './no-builtins.js', '-e', "try { require('./raid-patch.js'); console.log('ok'); } catch (e) { console.log('blocked: ' + e.message); }"], {cwd: tmp, encoding: 'utf8'});
    assert.match(result.stdout, /blocked: Cannot load module: 'fs'/);
  } finally {
    fs.rmSync(tmp, {recursive: true, force: true});
  }
});

test('the full production chain: raid-patch.js loads the extension, which then applies effects to an ordinary battle', async () => {
  const raidPatch = RAID_PATCH;
  if (!fs.existsSync(raidPatch) || !/extensions\.js/.test(fs.readFileSync(raidPatch, 'utf8'))) {
    console.log('    (skipped: no raid-patch.js with the manifest-based extension loader; pass --raid-patch)');
    return;
  }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'towerfx-chain-'));
  try {
    writeLoaderRig(tmp, raidPatch, ['cobbletowers-fx']);
    // Installed under the id CobbleTowers registers, with no test flag, exactly as CobbleRaids' installer writes it.
    fs.copyFileSync(FX_FILE, path.join(tmp, 'ext-cobbletowers-fx.js'));
    fs.writeFileSync(path.join(tmp, 'chain.js'), `
      require('./raid-patch.js');
      const BS = require('./sim/battle-stream');
      const pack = ${pack.toString()};
      async function run(towerFx) {
        const stream = new BS.BattleStream();
        (async () => { for await (const c of stream) {} })();
        const format = {mod: 'gen9', gameType: 'singles', gen: 9, ruleset: [], effectType: 'Format'};
        if (towerFx) format.towerFx = towerFx;
        stream.write('>start ' + JSON.stringify({format, seed: [1, 2, 3, 4]}));
        stream.write('>player p1 ' + JSON.stringify({name: 'A', team: pack('Blastoise', 'torrent', ['icebeam'], 50)}));
        stream.write('>player p2 ' + JSON.stringify({name: 'B', team: pack('Charizard', 'blaze', ['roost'], 50)}));
        await new Promise(r => setTimeout(r, 250));
        stream.write('>p1 move 1'); stream.write('>p2 move 1');
        await new Promise(r => setTimeout(r, 250));
        const b = stream.battle;
        return {weather: b.field.weather, foeHp: b.sides[1].active[0].hp, atk: b.sides[0].active[0].boosts.atk};
      }
      (async () => {
        const plain = await run(undefined);
        const fx = await run([{op: 'weather', id: 'raindance'}, {op: 'boost', sides: ['p1'], stat: 'atk', stages: 1},
                              {op: 'damage', sides: ['p1'], type: 'any', percent: 200}]);
        console.log(JSON.stringify({plain, fx}));
        process.exit(0);
      })();
    `);
    const result = spawnSync(process.execPath, ['-r', './no-builtins.js', 'chain.js'], {cwd: tmp, encoding: 'utf8'});
    assert.strictEqual(result.status, 0, result.stderr || result.stdout);
    assert.match(result.stdout, /Showdown extension cobbletowers-fx loaded/);
    assert.match(result.stdout, /\[CobbleTowers\] Applied 3 of 3 tower effect\(s\)/);
    const line = result.stdout.split('\n').filter(l => l.startsWith('{"plain"'))[0];
    assert.ok(line, 'the chain script reported: ' + result.stdout.slice(0, 300));
    const {plain, fx: withFx} = JSON.parse(line);
    assert.strictEqual(plain.weather, '', 'an ordinary battle through the same chain has no weather');
    assert.strictEqual(plain.atk, 0);
    assert.strictEqual(withFx.weather, 'raindance', 'the extension loaded by raid-patch.js applied weather');
    assert.strictEqual(withFx.atk, 1);
    assert.ok(withFx.foeHp < plain.foeHp, 'and doubled damage: foe at ' + withFx.foeHp + ' vs ' + plain.foeHp);
  } finally {
    fs.rmSync(tmp, {recursive: true, force: true});
  }
});

// ---- run -----------------------------------------------------------------------------------------------------

(async () => {
  // The reference battle is run BEFORE the patch is installed, so the first test compares against the real engine.
  baseline = await battle({});
  fx.install({Battle});

  let failed = 0;
  for (const {name, fn} of tests) {
    try {
      await fn();
      console.log('  [PASS] ' + name);
    } catch (err) {
      failed++;
      console.log('  [FAIL] ' + name + '\n         ' + String((err && err.stack) || err).split('\n').slice(0, 4).join('\n         '));
    }
  }
  console.log(`\n${tests.length - failed}/${tests.length} checks passed`);
  process.exit(failed ? 1 : 0);
})();
