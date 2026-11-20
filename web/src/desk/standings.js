// Pure helpers for the Frontier Desk Hall (P51): what a tower row and the team card say, from the payload's `extras`.
import DATA from '../gen/data.js';
import { ATLAS } from '../gen/atlas.js';

const TONES = { bronze: 'b', silver: 's', gold: 'g', platinum: 'p', diamond: 'd', master: 'm', champion: 'c' };

/** The pill colour class for a mastery rank; level 0 is always "unranked". */
export const rankTone = (st) => (!st || st.level <= 0 ? 'n' : TONES[String(st.rank).toLowerCase()] || 'n');

/** "Unranked", "Silver · 2 levels to next", "Gold (top rank)". */
export function standingText(st) {
  if (!st || st.level <= 0) return 'Unranked';
  return st.toNext < 0 ? st.rank + ' (top rank)' : st.rank + ' · ' + st.toNext + (st.toNext === 1 ? ' level' : ' levels') + ' to next';
}

export const standingOf = (extras, towerId) => (extras?.standings ?? []).find((s) => s.tower === towerId) || null;

/** Three foes to show for a tower id like "cobbletowers:tideforge"; empty when the build knows none. */
export const residentsOf = (towerId) => (DATA.residents?.[String(towerId).split(':').pop()] ?? []).slice(0, 3);

export const hasSprite = (name) => !!ATLAS.map[name];

/** "6 of 6 · Level 50" and how many are down. */
export function teamSummary(party) {
  const levels = party.map((m) => m.level);
  const lo = Math.min(...levels), hi = Math.max(...levels);
  return {
    count: party.length,
    fainted: party.filter((m) => m.fainted).length,
    text: party.length === 0 ? 'No Pokémon in your party'
      : (party.length === 1 ? '1 Pokémon' : party.length + ' of 6') + ' · ' + (lo === hi ? 'Level ' + lo : 'Levels ' + lo + ' to ' + hi),
  };
}

/**
 * The Hall's eligibility line: whether the team fits the mode of the player's lobby. Null when there is no lobby mode to judge
 * (the server sends an empty `modeName`), so the card shows nothing rather than a guess.
 * @returns {{ok: boolean, text: string} | null}
 */
export function modeLine(extras) {
  const name = extras?.modeName;
  if (!name) return null;
  const problems = extras.modeProblems ?? [];
  if (problems.length === 0) return { ok: true, text: 'Your team fits ' + name + '.' };
  const n = problems.length;
  return { ok: false, text: n === 1 ? '1 Pokémon breaks ' + name + '.' : n + ' Pokémon break ' + name + '.' };
}

/** The tower the Climb button starts with: the run's tower, else the first. */
/** The tower the Hall opens on: the one a hub host or portal sent the player to, else the run's, else the first. */
export const defaultPick = (hall) => hall.towers.find((t) => t.id === hall.extras?.focusTower)?.id
  || hall.towers.find((t) => t.id === hall.extras?.runTower)?.id || hall.towers[0]?.id || '';

/** The tower name without its trailing " Tower". */
export const shortName = (name) => String(name).replace(/ Tower$/, '');

/** The server's run status ("Floor 6 / INTERMISSION") as a short line ("Floor 6 · Intermission"). */
export function prettyStatus(status) {
  return String(status).split(' / ').map((part, i) => (i === 0 ? part : part.charAt(0) + part.slice(1).toLowerCase())).join(' · ');
}
