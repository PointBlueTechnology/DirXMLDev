import { FishboneBone, FishboneModel, FishbonePolicy } from "./fishbone";

export function fishboneHtml(model: FishboneModel, nonce: string, csp: string, cssHref: string): string {
  const json = JSON.stringify(model).replace(/</g, "\\u003c");
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta http-equiv="Content-Security-Policy" content="${csp}"/>
  <link rel="stylesheet" href="${cssHref}"/>
  <title>Policy Flow — ${esc(model.driver.name)}</title>
</head>
<body>
  <header class="bar">
    <div>
      <div class="kicker">DirXMLDev · Designer policy flow</div>
      <h1>${esc(model.driver.name)}</h1>
      <div class="meta">${esc(model.driverSet.name)}${model.driver.shimClass ? " · " + esc(model.driver.shimClass) : ""}</div>
    </div>
    <div class="legend">
      <span class="swatch pub"></span> Publisher (app → Identity Vault)
      <span class="swatch sub"></span> Subscriber (Identity Vault → app)
    </div>
  </header>
  <p class="hint" id="hint">Click a bone or policy to open its source. Refresh reloads from disk.</p>
  <div id="diagram">${renderSvg(model)}</div>
  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    const model = ${json};
    vscode.setState({ driver: model.driver.name, treeRoot: model.treeRoot });

    function send(type, nodeId) {
      vscode.postMessage({ type, nodeId });
    }
    document.getElementById("diagram").addEventListener("click", (ev) => {
      const node = ev.target.closest("[data-node]");
      if (!node) return;
      document.querySelectorAll(".selected").forEach((el) => el.classList.remove("selected"));
      node.classList.add("selected");
      const id = node.getAttribute("data-node");
      send("select", id);
      send("open", id);
    });
    window.addEventListener("message", (event) => {
      const msg = event.data;
      if (msg && msg.type === "hint") {
        document.getElementById("hint").textContent = msg.text;
      }
    });
  </script>
</body>
</html>`;
}

const POLICY_CHIP_W = 150;
const POLICY_CHIP_STEP = 26;
const POLICY_CHIP_H = 22;
/** Chip rect is drawn at centerY - POLICY_CHIP_TOP. */
const POLICY_CHIP_TOP = 10;

const LAYOUT = {
  width: 1240,
  spineY: 320,
  ribXs: [250, 400, 550, 700, 850] as const,
  idvX: 80,
  appX: 1165,
  schemaX: 990,
  ioX: 990,
  ribOffset: 118,
  boxHalf: 22,
  stackFromBox: 36,
  resourceGap: 32,
  resourceTileH: 48,
  resourceStackFromTile: 64,
  bottomPad: 40,
};

export interface FishboneLayout {
  width: number;
  height: number;
  spineY: number;
  resourceY: number;
  ribXs: readonly number[];
}

/**
 * Resource-row Y sits below the lowest subscriber (and output) chip so Startup /
 * Shutdown tiles cannot cover Creation / Placement stacks. SVG height grows with
 * both that row and any GCV/ECMAScript chips hanging under it.
 */
export function fishboneLayout(model: FishboneModel): FishboneLayout {
  const { width, spineY, ribXs } = LAYOUT;
  const channelBottom = subscriberSideBottom(model);
  const resourceY = channelBottom + LAYOUT.resourceGap;
  let resourceBottom = resourceY + LAYOUT.resourceTileH;
  for (const bone of model.resources) {
    if (bone.policies.length === 0) {
      continue;
    }
    const lastCenter = resourceY + LAYOUT.resourceStackFromTile + (bone.policies.length - 1) * POLICY_CHIP_STEP;
    resourceBottom = Math.max(resourceBottom, lastCenter + (POLICY_CHIP_H - POLICY_CHIP_TOP));
  }
  return { width, height: resourceBottom + LAYOUT.bottomPad, spineY, resourceY, ribXs };
}

function subscriberSideBottom(model: FishboneModel): number {
  const { spineY, ribOffset, boxHalf } = LAYOUT;
  let bottom = spineY + ribOffset + boxHalf;
  for (const bone of model.subscriber) {
    bottom = Math.max(bottom, stackBottom(spineY, 1, bone));
  }
  const output = model.spine.find((b) => b.key === "output");
  if (output) {
    bottom = Math.max(bottom, stackBottom(spineY, 1, output));
  }
  return bottom;
}

function stackBottom(spineY: number, dir: number, bone: FishboneBone): number {
  const boxY = spineY + dir * LAYOUT.ribOffset;
  const boxEdge = boxY + dir * LAYOUT.boxHalf;
  if (bone.policies.length === 0) {
    return Math.max(boxY, boxEdge);
  }
  const startY = boxY + dir * LAYOUT.stackFromBox;
  const lastCenter = startY + dir * (bone.policies.length - 1) * POLICY_CHIP_STEP;
  return lastCenter + dir * (POLICY_CHIP_H - POLICY_CHIP_TOP);
}

function renderSvg(model: FishboneModel): string {
  const { width, height, spineY, resourceY, ribXs } = fishboneLayout(model);
  const { idvX, appX, schemaX, ioX } = LAYOUT;

  const pubBones = model.publisher;
  const subBones = model.subscriber;
  const schema = model.spine.find((b) => b.key === "schema-mapping");
  const input = model.spine.find((b) => b.key === "input");
  const output = model.spine.find((b) => b.key === "output");

  const parts: string[] = [];
  parts.push(`<svg viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Policy flow fishbone">`);
  parts.push(`<text class="channel-label pub" x="530" y="36" text-anchor="middle">Publisher channel</text>`);
  parts.push(`<text class="channel-label sub" x="530" y="${height - 16}" text-anchor="middle">Subscriber channel</text>`);

  // spine
  parts.push(`<line class="spine" x1="${idvX + 48}" y1="${spineY}" x2="${appX - 48}" y2="${spineY}"/>`);
  parts.push(endCap(idvX, spineY, "Identity Vault", "idv"));
  parts.push(endCap(appX, spineY, "Application", "app"));

  pubBones.forEach((bone, i) => {
    parts.push(rib(ribXs[i]!, spineY, -1, bone));
  });
  subBones.forEach((bone, i) => {
    parts.push(rib(ribXs[i]!, spineY, 1, bone));
  });

  if (input) {
    parts.push(ioBone(ioX, spineY, -1, input));
  }
  if (schema) {
    parts.push(spineBox(schemaX, spineY, schema.label, schema.id, "schema", schema.policies.length));
  }
  if (output) {
    parts.push(ioBone(ioX, spineY, 1, output));
  }

  // Filter + driver-level sets (ECMAScript, GCV, Startup, Shutdown) — off the channel ribs.
  let rx = 24;
  const ry = resourceY;
  if (model.filter) {
    const w = 130;
    parts.push(`<g class="bone filter" data-node="${escAttr(model.filter.id)}" tabindex="0">
      <rect x="${rx}" y="${ry}" width="${w}" height="${LAYOUT.resourceTileH}" rx="6"/>
      <text class="bone-title" x="${rx + 10}" y="${ry + 20}" text-anchor="start">Filter</text>
      <text class="count" x="${rx + 10}" y="${ry + 38}" text-anchor="start">driver-filter.xml</text>
    </g>`);
    rx += w + 12;
  }
  for (const bone of model.resources) {
    const w = 150;
    parts.push(resourceBone(rx, ry, w, bone));
    rx += w + 12;
  }

  parts.push(`</svg>`);
  return parts.join("\n");
}

