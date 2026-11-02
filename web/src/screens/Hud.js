import { useEffect } from 'preact/hooks';
import { html } from '../html.js';
import { closeGui } from '../bridge.js';
import { load } from '../settings.js';
import { DeskSpectator, DeskRareTray } from '../desk/DeskModals.js';

/** How long a HUD panel stays up with no update before it hides and the page closes. */
export const STALE_MS = 120_000;

/** The HUD page (opened with #hud): the spectator panel and the Haunted rare tray, both click-through. */
export function Hud({ spec, specAt, tray, trayAt }) {
  const last = Math.max(spec ? specAt : 0, tray ? trayAt : 0);
  useEffect(() => {
    if (!spec && !tray) return undefined;
    const id = setTimeout(closeGui, Math.max(0, last + STALE_MS - Date.now()));
    return () => clearTimeout(id);
  }, [spec, tray, last]);
  // The tray was taken down and nothing else is showing: close the page now.
  useEffect(() => {
    if (spec || tray || !trayAt) return undefined;
    const id = setTimeout(closeGui, 400);
    return () => clearTimeout(id);
  }, [spec, tray, trayAt]);
  const specView = !spec ? null : load().look === 'desk' ? html`<${DeskSpectator} spec=${spec} />`
    : html`<div id="specbox" class="nine dark"><b class="gold">Following ${spec.teammateName}</b>
    <div>${spec.remainingCount}/${spec.totalCount} Pokemon remaining</div><div class="sub">Floor ${spec.floorIndex} - ${spec.runStateLabel}</div></div>`;
  return html`${specView}${tray ? html`<${DeskRareTray} tray=${tray} />` : null}`;
}
