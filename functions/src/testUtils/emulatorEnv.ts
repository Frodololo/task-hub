/**
 * `setupFiles` de `jest.integration.config.mjs` — se ejecuta ANTES de cargar
 * cualquier módulo de test, así que `admin.ts` (que llama a
 * `initializeApp()`/`getFirestore()` en su propio nivel superior, en cuanto
 * algo lo importa) ve `FIRESTORE_EMULATOR_HOST` ya puesto y enruta todas las
 * lecturas/escrituras al emulador en vez de a Firestore real — sin esto, los
 * tests de integración escribirían contra el proyecto real `task-hub-62f98`.
 */
process.env.FIRESTORE_EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8180";
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT ?? "demo-task-hub";