function endCap(cx: number, cy: number, label: string, kind: string): string {
  return `<g class="end ${kind}">
    <rect x="${cx - 48}" y="${cy - 28}" width="96" height="56" rx="8"/>
    <text x="${cx}" y="${cy + 4}" text-anchor="middle">${esc(label)}</text>
  </g>`;
}

function spineBox(
  cx: number,
  cy: number,
  label: string,
  id: string,
  klass: string,
  count: number,
): string {
  const empty = count === 0 ? " empty" : "";
  return `<g class="bone spine-node ${klass}${empty}" data-node="${escAttr(id)}" tabindex="0">
    <rect x="${cx - 62}" y="${cy - 22}" width="124" height="44" rx="6"/>
    <text class="bone-title" x="${cx}" y="${cy - 2}" text-anchor="middle">${esc(label)}</text>
    <text class="count" x="${cx}" y="${cy + 14}" text-anchor="middle">${count} polic${count === 1 ? "y" : "ies"}</text>
  </g>`;
}

function ioBone(cx: number, spineY: number, dir: number, bone: FishboneBone): string {
  // Input sits above the schema node toward the app; output below — Designer
  // places both transforms at the application end of the spine.
  const boxY = spineY + dir * LAYOUT.ribOffset;
  const empty = bone.policies.length === 0 ? " empty" : "";
  const klass = dir < 0 ? "pub" : "sub";
  return `<g class="bone ${klass}${empty}" data-node="${escAttr(bone.id)}" tabindex="0">
    <line class="rib" x1="${cx}" y1="${spineY + dir * LAYOUT.boxHalf}" x2="${cx}" y2="${boxY - dir * LAYOUT.boxHalf}"/>
    <rect x="${cx - 70}" y="${boxY - LAYOUT.boxHalf}" width="140" height="${LAYOUT.boxHalf * 2}" rx="6"/>
    <text class="bone-title" x="${cx}" y="${boxY - 4}" text-anchor="middle">${esc(bone.label)}</text>
    <text class="count" x="${cx}" y="${boxY + 14}" text-anchor="middle">${bone.policies.length} polic${bone.policies.length === 1 ? "y" : "ies"}</text>
    ${policyStack(cx, boxY + dir * LAYOUT.stackFromBox, bone, dir)}
  </g>`;
}

