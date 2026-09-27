import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

/* global fetch, console */

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputPath = resolve(packageRoot, "openapi/backend-openapi.json");
const sourceUrl = process.env.TARPEISTO_OPENAPI_URL ?? "http://localhost:8080/v3/api-docs";

const response = await fetch(sourceUrl, { headers: { accept: "application/json" } });
if (!response.ok) {
  throw new Error(`Could not fetch ${sourceUrl}: ${response.status} ${response.statusText}`);
}

const document = await response.json();
// Springdoc reflects the port of the temporary backend used for capture. The browser client is
// same-origin in production, so keep the committed contract deterministic and environment-free.
document.servers = [{ url: "/", description: "Same-origin application server" }];
await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(document, null, 2)}\n`, "utf8");

console.log(`Captured ${sourceUrl} in ${outputPath}`);
