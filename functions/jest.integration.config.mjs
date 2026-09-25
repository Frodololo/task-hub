/**
 * Config aparte de `jest.config.mjs`: solo `*.integration.test.ts`, que
 * necesitan el emulador de Firestore levantado (ver
 * `package.json#scripts.test:integration`, que envuelve esto en
 * `firebase emulators:exec`). No se ejecuta con `npm test` a secas.
 */
/** @type {import('jest').Config} */
export default {
  testEnvironment: "node",
  extensionsToTreatAsEsm: [".ts"],
  moduleNameMapper: {
    "^(\\.{1,2}/.*)\\.js$": "$1"
  },
  transform: {
    "^.+\\.ts$": ["ts-jest", { useESM: true }]
  },
  testMatch: ["**/src/**/*.integration.test.ts"],
  setupFiles: ["<rootDir>/src/testUtils/emulatorEnv.ts"],
  testTimeout: 30000
};
