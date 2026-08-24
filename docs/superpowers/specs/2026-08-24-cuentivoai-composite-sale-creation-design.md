# CuentivoAI Composite Sale Creation Design

## Objective

Allow one natural-language request to propose and, only after explicit confirmation, create missing customers and products together with a sale and optional initial payment. This removes the current requirement to pre-register every entity while preserving Cuentivo's deterministic financial authority.

## Scope

This increment supports a `CREATE_SALE` proposal containing any combination of:

- an existing or new customer;
- existing or new products;
- quantities and declared unit prices;
- an optional initial payment;
- an optional promised-payment date already supported by the typed proposal.

It does not add customer phone/address extraction, product cost, stock, category, barcode, automatic inventory changes, billing changes, or a new provider.

## Resolution and clarification

The resolver searches only active rows belonging to the active business.

- One exact match uses the existing entity.
- No match proposes a new entity.
- Similar or multiple candidates produce `requires_clarification`; the user can select an existing entity, explicitly create a new one, or cancel.
- Gemini never chooses a local entity ID and never decides whether a similar name is equivalent.

Selections are bound to the preview generation. A stale selection cannot confirm a newer proposal.

## New entity defaults

A new customer stores only the normalized display name and existing safe domain defaults. CuentivoAI does not invent phone, address, credit limit, notes, or other personal data.

A new product stores:

- the confirmed product name;
- the price declared in the phrase as its habitual sale price, in integer cents;
- existing safe defaults for optional fields.

CuentivoAI does not invent cost, stock, category, barcode, photo, or inventory history. A missing or invalid declared price blocks creation.

## Preview

The preview is the trust boundary and identifies each customer/product as either `EXISTING` or `NEW`. It displays:

- customer resolution;
- every product, quantity, unit price and line total;
- habitual price for each proposed new product;
- deterministic total;
- optional initial payment;
- deterministic remaining amount;
- promised-payment candidate, when valid;
- unresolved ambiguities and blocking errors.

No database mutation occurs while resolving, clarifying, editing, cancelling, or displaying the preview.

## Confirmation and transaction

One explicit confirmation invokes a local deterministic transaction. In order, the transaction:

1. revalidates the active business, current user, confirmation key and source fingerprints;
2. reserves the idempotency operation;
3. revalidates that proposed new normalized names still do not conflict;
4. inserts missing customer and product rows;
5. records the sale, items and charge movement;
6. records the optional initial payment, allocation and credit movement;
7. records audit evidence;
8. commits all rows or rolls back all rows.

After commit, Cuentivo reloads the persisted bundle and enqueues exact local rows for synchronization. A retry recovers missing outbox entries without duplicating financial or catalog rows.

## Safety invariants

- Gemini remains proposal-only and never writes Room, Firestore or outbox rows.
- IDs, normalized names, cents, totals, fingerprints and idempotency keys are controlled locally.
- Duplicate tap/retry creates at most one customer, product, sale and payment for the confirmation key.
- Any stale, ambiguous, invalid, cross-business or overflow condition fails closed.
- Cancellation propagates and never becomes a generic model error.
- Existing manual Free/Pro flows remain unchanged.
- Payment registration must never be blocked merely by a commercial creation limit.
- Cuentivo remains functional when AI, gateway, Gemini or sync is unavailable.

## Error handling

- A name collision discovered at confirmation returns a stale/clarification result and writes nothing.
- A failed insert, financial validation or audit insert rolls back the complete transaction.
- An outbox failure occurs after the local transaction and returns a recoverable unavailable state; retry uses the same confirmation key and persisted rows.
- Unsupported or incomplete proposals remain editable and cannot be confirmed.

## Testing

Required JVM tests cover exact/new/mixed resolution, similar-name clarification, invalid price, overflow, immutable previews, stale generations and cancellation.

Required connected Room tests cover:

- preview and clarification write nothing;
- new customer plus new product plus sale plus payment commit atomically;
- mixed existing/new products;
- habitual product price persistence;
- duplicate tap and retry exactly once;
- normalized-name race rejection;
- rollback at every insertion boundary;
- wrong-business and blank-user rejection;
- exact persisted outbox payloads and partial-enqueue recovery.

The final gate requires full JVM tests, connected financial tests on the authorized phone, `assembleDebug`, `assembleRelease`, `lintDebug`, `git diff --check`, and a manual debug-APK acceptance scenario.

## Manual acceptance scenario

With no pre-existing `Ana` or `Crema`, submit:

`Ana se llevó dos cremas de $120 y pagó $100.`

After clarification of the proposed singular product name, the preview must show a new customer, a new product with habitual price $120, total $240, initial payment $100 and remaining balance $140. Confirmation creates exactly one of each applicable row. A duplicate confirmation does not duplicate anything.
