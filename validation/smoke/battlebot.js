// A player that fights Cobblemon battles properly, replacing the CobbleRaids rig's raidbot.js: reads each battle
// request, picks a move the battle accepts and answers a forced switch itself (DEFAULT breaks CobbleRaids' patched
// chooseSwitch). Singles only; anything undecodable falls back to DEFAULT. Usage: node battlebot.js <username> [port]
// [move-preference,comma,separated]. A line `SAY <text>` appended to cmd_<username>.txt is sent as chat. mineflayer
// comes from the rig's node_modules via NODE_PATH.
const mineflayer = require('mineflayer');

const username = process.argv[2];
if (!username) { console.error('usage: node battlebot.js <username> [port] [moves]'); process.exit(1); }
const port = Number(process.argv[3] || 25565);
const tag = `[${username}]`;

// Strong, reliable attacks first. Anything not listed is tried in the order the battle offers it, after the
// moves that are known to do nothing on turn one are pushed to the back.
const PREFER = (process.argv[4] ? process.argv[4].split(',') : [
  'freezedry', 'blizzard', 'icebeam', 'surf', 'flamethrower', 'thunderbolt', 'psychic', 'earthquake',
  'hyperbeam', 'shadowball', 'closecombat', 'crunch', 'hydropump', 'bodyslam', 'return', 'tackle',
]);
const AVOID = new Set([
  'lastresort', 'mirrorcoat', 'counter', 'metalburst', 'protect', 'detect', 'splash', 'focuspunch',
  'endure', 'bide', 'sleeptalk', 'snore',
]);

const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, version: '1.21.1', auth: 'offline' });
require('./skipbad')(bot);
bot._client.on('error', () => {});   // Cobblemon's entity metadata is not in mineflayer's tables; routine

let battleId = null;     // 16 raw bytes, from battle_initialize
let request = null;      // the last decoded request, or null if it could not be
let rejected = new Set();
let lastChoice = 0;

// ---- reading Cobblemon's buffers --------------------------------------------------------------

class Reader {
  constructor(buf) { this.b = buf; this.o = 0; }
  u8() { return this.b[this.o++]; }
  bool() { return this.u8() !== 0; }
  varint() {
    let result = 0, shift = 0, byte;
    do { byte = this.u8(); result |= (byte & 0x7f) << shift; shift += 7; } while (byte & 0x80);
    return result;
  }
  str() {
    const length = this.varint();
    const text = this.b.toString('utf8', this.o, this.o + length);
    this.o += length;
    return text;
  }
  // Cobblemon reads an enum with readInt(): four bytes, not a varint.
  int32() { const v = this.b.readInt32BE(this.o); this.o += 4; return v; }
  skip(n) { this.o += n; }
}

/** A nullable list holding one nullable gimmick move (name, target, disabled) per ordinary move. */
function gimmickMoves(r, count) {
  if (!r.bool()) return;
  for (let i = 0; i < count; i++) {
    if (r.bool()) { r.str(); r.int32(); r.bool(); }
  }
}

