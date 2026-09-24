import * as vscode from "vscode";
import * as fs from "fs";
import * as path from "path";
import { TREE_MANIFEST, driverDirContaining, findTreeRoot, isTreeRoot } from "./ascode";
import { FishboneModel, filesForNode } from "./fishbone";
import { DriverListing, IdmLocation, findIdm, listDrivers, loadFishbone } from "./idm";
import { fishboneHtml } from "./webview";

let extensionUri: vscode.Uri;
let panel: vscode.WebviewPanel | undefined;
let current: { treeRoot: string; driver: string; idm: IdmLocation; model: FishboneModel } | undefined;
let selectedNodeId: string | undefined;

export function activate(context: vscode.ExtensionContext): void {
  extensionUri = context.extensionUri;
  context.subscriptions.push(
    vscode.commands.registerCommand("dirxmldev.fishbone.show", () => showFishbone()),
    vscode.commands.registerCommand("dirxmldev.fishbone.reveal", () => revealSelected()),
    vscode.commands.registerCommand("dirxmldev.fishbone.refresh", () => refreshFishbone(true)),
  );

  const watcher = vscode.workspace.createFileSystemWatcher(
    "**/{driverset.xml,driver.xml,library.xml,*.policy.xml,fishbone.refresh}",
  );
  const reload = () => {
    if (panel) {
      void refreshFishbone(false);
    }
  };
  watcher.onDidChange(reload);
  watcher.onDidCreate(reload);
  watcher.onDidDelete(reload);
  context.subscriptions.push(watcher);
}

export function deactivate(): void {
  panel?.dispose();
}

function idmFor(treeRoot: string): IdmLocation {
  const configured = vscode.workspace.getConfiguration("dirxmldev").get<string>("idmPath");
  const folders = (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
  return findIdm(treeRoot, folders, configured);
}

async function showFishbone(): Promise<void> {
  const picked = await pickDriver();
  if (!picked) {
    return;
  }
  const view = ensurePanel();
  try {
    const model = await loadFishbone(picked.idm, picked.treeRoot, picked.driver);
    current = { ...picked, model };
    selectedNodeId = undefined;
    render(view, model);
    void vscode.commands.executeCommand("setContext", "dirxmldev.fishboneOpen", true);
  } catch (e) {
    void vscode.window.showErrorMessage("Fishbone: " + (e instanceof Error ? e.message : String(e)));
  }
}

async function refreshFishbone(openIfMissing: boolean): Promise<void> {
  if (!current) {
    if (openIfMissing) {
      await showFishbone();
    }
    return;
  }
  try {
    const listing = await listDrivers(current.idm, current.treeRoot);
    if (!listing.drivers.some((d) => d.name === current!.driver)) {
      void vscode.window.showWarningMessage(
        `Driver '${current.driver}' is gone from ${path.basename(current.treeRoot)}. Pick another.`,
      );
      current = undefined;
      await showFishbone();
      return;
    }
    const model = await loadFishbone(current.idm, current.treeRoot, current.driver);
    current = { ...current, model };
    const view = panel ?? ensurePanel();
    render(view, model);
    void view.webview.postMessage({ type: "hint", text: "Reloaded from disk." });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    void vscode.window.showErrorMessage("Fishbone refresh failed: " + msg);
  }
}

async function revealSelected(): Promise<void> {
  if (!current) {
    void vscode.window.showInformationMessage("Open the policy-flow fishbone first.");
    return;
  }
  if (!selectedNodeId) {
    void vscode.window.showInformationMessage("Click a bone or policy on the fishbone first.");
    return;
  }
  await openNode(current.model, selectedNodeId, true);
}

function ensurePanel(): vscode.WebviewPanel {
  if (panel) {
    panel.reveal(vscode.ViewColumn.Beside);
    return panel;
  }
  panel = vscode.window.createWebviewPanel(
    "dirxmldev.fishbone",
    "Policy Flow",
    { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
    {
      enableScripts: true,
      retainContextWhenHidden: true,
      localResourceRoots: [vscode.Uri.joinPath(extensionUri, "media")],
    },
  );
  panel.onDidDispose(() => {
    panel = undefined;
    current = undefined;
    selectedNodeId = undefined;
    void vscode.commands.executeCommand("setContext", "dirxmldev.fishboneOpen", false);
  });
  panel.webview.onDidReceiveMessage(async (msg: { type?: string; nodeId?: string }) => {
    if (!current || !msg.nodeId) {
      return;
    }
    if (msg.type === "select") {
      selectedNodeId = msg.nodeId;
    }
    if (msg.type === "open") {
      await openNode(current.model, msg.nodeId, false);
    }
  });
  return panel;
}

function render(view: vscode.WebviewPanel, model: FishboneModel): void {
  const nonce = Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
  const cssUri = view.webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, "media", "fishbone.css"));
  const csp = [
    `default-src 'none'`,
    `style-src ${view.webview.cspSource}`,
    `script-src 'nonce-${nonce}'`,
  ].join("; ");
  view.title = "Policy Flow — " + model.driver.name;
  view.webview.html = fishboneHtml(model, nonce, csp, cssUri.toString());
}

