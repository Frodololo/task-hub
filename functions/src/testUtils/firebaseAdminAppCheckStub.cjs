// Stub de test para `firebase-admin/app-check` — mismo motivo que
// `firebaseAdminAuthStub.cjs`: `firebase-functions/v2/https` lo importa a
// nivel de fichero solo para `getAppCheck(...).verifyToken(...)` dentro del
// código de verificación del protocolo HTTP callable real, un camino que
// `<funcion>.run(callableRequest)` (usado por los tests de integración) no
// ejercita nunca.
module.exports = {
  getAppCheck() {
    throw new Error(
      "firebase-admin/app-check stub: no debería invocarse en tests de integración (ver KDoc de este fichero)"
    );
  }
};
