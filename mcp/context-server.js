#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { createHttpTransport } from "./tools.js";
import { registerAndroContextTools } from "./context-tools.js";

const server = new McpServer({ name: "andro-context", version: "1.0.0" }, { capabilities: { tools: {} } });
registerAndroContextTools(server, z, { transport: createHttpTransport(), defaultService: process.env.ANDRO_SERVICE || "local" });
await server.connect(new StdioServerTransport());
console.error(`Andro Context MCP (stdio) → ${process.env.ANDRO_BASE_URL || "http://localhost:8083"}`);
