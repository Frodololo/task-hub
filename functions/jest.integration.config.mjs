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
    // `firebase-admin/auth`/`firebase-admin/app-check` -> stubs: ver KDoc de
    // `testUtils/firebaseAdminAuthStub.cjs` (jwks-rsa -> jose@6 es ESM puro,
    // Jest no puede `require()`lo síncronamente).
    "^firebase-admin/auth$": "<rootDir>/src/testUtils/firebaseAdminAuthStub.cjs",
    "^firebase-admin/app-check$": "<rootDir>/src/testUtils/firebaseAdminAppCheckStub.cjs",
    "^(\\.{1,2}/.*)\\.js$": "$1"
  },
  transform: {
    "^.+\\.ts$": ["ts-jest", { useESM: true }]
  },
  testMatch: ["**/src/**/*.integration.test.ts"],
  setupFiles: ["<rootDir>/src/testUtils/emulatorEnv.ts"],
  testTimeout: 30000
};