function rib(cx: number, spineY: number, dir: number, bone: FishboneBone): string {
  const boxY = spineY + dir * LAYOUT.ribOffset;
  const empty = bone.policies.length === 0 ? " empty" : "";
  const klass = dir < 0 ? "pub" : "sub";
  return `<g class="bone ${klass}${empty}" data-node="${escAttr(bone.id)}" tabindex="0">
    <line class="rib" x1="${cx}" y1="${spineY}" x2="${cx}" y2="${boxY - dir * LAYOUT.boxHalf}"/>
    <rect x="${cx - 68}" y="${boxY - LAYOUT.boxHalf}" width="136" height="${LAYOUT.boxHalf * 2}" rx="6"/>
    <text class="bone-title" x="${cx}" y="${boxY - 4}" text-anchor="middle">${esc(bone.label)}</text>
    <text class="count" x="${cx}" y="${boxY + 14}" text-anchor="middle">${bone.policies.length} polic${bone.policies.length === 1 ? "y" : "ies"}</text>
    ${policyStack(cx, boxY + dir * LAYOUT.stackFromBox, bone, dir)}
  </g>`;
}

function policyStack(cx: number, startY: number, bone: FishboneBone, dir: number): string {
  return bone.policies
    .map((p, i) => {
      const y = startY + dir * i * POLICY_CHIP_STEP;
      const w = POLICY_CHIP_W;
      const clipId = clipIdFor(p.id);
      const klass = p.unresolved ? "policy unresolved" : "policy";
      return `<g class="${klass}" data-node="${escAttr(p.id)}">
        <clipPath id="${escAttr(clipId)}"><rect x="${cx - w / 2}" y="${y - POLICY_CHIP_TOP}" width="${w}" height="${POLICY_CHIP_H}" rx="4"/></clipPath>
        <rect x="${cx - w / 2}" y="${y - POLICY_CHIP_TOP}" width="${w}" height="${POLICY_CHIP_H}" rx="4"/>
        <text x="${cx}" y="${y + 5}" text-anchor="middle" clip-path="url(#${escAttr(clipId)})">${esc(shortName(p))}</text>
      </g>`;
    })
    .join("\n");
}

function resourceBone(x: number, y: number, w: number, bone: FishboneBone): string {
  const empty = bone.policies.length === 0 ? " empty" : "";
  const cx = x + w / 2;
  const n = bone.policies.length;
  return `<g class="bone resource${empty}" data-node="${escAttr(bone.id)}" tabindex="0">
    <rect x="${x}" y="${y}" width="${w}" height="${LAYOUT.resourceTileH}" rx="6"/>
    <text class="bone-title" x="${x + 10}" y="${y + 20}" text-anchor="start">${esc(bone.label)}</text>
    <text class="count" x="${x + 10}" y="${y + 38}" text-anchor="start">${n} ${n === 1 ? "item" : "items"}</text>
    ${policyStack(cx, y + LAYOUT.resourceStackFromTile, bone, 1)}
  </g>`;
}

function clipIdFor(nodeId: string): string {
  return "clip-" + nodeId.replace(/[^A-Za-z0-9_-]/g, "_");
}

function shortName(p: FishbonePolicy): string {
  const n = p.name;
  return n.length > 22 ? n.slice(0, 20) + "…" : n;
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function escAttr(s: string): string {
  return esc(s).replace(/"/g, "&quot;");
}
