import { createHttpTransport } from "./tools.js";

const CONTEXT_SOURCES = ["GIT", "SLACK", "NOTION", "JIRA", "LINEAR", "DATADOG", "ZOHO", "GOOGLE_CHAT"];

function text(value) {
  return { content: [{ type: "text", text: value }] };
}

function normalizeSources(requested) {
  if (!requested?.length) return CONTEXT_SOURCES;
  return requested.filter((source) => CONTEXT_SOURCES.includes(source));
}

function contextQuery(args) {
  const parts = [args.query?.trim()].filter(Boolean);
  if (args.file_path) parts.push(`Current code file: ${args.file_path}`);
  if (args.symbol) parts.push(`Current symbol: ${args.symbol}`);
  if (args.project) parts.push(`Current project: ${args.project}`);
  parts.push("Do not retrieve or rely on source-code chunks. Return organizational context only: decisions, requirements, issues, discussions, documentation, ownership, git history, external-system records, and rationale relevant to the request.");
  return parts.join("\n");
}

export async function fetchAndroContext({ query, file_path, symbol, project, service, transport, maxChars = 6000 } = {}) {
  const t = transport || createHttpTransport();
  const svc = service || process.env.ANDRO_SERVICE || "local";
  const data = await t.request("POST", "/api/v1/investigate", {
    service: svc,
    query: contextQuery({ query, file_path, symbol, project }),
    sourceTypes: CONTEXT_SOURCES,
  });
  const body = [
    data.summary || "(no relevant context found)",
    data.details ? "## Evidence\n" + data.details : "",
    "## Boundary\nCODE retrieval is intentionally excluded. Use Atlas for repository source, symbols, and code relationships.",
  ].filter(Boolean).join("\n\n");
  return body.slice(0, maxChars);
}

