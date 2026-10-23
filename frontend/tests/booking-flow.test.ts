import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { BookingFlow, PENDING_BOOKING } from "../src/lib/booking-flow";
import { ApiClientError } from "../src/lib/api-client";
import { selectionFromQuery } from "../src/lib/booking-selection";
import type {
  Quote,
  QuoteRequest,
  BookingCreationReceipt,
  OperationStatus,
} from "../src/lib/types";
const fixtures = Object.fromEntries(
  JSON.parse(readFileSync(new URL("../../docs/api/examples.json", import.meta.url), "utf8")).examples.map(
    (e: { name: string; value: unknown }) => [e.name, e.value],
  ),
);
const quote = fixtures.quote as Quote,
  selection = fixtures.stay as QuoteRequest,
  receipt = fixtures.receipt as BookingCreationReceipt;
const key = "2bd0cbe0-7124-4c38-b189-ddfc4abca777";
function memory() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) || null,
    setItem: (k: string, v: string) => {
      m.set(k, v);
    },
    removeItem: (k: string) => {
      m.delete(k);
    },
  };
}
function setup(storage: ReturnType<typeof memory> | null = memory()) {
  const calls: string[] = [];
  const api = {
    quote: async () => {
      calls.push("quote");
      return quote;
    },
    createBooking: async () => {
      calls.push("create");
      return receipt;
    },
    bookingResult: async (): Promise<OperationStatus> => {
      calls.push("result");
      throw new ApiClientError({
        status: 404,
        code: "NOT_FOUND",
        message: "not found",
      });
    },
    fenceBooking: async (): Promise<OperationStatus> => {
      calls.push("fence");
      return { ...fixtures.failedOperation, key } as OperationStatus;
    },
  };
  const flow = new BookingFlow(
    api,
    selection,
    "7",
    storage,
    () => {},
    () => key,
  );
  return { flow, api, storage, calls };
}
test("selection rejects missing/duplicate/token fields and invalid calendar dates", () => {
  assert.equal(
    selectionFromQuery({
      roomId: "1",
      checkInDate: "2026-02-30",
      checkOutDate: "2026-03-02",
      guests: "2",
    }),
    null,
  );
  assert.equal(
    selectionFromQuery({ ...selection, guests: "2", quoteToken: "secret" }),
    null,
  );
  assert.equal(selectionFromQuery({ ...selection, guests: ["1", "2"] }), null);
  assert.deepEqual(
    selectionFromQuery({ ...selection, guests: "2" }),
    selection,
  );
});
test("explicit agreement and durable key precede the only create; double click is ignored", async () => {
  const { flow, api, storage, calls } = setup();
  await flow.start();
  await flow.confirm(false);
  assert.deepEqual(calls, ["quote"]);
  api.createBooking = async () => {
    assert.equal(JSON.parse(storage!.getItem(PENDING_BOOKING)!).key, key);
    calls.push("create");
    return receipt;
  };
  await Promise.all([flow.confirm(true), flow.confirm(true)]);
  assert.deepEqual(calls, ["quote", "create"]);
  assert.equal(flow.state.phase, "succeeded");
  assert.equal(storage!.getItem(PENDING_BOOKING), null);
});
test("unavailable tab storage prevents dispatching CREATE", async () => {
  const { flow, calls } = setup(null);
  await flow.start();
  await flow.confirm(true);
  assert.deepEqual(calls, ["quote"]);
  assert.equal(flow.state.phase, "error");
});
test("network uncertainty plus 404 never creates a second key or quote; only explicit fence resolves", async () => {
  const { flow, api, calls, storage } = setup();
  api.createBooking = async () => {
    calls.push("create");
    throw new Error("lost response");
  };
  await flow.start();
  await flow.confirm(true);
  assert.equal(flow.state.phase, "unknown");
  await flow.prepare();
  await flow.confirm(true);
  assert.deepEqual(calls, ["quote", "create", "result"]);
  assert.ok(storage!.getItem(PENDING_BOOKING));
  await flow.fence();
  assert.equal(flow.state.phase, "failed");
  assert.equal(storage!.getItem(PENDING_BOOKING), null);
  await flow.prepare();
  assert.equal(flow.state.phase, "ready");
  assert.deepEqual(calls, ["quote", "create", "result", "fence", "quote"]);
});
test("reload resumes original owner/key even if the selection changed", async () => {
  const { flow, storage } = setup();
  storage!.setItem(
    PENDING_BOOKING,
    JSON.stringify({ version: 1, ownerId: "7", key }),
  );
  const calls: string[] = [];
  const api = {
    quote: async () => {
      calls.push("quote");
      return quote;
    },
    createBooking: async () => {
      throw new Error("must not create");
    },
    bookingResult: async (k: string) => {
      calls.push(k);
      return {
        ...fixtures.succeededOperation,
        key,
        receipt,
      } as OperationStatus;
    },
    fenceBooking: async () => {
      throw new Error("must not fence");
    },
  };
  const resumed = new BookingFlow(
    api,
    { ...selection, guests: 1 },
    "7",
    storage,
    () => {},
  );
  await resumed.start();
  assert.deepEqual(calls, [key]);
  assert.equal(resumed.state.phase, "succeeded");
});
test("another owner never looks up an old owner operation", async () => {
  const { flow, storage, calls } = setup();
  storage!.setItem(
    PENDING_BOOKING,
    JSON.stringify({ version: 1, ownerId: "8", key }),
  );
  await flow.start();
  assert.deepEqual(calls, ["quote"]);
  assert.equal(storage!.getItem(PENDING_BOOKING), null);
});
test("processing is not failed by elapsed UI time; fencing a success preserves the receipt", async () => {
  const { flow, api, storage } = setup();
  storage!.setItem(
    PENDING_BOOKING,
    JSON.stringify({ version: 1, ownerId: "7", key }),
  );
  api.bookingResult = async () =>
    ({ ...fixtures.processingOperation, key }) as OperationStatus;
  api.fenceBooking = async () =>
    ({ ...fixtures.succeededOperation, key, receipt }) as OperationStatus;
  await flow.start();
  assert.equal(flow.state.phase, "processing");
  await flow.fence();
  assert.equal(flow.state.phase, "succeeded");
  assert.deepEqual(flow.state.receipt, receipt);
});
test("late commit response after leaving the page retains the recovery key", async () => {
  const { flow, api, storage } = setup();
  let resolve!: (v: BookingCreationReceipt) => void;
  api.createBooking = () =>
    new Promise((r) => {
      resolve = r;
    });
  await flow.start();
  const submitting = flow.confirm(true);
  await new Promise((r) => setTimeout(r, 0));
  flow.dispose();
  resolve(receipt);
  await submitting;
  assert.ok(storage!.getItem(PENDING_BOOKING));
});
test("corrupted recovery context cannot be overwritten with a new create", async () => {
  const { flow, storage, calls } = setup();
  storage!.setItem(PENDING_BOOKING, "broken");
  await flow.start();
  await flow.prepare();
  await flow.confirm(true);
  assert.equal(flow.canPrepare, false);
  assert.deepEqual(calls, []);
});
test("invalid successful receipt preserves the pending key and resolves from the registry", async () => {
  const { flow, api, storage } = setup();
  api.createBooking = async () => ({}) as BookingCreationReceipt;
  await flow.start();
  await flow.confirm(true);
  assert.equal(flow.state.phase, "unknown");
  assert.ok(storage!.getItem(PENDING_BOOKING));
});
