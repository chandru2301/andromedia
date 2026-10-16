/**
 * Shared Andro tool definitions.
 *
 * Tools are transport-pluggable: the same schemas + response formatting run
 *   - locally  (stdio / hosted proxy)  → HTTP straight to the Andromedia API
 *   - remotely (relay)                 → HTTP forwarded over a WebSocket to the
 *                                        desktop agent, which calls the user's
 *                                        own localhost API.
 *
 * A transport is just `{ request(method, path, body) -> json }`.
 */

import { randomUUID } from "node:crypto";
import { emitUiEvent } from "./ui-events.js";

const ANDRO_BASE = (process.env.ANDRO_BASE_URL || "http://localhost:8083").replace(/\/$/, "");
const DEFAULT_SERVICE = process.env.ANDRO_SERVICE || "local";

function eventKind(path) {
  if (path.includes("/search")) return "search";
  if (path.includes("/investigate")) return "thinking";
  if (path.includes("/index/")) return "read";
  return "command";
}

export function androBaseUrl() {
  return ANDRO_BASE;
}

/** Direct HTTP transport — this process can reach the Andromedia API itself. */
export function createHttpTransport({ baseUrl = ANDRO_BASE, trialId, accessToken } = {}) {
  const base = baseUrl.replace(/\/$/, "");
  return {
    async request(method, path, body) {
      const eventId = randomUUID();
      const kind = eventKind(path);
      const started = Date.now();
      emitUiEvent({ id: eventId, type: "operation.started", kind, status: "running", title: `${method} ${path}`, detail: "Andro backend operation" });
      const headers = { "Content-Type": "application/json" };
      const tid = trialId ?? process.env.ANDRO_TRIAL_ID?.trim();
      const token = accessToken ?? process.env.ANDRO_ACCESS_TOKEN?.trim();
      if (tid) {
        headers["X-Trial-Id"] = tid;
      }
      if (token) {
        headers.Authorization = `Bearer ${token}`;
      }
      const res = await fetch(`${base}${path}`, {
        method,
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!res.ok) {
        const bodyText = await res.text();
        emitUiEvent({ id: eventId, type: "operation.failed", kind, status: "error", title: `${method} ${path}`, detail: `HTTP ${res.status}` });
        throw new Error(`Backend returned ${res.status}: ${bodyText}`);
      }
      const result = await res.json();
      emitUiEvent({ id: eventId, type: "operation.completed", kind, status: "complete", title: `${method} ${path}`, detail: `completed in ${Date.now() - started}ms` });
      return result;
    },
  };
}

const defaultTransport = createHttpTransport();

function text(body) {
  return { content: [{ type: "text", text: body }] };
}

/* ------------------------------------------------------------------ */
/* Handlers                                                            */
/* ------------------------------------------------------------------ */

export async function handleInvestigate(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const service = args.service || fallbackService;
  const data = await t.request("POST", "/api/v1/investigate", {
    service,
    query: args.query,
    ...(args.source_types?.length ? { sourceTypes: args.source_types } : {}),
  });

  return text(
    [
      `## Andro — ${args.query}`,
      "",
      data.summary || "(no summary)",
      "",
      "---",
      "**Sources:**",
      data.details || "(no details)",
    ].join("\n"),
  );
}

async function searchHits(args, t, fallbackService) {
  const service = args.service || fallbackService;
  const data = await t.request("POST", "/api/v1/search", {
    service,
    query: args.query,
    topK: args.top_k || 10,
    hybrid: true,
    ...(args.source_types?.length ? { sourceTypes: args.source_types } : {}),
  });
  return Array.isArray(data.hits) ? data.hits : [];
}

function hitLabel(hit) {
  return hit.filePath || hit.path || hit.fileName || hit.title || "(untitled)";
}

export async function handleSearch(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const hits = await searchHits(args, t, fallbackService);
  const lines = [`## Search results — "${args.query}"`, ""];

  if (hits.length === 0) {
    lines.push("No results found.");
  } else {
    hits.forEach((hit, i) => {
      const source = hit.sourceType || "CODE";
      const score =
        typeof hit.score === "number" ? hit.score.toFixed(4) : hit.score || "";
      const sha = hit.commitSha ? ` · ${String(hit.commitSha).slice(0, 7)}` : "";
      const ts = hit.timestamp ? ` · ${String(hit.timestamp).slice(0, 10)}` : "";
      lines.push(`### ${i + 1}. [${source}] ${hitLabel(hit)}${sha}${ts} (score: ${score})`);
      if (hit.content) {
        const preview = String(hit.content).slice(0, 400);
        lines.push("```");
        lines.push(preview + (String(hit.content).length > 400 ? "..." : ""));
        lines.push("```");
      }
      lines.push("");
    });
  }

  return text(lines.join("\n"));
}

export async function handleListSources(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const service = args.service || fallbackService;
  const data = await t.request(
    "GET",
    `/api/v1/index/services/${encodeURIComponent(service)}/overview`,
  );

  const lines = [
    `## Andro sources — service: ${service}`,
    "",
    `**Index path:** ${data.indexPath || "unknown"}`,
    `**Exists:** ${data.exists ?? false}`,
    `**Total chunks:** ${data.totalChunks ?? 0}`,
    `**Sources connected:** ${data.sourcesConnected ?? 0}`,
  ];

  if (data.zohoConnectionKey) {
    lines.push(`**Active Zoho connection:** \`${data.zohoConnectionKey}\``);
  }

  lines.push("", "### Source breakdown");
  const types = Array.isArray(data.sourceTypes) ? data.sourceTypes : [];
  if (types.length === 0) {
    lines.push("- (none indexed yet)");
  } else {
    for (const type of types) {
      lines.push(`- **${type}**`);
    }
  }

  if (data.lastSyncAt) {
    lines.push("", `**Last sync:** ${data.lastSyncAt}`);
  }
  if (data.indexSizeBytes != null) {
    lines.push(`**Index size:** ${data.indexSizeBytes} bytes`);
  }

  return text(lines.join("\n"));
}

export async function handleFileHistory(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const service = args.service || fallbackService;
  const data = await t.request("POST", "/api/v1/investigate", {
    service,
    query: `history of ${args.file_path}`,
    sourceTypes: ["GIT"],
  });

  return text(
    [
      `## File history — ${args.file_path}`,
      "",
      data.summary || "(no history found)",
      "",
      "---",
      data.details || "",
    ].join("\n"),
  );
}

function architectureGraphShape() {
  return JSON.stringify({
    version: 1,
    title: "Architecture",
    summary: "Grounded repository architecture",
    nodes: [
      { id: "stable-id", label: "Payment Service", kind: "service", sourceRefs: [{ path: "src/PaymentService.java", line: 12 }] },
    ],
    edges: [
      { id: "edge-id", from: "stable-id", to: "another-id", label: "publishes", kind: "request|event|dependency|data|unknown" },
    ],
  }, null, 2);
}

export async function handleArchitectureGenerate(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const service = args.service || fallbackService;
  const query = `${args.request}\nIdentify services, modules, entry points, data stores, queues, caches, external systems, and dependency or event relationships. Include source paths and line references.`;
  const data = await t.request("POST", "/api/v1/investigate", {
    service,
    query,
    ...(args.source_types?.length ? { sourceTypes: args.source_types } : {}),
  });
  return text([
    "# Andro architecture context",
    `Service: ${service}`,
    "",
    "Use only grounded evidence below. Return ArchitectureGraph JSON matching this shape. Mark uncertain relationships instead of inventing them.",
    "",
    "## ArchitectureGraph shape",
    "```json",
    architectureGraphShape(),
    "```",
    "",
    "## Grounded evidence",
    data.summary || "(no summary)",
    "",
    data.details || "(no details)",
  ].join("\n"));
}

function graphToOperationSet(graph, projectId) {
  const nodes = Array.isArray(graph.nodes) ? graph.nodes : [];
  const edges = Array.isArray(graph.edges) ? graph.edges : [];
  const columns = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  const ids = new Map();
  const operations = nodes.map((node, index) => {
    const elementId = `andraw-${node.id}`;
    ids.set(node.id, elementId);
    return {
      type: "create_element",
      element: {
        id: elementId,
        kind: "rectangle",
        label: node.label || node.id,
        x: 180 + (index % columns) * 280,
        y: 160 + Math.floor(index / columns) * 170,
        width: 210,
        height: 90,
      },
    };
  });
  for (const edge of edges) {
    const fromElementId = ids.get(edge.from);
    const toElementId = ids.get(edge.to);
    if (fromElementId && toElementId) {
      operations.push({ type: "connect_elements", fromElementId, toElementId, label: edge.label });
    }
  }
  return {
    id: randomUUID(),
    projectId,
    graph,
    operations,
    summary: {
      creates: nodes.length,
      updates: 0,
      deletes: 0,
      connections: operations.filter(operation => operation.type === "connect_elements").length,
    },
  };
}

export async function handleCanvasPreviewOperations(args) {
  let graph;
  try {
    graph = JSON.parse(args.graph);
  } catch {
    throw new Error("graph must be valid JSON");
  }
  if (graph?.version !== 1 || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) {
    throw new Error("graph must contain version: 1, nodes[], and edges[]");
  }
  if (graph.nodes.length > 500 || graph.edges.length > 1000) {
    throw new Error("graph exceeds the safe operation limits");
  }
  const operationSet = graphToOperationSet(graph, args.project_id);
  return text(JSON.stringify({
    ok: true,
    operationSet,
    relay: "Preview generated. Apply it through the connected Andraw Canvas Copilot.",
  }, null, 2));
}

/**
 * ChatGPT deep-research connectors expect a `search` tool returning a list of
 * {id, title, url} plus a `fetch` tool that resolves one id to full text.
 * These wrap the same hybrid search so one connector URL serves both vendors.
 */
export async function handleChatGptSearch(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const hits = await searchHits({ ...args, top_k: args.top_k || 10 }, t, fallbackService);
  const results = hits.map((hit, i) => ({
    id: encodeURIComponent(hitLabel(hit)) + `#${i}`,
    title: `[${hit.sourceType || "CODE"}] ${hitLabel(hit)}`,
    text: String(hit.content || "").slice(0, 400),
    url: hit.url || null,
  }));
  return { content: [{ type: "text", text: JSON.stringify({ results }) }] };
}

export async function handleChatGptFetch(args, t = defaultTransport, fallbackService = DEFAULT_SERVICE) {
  const label = decodeURIComponent(String(args.id).split("#")[0]);
  const hits = await searchHits({ query: label, top_k: 5 }, t, fallbackService);
  const hit = hits.find((h) => hitLabel(h) === label) || hits[0];
  const doc = {
    id: args.id,
    title: hit ? `[${hit.sourceType || "CODE"}] ${hitLabel(hit)}` : label,
    text: hit ? String(hit.content || "") : "Not found in the current index.",
    url: hit?.url || null,
    metadata: hit ? { sourceType: hit.sourceType, score: hit.score } : {},
  };
  return { content: [{ type: "text", text: JSON.stringify(doc) }] };
}

const SOURCE_ENUM = ["CODE", "GIT", "SLACK", "NOTION", "JIRA", "LINEAR", "DATADOG", "ZOHO", "GOOGLE_CHAT"];

/**
 * Register every Andro tool on an McpServer.
 *
 * @param opts.transport       transport to run tool HTTP through (default: direct HTTP)
 * @param opts.defaultService  service key when the caller omits one
 * @param opts.chatgptTools    also expose `search` / `fetch` (ChatGPT connectors)
 */
export function registerAndroTools(server, z, opts = {}) {
  const t = opts.transport || defaultTransport;
  const svc = opts.defaultService || DEFAULT_SERVICE;
  const chatgptTools = opts.chatgptTools !== false;
  const emitEvent = opts.emitEvent || (() => {});
  const observed = (name, kind, handler) => async (args) => {
    const id = `tool-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    emitEvent({ id, kind, status: "running", title: name, detail: args?.query || args?.file_path || args?.service || "tool call", tool: name });
    const started = Date.now();
    try { const result = await handler(args); emitEvent({ id, kind, status: "complete", title: name, detail: `completed in ${Date.now()-started}ms`, tool: name }); return result; }
    catch (error) { emitEvent({ id, kind, status: "error", title: name, detail: String(error?.message || error), tool: name }); throw error; }
  };

  server.registerTool(
    "andro_investigate",
    {
      description:
        "Ask Andro a grounded decision question over indexed CRM and team sources " +
        "(ZOHO, CODE, GIT, SLACK, etc.). Prefer this for follow-up / risk / prioritize questions.",
      inputSchema: {
        query: z.string().describe("Your question in plain English"),
        service: z.string().optional().describe("Andro service/workspace (default from env)"),
        source_types: z
          .array(z.enum(SOURCE_ENUM))
          .optional()
          .describe("Optional source filter"),
      },
    },
    observed("andro_investigate", "thinking", async (args) => handleInvestigate(args, t, svc)),
  );

  server.registerTool(
    "architecture_generate",
    {
      description:
        "Prepare grounded Andro repository context for an editable Andraw architecture diagram. " +
        "Use the returned evidence to produce ArchitectureGraph JSON before calling canvas_preview_operations.",
      inputSchema: {
        request: z.string().describe("Architecture question or diagram scope"),
        service: z.string().optional().describe("Andro service/workspace"),
        source_types: z.array(z.enum(SOURCE_ENUM)).optional().describe("Optional source filter"),
      },
    },
    observed("architecture_generate", "thinking", async (args) => handleArchitectureGenerate(args, t, svc)),
  );

  server.registerTool(
    "canvas_preview_operations",
    {
      description:
        "Validate ArchitectureGraph JSON and compile it into a deterministic, editable Andraw operation preview. " +
        "This tool never mutates the canvas.",
      inputSchema: {
        graph: z.string().describe("JSON encoded ArchitectureGraph from architecture_generate"),
        project_id: z.string().optional().describe("Active Tempest project ID"),
      },
    },
    observed("canvas_preview_operations", "command", async (args) => handleCanvasPreviewOperations(args)),
  );

  server.registerTool(
    "andro_search",
    {
      description:
        "Hybrid BM25 + vector search over Andro's knowledge index. Returns evidence snippets.",
      inputSchema: {
        query: z.string().describe("Search query"),
        service: z.string().optional().describe("Andro service name"),
        source_types: z.array(z.enum(SOURCE_ENUM)).optional(),
        top_k: z.number().optional().describe("Number of results (default 10)"),
      },
    },
    observed("andro_search", "search", async (args) => handleSearch(args, t, svc)),
  );

  server.registerTool(
    "andro_list_sources",
    {
      description:
        "List indexed sources for a service — chunk counts, source types, last sync.",
      inputSchema: {
        service: z.string().optional().describe("Andro service name"),
      },
    },
    observed("andro_list_sources", "search", async (args) => handleListSources(args, t, svc)),
  );

  // File-history stays unimplemented as a real git log. Do not register it by default —
  // the design-partner corpus has zero GIT/CODE chunks, so agents that call it look broken.
  // Opt in with ANDRO_ENABLE_FILE_HISTORY=1 once GIT history is indexed.
  if (process.env.ANDRO_ENABLE_FILE_HISTORY === "1") {
    server.registerTool(
      "andro_get_file_history",
      {
        description:
          "Heuristic search of indexed GIT commits mentioning a path (via investigate). " +
          "Not a true git log; requires GIT history to be indexed.",
        inputSchema: {
          file_path: z
            .string()
            .describe("Relative file path (e.g. src/PaymentService.java)"),
          service: z.string().optional().describe("Andro service name"),
        },
      },
      observed("andro_get_file_history", "read", async (args) => handleFileHistory(args, t, svc)),
    );
  }

  if (chatgptTools) {
    server.registerTool(
      "search",
      {
        description:
          "Search the connected Andro workspace and return matching documents as " +
          "{results:[{id,title,text,url}]}. Use `fetch` to read one in full.",
        inputSchema: {
          query: z.string().describe("Search query"),
          top_k: z.number().optional(),
        },
      },
      observed("search", "search", async (args) => handleChatGptSearch(args, t, svc)),
    );

    server.registerTool(
      "fetch",
      {
        description: "Retrieve the full text of one Andro document by the id returned from `search`.",
        inputSchema: { id: z.string().describe("Document id from `search`") },
      },
      observed("fetch", "read", async (args) => handleChatGptFetch(args, t, svc)),
    );
  }
}