export async function diagnoseAndro({ query, service, sourceTypes, transport } = {}) {
  const t = transport || createHttpTransport();
  const svc = service || process.env.ANDRO_SERVICE || "local";
  const requestedSources = normalizeSources(sourceTypes);
  const result = {
    service: svc,
    api: { reachable: false, error: null },
    index: null,
    sources: {},
    search: null,
    investigation: null,
  };

  try {
    const overview = await t.request(
      "GET",
      `/api/v1/index/services/${encodeURIComponent(svc)}/overview`,
    );
    result.api.reachable = true;
    result.index = {
      exists: overview.exists,
      totalChunks: overview.totalChunks,
      sourceTypes: overview.sourceTypes || [],
      lastSyncAt: overview.lastSyncAt || null,
      indexSizeBytes: overview.indexSizeBytes || 0,
    };
    for (const source of requestedSources) {
      result.sources[source] = {
        indexed: Array.isArray(overview.sourceTypes) && overview.sourceTypes.includes(source),
      };
    }
  } catch (error) {
    result.api.error = error instanceof Error ? error.message : String(error);
    return result;
  }

  if (query?.trim()) {
    try {
      const data = await t.request("POST", "/api/v1/search", {
        service: svc,
        query: query.trim(),
        topK: 5,
        hybrid: true,
        sourceTypes: requestedSources,
      });
      const hits = Array.isArray(data.hits) ? data.hits : [];
      result.search = {
        ok: true,
        hitCount: hits.length,
        hits: hits.slice(0, 5).map((hit) => ({
          sourceType: hit.sourceType || hit.source || "UNKNOWN",
          path: hit.filePath || hit.path || hit.fileName || hit.title || "(untitled)",
          content: hit.content ? String(hit.content).slice(0, 800) : "",
        })),
      };
    } catch (error) {
      result.search = {
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }

    try {
      const data = await t.request("POST", "/api/v1/investigate", {
        service: svc,
        query: query.trim(),
        sourceTypes: requestedSources,
      });
      result.investigation = {
        ok: true,
        summary: data.summary || "",
        details: data.details ? String(data.details).slice(0, 2000) : "",
      };
    } catch (error) {
      result.investigation = {
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  }

  return result;
}

export function registerAndroContextTools(server, z, { transport, defaultService } = {}) {
  const t = transport || createHttpTransport();
  const svc = defaultService || process.env.ANDRO_SERVICE || "local";

  server.registerTool("andro_context", {
    description: "Retrieve organizational context for the current coding task from team/project sources. CODE is intentionally excluded; use Atlas for repository code.",
    inputSchema: {
      query: z.string().describe("What context is needed for the current task"),
      service: z.string().optional().describe("Andro service/workspace"),
      file_path: z.string().optional().describe("Current code file, used only as contextual metadata"),
      symbol: z.string().optional().describe("Current code symbol, used only as contextual metadata"),
      project: z.string().optional().describe("Current project/repository name"),
      source_types: z.array(z.enum(CONTEXT_SOURCES)).optional().describe("Optional context-source filter"),
    },
  }, async (args) => {
    const service = args.service || svc;
    const sourceTypes = normalizeSources(args.source_types);
    const data = await t.request("POST", "/api/v1/investigate", {
      service,
      query: contextQuery(args),
      sourceTypes,
    });
    return text([
      "# Andro Context", "", `Service: ${service}`, `Sources: ${sourceTypes.join(", ")}`, "",
      data.summary || "(no relevant context found)", "", "## Evidence",
      data.details || "(no evidence details returned)", "", "## Boundary",
      "CODE retrieval is intentionally excluded. Use Tempest Atlas for repository source, symbols, and code relationships.",
    ].join("\n"));
  });

  server.registerTool("andro_diagnose", {
    description: "Diagnose Andromedia connectivity, index coverage, and real retrieval for Slack/Jira or other organizational sources. Use this when context appears unavailable or a connector may be indexed but unreachable.",
    inputSchema: {
      query: z.string().optional().describe("Optional real query to test against the indexed sources"),
      service: z.string().optional().describe("Andro service/workspace"),
      source_types: z.array(z.enum(CONTEXT_SOURCES)).optional().describe("Sources to inspect and query"),
    },
  }, async (args) => {
    const result = await diagnoseAndro({
      query: args.query,
      service: args.service || svc,
      sourceTypes: args.source_types,
      transport: t,
    });
    return text([
      "# Andro Diagnostic",
      "",
      `Service: ${result.service}`,
      `API reachable: ${result.api.reachable ? "YES" : "NO"}`,
      result.api.error ? `API error: ${result.api.error}` : "",
      "",
      "## Index",
      result.index
        ? JSON.stringify(result.index, null, 2)
        : "(index overview unavailable)",
      "",
      "## Source coverage",
      JSON.stringify(result.sources, null, 2),
      "",
      "## Search test",
      result.search ? JSON.stringify(result.search, null, 2) : "(not requested)",
      "",
      "## Investigation test",
      result.investigation ? JSON.stringify(result.investigation, null, 2) : "(not requested)",
    ].filter(Boolean).join("\n"));
  });

  server.registerTool("andro_context_search", {
    description: "Search Andro's non-code organizational knowledge. CODE is intentionally excluded because Tempest Atlas owns repository code search.",
    inputSchema: {
      query: z.string().describe("Context search query"),
      service: z.string().optional().describe("Andro service/workspace"),
      source_types: z.array(z.enum(CONTEXT_SOURCES)).optional(),
      top_k: z.number().optional().describe("Maximum evidence items"),
    },
  }, async (args) => {
    const service = args.service || svc;
    const sourceTypes = normalizeSources(args.source_types);
    const data = await t.request("POST", "/api/v1/search", {
      service, query: args.query, topK: args.top_k || 10, hybrid: true, sourceTypes,
    });
    const hits = Array.isArray(data.hits) ? data.hits : [];
    const lines = [`# Andro Context Search — "${args.query}"`, "", `Sources: ${sourceTypes.join(", ")}`, ""];
    if (!hits.length) lines.push("No organizational context found.");
    for (const [index, hit] of hits.entries()) {
      lines.push(`### ${index + 1}. [${hit.sourceType || "UNKNOWN"}] ${hit.filePath || hit.path || hit.fileName || hit.title || "(untitled)"}`);
      if (hit.commitSha) lines.push(`Commit: ${String(hit.commitSha).slice(0, 12)}`);
      if (hit.timestamp) lines.push(`Date: ${String(hit.timestamp).slice(0, 10)}`);
      if (hit.content) lines.push(String(hit.content).slice(0, 1200));
      lines.push("");
    }
    lines.push("---", "CODE retrieval is intentionally excluded. Use Tempest Atlas for repository code.");
    return text(lines.join("\n"));
  });
}
