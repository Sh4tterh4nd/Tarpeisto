// @ts-check
import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import eslintConfigPrettier from "eslint-config-prettier";

/**
 * Boundary rules (policy 6.2):
 * - `apps/web` may only import the public entry point of `@bigcontainers/api-client`
 *   and `@bigcontainers/shared-ui`, never their internal file paths.
 * - Only the data/outbox layer may import `dexie` directly; ordinary feature/UI code
 *   must not touch IndexedDB directly. No such layer exists yet in Phase 0, so the
 *   restriction currently applies everywhere it could be imported from.
 */
const boundaryRestrictedImports = {
  rules: {
    "no-restricted-imports": [
      "error",
      {
        patterns: [
          {
            group: ["@bigcontainers/api-client/*", "!@bigcontainers/api-client"],
            message: "Import only the @bigcontainers/api-client package entry point.",
          },
          {
            group: ["@bigcontainers/shared-ui/*", "!@bigcontainers/shared-ui"],
            message: "Import only the @bigcontainers/shared-ui package entry point.",
          },
          {
            group: ["dexie", "dexie/*"],
            message:
              "Components must not access IndexedDB/Dexie directly; that belongs to the data/outbox layer.",
          },
        ],
      },
    ],
  },
};

export default tseslint.config(
  {
    ignores: [
      "**/dist/**",
      "**/coverage/**",
      "**/playwright-report/**",
      "**/test-results/**",
      "**/.vite/**",
      "**/generated/**",
      "**/node_modules/**",
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2023,
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports", fixStyle: "inline-type-imports" },
      ],
    },
  },
  {
    files: ["apps/web/src/**/*.{ts,tsx}"],
    ...boundaryRestrictedImports,
  },
  {
    files: ["**/*.test.{ts,tsx}", "**/e2e/**/*.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
  eslintConfigPrettier,
);