async function openNode(model: FishboneModel, nodeId: string, fromCommand: boolean): Promise<void> {
  const files = filesForNode(model, nodeId);
  if (files.length === 0) {
    if (fromCommand || nodeId.startsWith("bone:") || nodeId.startsWith("config:")) {
      void vscode.window.showInformationMessage("No policy linked in that set. Linkage lives in driver.xml.");
    }
    return;
  }
  let chosen = files[0]!;
  if (files.length > 1) {
    const pick = await vscode.window.showQuickPick(
      files.map((f) => ({ label: f.ref, description: f.file, f })),
      { title: "Open policy" },
    );
    if (!pick) {
      return;
    }
    chosen = pick.f;
  }
  const abs = path.join(model.treeRoot, chosen.file);
  if (!fs.existsSync(abs)) {
    void vscode.window.showErrorMessage("File missing on disk: " + chosen.file);
    return;
  }
  const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(abs));
  await vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.One, preview: true });
}

async function pickDriver(): Promise<{ treeRoot: string; driver: string; idm: IdmLocation } | undefined> {
  const roots = await discoverTreeRoots();
  if (roots.length === 0) {
    void vscode.window.showErrorMessage(
      "No IDM-as-code tree found (no driverset.xml). Open a tree, or the sample at extensions/dirxmldev-visual/sample-tree.",
    );
    return undefined;
  }

  const active = vscode.window.activeTextEditor?.document.uri.fsPath;
  let treeRoot = active ? roots.find((r) => active === r || active.startsWith(r + path.sep)) : undefined;
  if (!treeRoot && roots.length === 1) {
    treeRoot = roots[0];
  }
  if (!treeRoot) {
    const pick = await vscode.window.showQuickPick(
      roots.map((r) => ({ label: path.basename(r), description: r, r })),
      { title: "IDM-as-code tree" },
    );
    if (!pick) {
      return undefined;
    }
    treeRoot = pick.r;
  }

  const idm = idmFor(treeRoot);
  let listing: DriverListing;
  try {
    listing = await listDrivers(idm, treeRoot);
  } catch (e) {
    void vscode.window.showErrorMessage(
      "Fishbone: cannot run bin/idm (set dirxmldev.idmPath or IDM_HOME to the DirXMLDev checkout). " +
        (e instanceof Error ? e.message : String(e)),
    );
    return undefined;
  }
  let driver: string | undefined;
  if (active && (active === treeRoot || active.startsWith(treeRoot + path.sep))) {
    const dir = driverDirContaining(treeRoot, listing.drivers.map((d) => d.dir), active);
    driver = listing.drivers.find((d) => d.dir === dir)?.name;
  }
  if (!driver && listing.drivers.length === 1) {
    driver = listing.drivers[0]!.name;
  }
  if (!driver) {
    if (listing.drivers.length === 0) {
      void vscode.window.showErrorMessage("driverset.xml lists no readable drivers.");
      return undefined;
    }
    const pick = await vscode.window.showQuickPick(
      listing.drivers.map((d) => ({ label: d.name, description: d.dir, d })),
      { title: "Driver" },
    );
    if (!pick) {
      return undefined;
    }
    driver = pick.d.name;
  }
  return { treeRoot, driver, idm };
}

async function discoverTreeRoots(): Promise<string[]> {
  const found = new Set<string>();
  const active = vscode.window.activeTextEditor?.document.uri.fsPath;
  if (active) {
    const root = findTreeRoot(active);
    if (root) {
      found.add(root);
    }
  }
  if (vscode.workspace.workspaceFolders) {
    for (const folder of vscode.workspace.workspaceFolders) {
      const local = findTreeRoot(folder.uri.fsPath);
      if (local) {
        found.add(local);
      }
      const matches = await vscode.workspace.findFiles(
        new vscode.RelativePattern(folder, `**/${TREE_MANIFEST}`),
        "**/{node_modules,target,out,.git}/**",
        20,
      );
      for (const uri of matches) {
        const root = path.dirname(uri.fsPath);
        if (isTreeRoot(root)) {
          found.add(root);
        }
      }
    }
  }
  return [...found];
}
