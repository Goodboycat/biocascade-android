const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(userId, now = new Date(Date.now() - 1000)) {
  return {
    userId,
    label: "Primary Adult Baseline",
    ageYears: 32,
    biologicalSex: "MALE",
    weightKg: 72.5,
    heightCm: 175.0,
    serumCreatinineMgDl: 0.95,
    restingHeartRateBpm: 68,
    restingSystolicMmHg: 118,
    restingDiastolicMmHg: 76,
    createdAt: now,
    updatedAt: now,
  };
}

function validRecordPayload(userId, now = new Date(Date.now() - 1000)) {
  return {
    userId,
    profileLabel: "Primary Adult Baseline",
    stimulusSummary: "Glucose +120 mg/dL bolus",
    simElapsedSeconds: 420.5,
    glucoseMgDl: 215.4,
    insulinUiMl: 44.2,
    sodiumMeqL: 139.5,
    potassiumMeqL: 4.1,
    heartRateBpm: 84,
    systolicBp: 126,
    diastolicBp: 80,
    egfrMlMin: 108.4,
    spO2Pct: 98.2,
    bloodPh: 7.39,
    criticalCount: 1,
    encryptedPayloadHash: "a1b2c3d4e5f600112233445566778899aabbccddeeff",
    createdAt: now,
    updatedAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write profiles, records, or auditLogs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("profiles").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("profiles").doc("prof_1").set(validProfilePayload(ALICE_UID))
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("records").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("auditLogs").get());
});

test("Authenticated owner: valid profile create, partial update, list, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profRef = aliceDb.collection("users").doc(ALICE_UID).collection("profiles").doc("prof_1");

  await assertSucceeds(profRef.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("profiles").get());

  await assertSucceeds(
    profRef.update({
      label: "Updated Athlete Baseline",
      weightKg: 74.0,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    profRef.update({
      createdAt: new Date(Date.now() - 200),
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Reject @DocumentId 'id' leakage or ghost field on fresh create
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("profiles").doc("prof_2").set({
      ...validProfilePayload(ALICE_UID),
      id: "prof_2",
    })
  );

  await assertSucceeds(profRef.delete());
});

test("Authenticated owner: valid simulation record & immutable audit log succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const recRef = aliceDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_1");
  const logRef = aliceDb.collection("users").doc(ALICE_UID).collection("auditLogs").doc("log_1");

  await assertSucceeds(recRef.set(validRecordPayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("records").get());

  // Audit log create succeeds, update/delete denied
  await assertSucceeds(
    logRef.set({
      userId: ALICE_UID,
      action: "PDF_EXPORTED",
      detail: "Encrypted clinical PDF report exported.",
      createdAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(logRef.update({ detail: "Tampered" }));
  await assertFails(logRef.delete());

  // Reject future timestamp tampering
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_future").set(
      validRecordPayload(ALICE_UID, new Date(Date.now() + 60000))
    )
  );
});

test("Cross-user isolation: Bob cannot read or mutate Alice's clinical data", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("users").doc(ALICE_UID).collection("profiles").doc("prof_a").set(validProfilePayload(ALICE_UID));
    await adminDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_a").set(validRecordPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("profiles").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("profiles").doc("prof_a").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_a").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_bob").set(validRecordPayload(BOB_UID))
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("profiles").doc("prof_a").delete());
});