/** BattleQueueRequestPacket's payload. Throws on anything outside plain singles, so the caller falls back. */
function decodeRequest(buf) {
  const r = new Reader(buf);
  const out = { wait: r.bool(), active: [], forceSwitch: [], pokemon: [], sideId: 'p1' };
  const activeCount = r.u8();
  for (let i = 0; i < activeCount; i++) {
    const moves = [];
    const moveCount = r.u8();
    for (let j = 0; j < moveCount; j++) {
      moves.push({ id: r.str(), name: r.str(), pp: r.u8(), maxpp: r.u8(), target: r.int32(), disabled: r.bool() });
    }
    r.bool(); r.bool(); r.bool();                       // trapped, canMegaEvo, canUltraBurst
    gimmickMoves(r, moves.length);                      // canZMove: a nullable list, one nullable move each
    r.bool();                                           // canDynamax
    gimmickMoves(r, moves.length);                      // maxMoves: present even when Dynamax is off
    if (r.bool()) r.str();                              // canTerastallize (nullable string)
    out.active.push({ moves });
  }
  const forceCount = r.u8();
  for (let i = 0; i < forceCount; i++) out.forceSwitch.push(r.bool());
  r.bool();                                             // noCancel
  if (r.bool()) {                                       // a side follows
    r.skip(16);                                         // the side's UUID
    out.sideId = r.str();                               // its showdown id: "p1" or "p2"
    const count = r.u8();
    for (let i = 0; i < count; i++) {
      const ident = r.str();
      const details = r.str();                          // "Species, <uuid>, L50, ..."
      const condition = r.str();                        // "245/245" or "0 fnt"
      const active = r.bool(); r.bool(); r.bool();      // active, reviving, commanding
      for (let n = r.u8(); n > 0; n--) r.str();         // moves
      r.str(); r.str(); r.str();                        // base ability, pokeball, ability
      for (let n = r.u8(); n > 0; n--) r.str();         // base types
      for (let n = r.u8(); n > 0; n--) r.str();         // types
      const uuid = (details.split(',')[1] || '').trim();
      out.pokemon.push({ ident, uuid, fainted: condition.includes('fnt'), active });
    }
  }
  return out;
}

// ---- answering --------------------------------------------------------------------------------

function utf(text) {
  const body = Buffer.from(text, 'utf8');
  return Buffer.concat([Buffer.from([body.length]), body]);   // names are short: a one-byte varint
}

function send(responses) {
  const data = Buffer.concat([battleId, Buffer.from([responses.length]), ...responses]);
  bot._client.write('custom_payload', { channel: 'cobblemon:battle_select_actions', data });
}

function pickMove(moves) {
  const usable = moves.filter((m) => !m.disabled && m.pp > 0 && !rejected.has(m.id));
  for (const wanted of PREFER) {
    const hit = usable.find((m) => m.id === wanted);
    if (hit) return hit;
  }
  return usable.find((m) => !AVOID.has(m.id)) || usable[0] || null;
}

// Cobblemon's MoveTarget ordinals (read from the request as a four-byte int): a move that must be aimed --
// any (0), normal (5), adjacentFoe (12) -- is refused without a target; the rest are refused WITH one.
const NEEDS_TARGET = new Set([0, 5, 12]);

/** The opponent's slot in a singles battle: the side that is not ours, position a. */
function opponentSlot() {
  return (request && request.sideId === 'p2') ? 'p1a' : 'p2a';
}

function choose() {
  const now = Date.now();
  if (now - lastChoice < 80 || !battleId) return;    // collapse true duplicates, never a re-prompt
  lastChoice = now;

  if (request && request.forceSwitch.some(Boolean)) {
    const next = request.pokemon.find((p) => !p.fainted && !p.active && p.uuid);
    if (next) {
      const uuidBytes = Buffer.from(next.uuid.replace(/-/g, ''), 'hex');
      send([Buffer.concat([Buffer.from([0x00]), uuidBytes])]);   // SWITCH(0) + the Pokemon's UUID
      console.log(`${tag} SWITCH ${next.ident}`);
      return;
    }
  } else if (request && request.active.length > 0) {
    // BOT_TRY_SWITCH=1: on its second turn of a battle the bot tries to swap a healthy Pokemon out by choice. Where
    // the
    // rules allow it that is harmless; under a no-switching rule the server must refuse it (see
    // forced_switch_test.py).
    turnsInBattle += 1;
    if (process.env.BOT_TRY_SWITCH && turnsInBattle === 2) {
      const bench = request.pokemon.find((p) => !p.fainted && !p.active && p.uuid);
      if (bench) {
        send([Buffer.concat([Buffer.from([0x00]), Buffer.from(bench.uuid.replace(/-/g, ''), 'hex')])]);
        console.log(`${tag} TRY_SWITCH ${bench.ident}`);
        return;
      }
    }
    const move = pickMove(request.active[0].moves);
    if (move) {
      // MOVE(1), the move id, the target (null unless the move must be aimed), and a null gimmick.
      const aimed = NEEDS_TARGET.has(move.target);
      const target = aimed ? Buffer.concat([Buffer.from([0x01]), utf(opponentSlot())]) : Buffer.from([0x00]);
      send([Buffer.concat([Buffer.from([0x01]), utf(move.id), target, Buffer.from([0x00])])]);
      console.log(`${tag} CHOICE ${move.id}${aimed ? ' -> ' + opponentSlot() : ''}`);
      lastMove = move.id;
      return;
    }
  }
  send([Buffer.from([0x02])]);                       // DEFAULT(2): Showdown picks (Struggle, if nothing else)
  console.log(`${tag} CHOICE DEFAULT`);
}

