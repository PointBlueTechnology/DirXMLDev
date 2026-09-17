import * as vscode from "vscode";
import * as fs from "fs";
import * as path from "path";
import {
  AsCodeTree,
  DriverInfo,
  TREE_MANIFEST,
  driverContaining,
  findTreeRoot,
  loadTree,
} from "./ascode";
import { FishboneModel, filesForNode, loadFishbone } from "./fishbone";
import { fishboneHtml } from "./webview";

let extensionUri: vscode.Uri;
let panel: vscode.WebviewPanel | undefined;
let current: { tree: AsCodeTree; driver: DriverInfo; model: FishboneModel } | undefined;
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

async function showFishbone(): Promise<void> {
  const picked = await pickDriver();
  if (!picked) {
    return;
  }
  current = picked;
  selectedNodeId = undefined;
  const view = ensurePanel();
  render(view, picked.model);
  void vscode.commands.executeCommand("setContext", "dirxmldev.fishboneOpen", true);
}

async function refreshFishbone(openIfMissing: boolean): Promise<void> {
  if (!current) {
    if (openIfMissing) {
      await showFishbone();
    }
    return;
  }
  try {
    const tree = loadTree(current.tree.root);
    const driver = tree.drivers.find((d) => d.name === current!.driver.name);
    if (!driver) {
      void vscode.window.showWarningMessage(
        `Driver '${current.driver.name}' is gone from ${path.basename(tree.root)}. Pick another.`,
      );
      current = undefined;
      await showFishbone();
      return;
    }
    current = { tree, driver, model: loadFishbone(tree, driver) };
    const view = panel ?? ensurePanel();
    render(view, current.model);
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

async function pickDriver(): Promise<{ tree: AsCodeTree; driver: DriverInfo; model: FishboneModel } | undefined> {
  const trees = await discoverTrees();
  if (trees.length === 0) {
    void vscode.window.showErrorMessage(
      "No IDM-as-code tree found (no driverset.xml). Open a tree, or the sample at extensions/dirxmldev-visual/sample-tree.",
    );
    return undefined;
  }

  const active = vscode.window.activeTextEditor?.document.uri.fsPath;
  let tree = active
    ? trees.find((t) => active === t.root || active.startsWith(t.root + path.sep))
    : undefined;
  if (!tree && trees.length === 1) {
    tree = trees[0];
  }
  if (!tree) {
    const pick = await vscode.window.showQuickPick(
      trees.map((t) => ({ label: t.driverSet.name || path.basename(t.root), description: t.root, t })),
      { title: "IDM-as-code tree" },
    );
    if (!pick) {
      return undefined;
    }
    tree = pick.t;
  }

  let driver: DriverInfo | undefined;
  if (active && (active === tree.root || active.startsWith(tree.root + path.sep))) {
    driver = driverContaining(tree, active);
  }
  if (!driver && tree.drivers.length === 1) {
    driver = tree.drivers[0];
  }
  if (!driver) {
    if (tree.drivers.length === 0) {
      void vscode.window.showErrorMessage("driverset.xml lists no readable drivers.");
      return undefined;
    }
    const pick = await vscode.window.showQuickPick(
      tree.drivers.map((d) => ({ label: d.name, description: d.dir, d })),
      { title: "Driver" },
    );
    if (!pick) {
      return undefined;
    }
    driver = pick.d;
  }
  return { tree, driver, model: loadFishbone(tree, driver) };
}

async function discoverTrees(): Promise<AsCodeTree[]> {
  const found = new Map<string, AsCodeTree>();
  const active = vscode.window.activeTextEditor?.document.uri.fsPath;
  if (active) {
    const root = findTreeRoot(active);
    if (root) {
      found.set(root, loadTree(root));
    }
  }
  if (vscode.workspace.workspaceFolders) {
    for (const folder of vscode.workspace.workspaceFolders) {
      const local = findTreeRoot(folder.uri.fsPath);
      if (local && !found.has(local)) {
        found.set(local, loadTree(local));
      }
      const matches = await vscode.workspace.findFiles(
        new vscode.RelativePattern(folder, `**/${TREE_MANIFEST}`),
        "**/{node_modules,target,out,.git}/**",
        20,
      );
      for (const uri of matches) {
        const root = path.dirname(uri.fsPath);
        if (!found.has(root)) {
          found.set(root, loadTree(root));
        }
      }
    }
  }
  return [...found.values()];
}
