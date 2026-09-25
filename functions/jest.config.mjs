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
  testMatch: ["**/src/**/*.test.ts"],
  // Los tests de integración (`*.integration.test.ts`) necesitan el
  // emulador de Firestore levantado — corren aparte, ver
  // `jest.integration.config.mjs` / `npm run test:integration`.
  testPathIgnorePatterns: ["/node_modules/", "\\.integration\\.test\\.ts$"]
};
