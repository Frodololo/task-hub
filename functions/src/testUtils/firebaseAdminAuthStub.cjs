// Stub de test para `firebase-admin/auth` — ver `jest.integration.config.mjs`
// (`moduleNameMapper`). `firebase-functions/v2/https` importa este módulo a
// nivel de fichero solo para `getAuth(...).verifyIdToken(...)` dentro del
// código de verificación de tokens del protocolo HTTP callable real — un
// camino que los tests de integración de este paquete NUNCA ejercitan
// (llaman a `<funcion>.run(callableRequest)` directamente, sin pasar por
// HTTP, ver `testUtils/emulatorHelpers.ts#callAs`).
//
// Sin este stub, `require("firebase-admin/auth")` arrastra
// `jwks-rsa` -> `jose@6` (ESM puro, sin build CJS desde la v6) y Jest no
// soporta `require()` síncrono de ESM real (ver error "Must use import to
// load ES Module" / requiere Node 24.9+ que este proyecto no usa).
module.exports = {
  getAuth() {
    throw new Error("firebase-admin/auth stub: no debería invocarse en tests de integración (ver KDoc de este fichero)");
  }
};