let lastMove = null;
let turnsInBattle = 0;

bot._client.on('custom_payload', (p) => {
  const channel = p.channel || '';
  if (!channel.startsWith('cobblemon')) return;
  const data = p.data || Buffer.alloc(0);
  if (process.env.BOT_TRACE && channel.startsWith('cobblemon:battle')) console.log(`${tag} PKT ${channel}`);

  if (channel === 'cobblemon:battle_initialize' && data.length >= 16) {
    battleId = data.subarray(0, 16);
    turnsInBattle = 0;
    rejected = new Set();
    request = null;
    console.log(`${tag} BATTLE ${battleId.toString('hex')}`);
  } else if (channel === 'cobblemon:battle_queue_request') {
    try {
      request = decodeRequest(data);
    } catch (e) {
      request = null;
      console.log(`${tag} UNDECODABLE ${e.message}`);
    }
  } else if (channel === 'cobblemon:battle_make_choice') {
    choose();   // "you may choose now": the one signal that is safe to answer
  }
});

// A refused choice is not fatal: remember the move, and answer again with the next candidate. The battle
// re-prompts after a refusal, but not reliably, so do not wait for it.
bot.on('messagestr', (text) => {
  if (!text.includes('Invalid action choice')) return;
  console.log(`${tag} REFUSED ${lastMove}`);
  if (lastMove) rejected.add(lastMove);
  lastChoice = 0;
  choose();
});

// SAY lines from the harness: typed as the player (a leading slash makes it a command). Everything the server says
// back that mentions a refused command is logged on its own line, so a test can read the answer.
const fs = require('fs');
const commandFile = `cmd_${username}.txt`;
let commandOffset = 0;
setInterval(() => {
  let text;
  try { text = fs.readFileSync(commandFile, 'utf8'); } catch (e) { return; }
  if (text.length <= commandOffset) return;
  const fresh = text.slice(commandOffset);
  commandOffset = text.length;
  for (const line of fresh.split(String.fromCharCode(10))) {
    if (line.startsWith('SAY ')) { const said = line.slice(4).trim(); console.log(`${tag} SAID ${said}`); bot.chat(said); }
  }
}, 500);
bot.on('messagestr', (text) => {
  if (/World Boss|Haunting|Candy Corn|fight opens|You are in/.test(text)) console.log(`${tag} CHAT ${text.replace(/\s+/g, ' ').slice(0, 220)}`);
  if (text.includes('turned off inside the tower')) console.log(`${tag} BLOCKED ${text}`);
  if (text.includes('Switching is not allowed')) console.log(`${tag} SWITCH_REFUSED ${text}`);
  // A refusal is followed at once by a fresh request and "choose now"; the duplicate guard in choose() must not eat
  // it.
  if (text.includes('is not allowed in this battle') || text.includes('Switching is not allowed')) lastChoice = 0;
});

bot.on('spawn', () => { console.log(`${tag} SPAWN`); console.log(`${tag} AUTOFIGHT on`); });
bot.on('kicked', (r) => console.log(`${tag} KICKED ${r}`));
bot.on('end', (r) => console.log(`${tag} END ${r}`));
process.on('uncaughtException', (e) => console.log(`${tag} SWALLOWED ${e.message}`));
