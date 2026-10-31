const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

const contractPath = path.resolve(__dirname, "../src/contract.ts");
const extractionPath = path.resolve(__dirname, "../src/ai/ledgerExtraction.ts");
const ledgerProviderPath = path.resolve(__dirname, "../src/ai/geminiLedgerProvider.ts");

const originalContract = fs.readFileSync(contractPath, "utf8");
const originalExtraction = fs.readFileSync(extractionPath, "utf8");
const originalLedgerProvider = fs.readFileSync(ledgerProviderPath, "utf8");

function runVitestJson(args) {
  const cmd = `npx vitest run ${args} --reporter=json`;
  let stdout = "";
  let exitCode = 0;
  try {
    stdout = execSync(cmd, {
      cwd: path.resolve(__dirname, ".."),
      encoding: "utf8",
      stdio: ["pipe", "pipe", "pipe"],
    });
  } catch (err) {
    exitCode = err.status !== undefined ? err.status : 1;
    stdout = (err.stdout || "") + "\n" + (err.stderr || "");
  }

  const jsonStart = stdout.indexOf("{");
  const jsonEnd = stdout.lastIndexOf("}");
  if (jsonStart === -1 || jsonEnd === -1 || jsonEnd <= jsonStart) {
    throw new Error(`INFRASTRUCTURE_ERROR: Could not parse vitest JSON output.\nExit code: ${exitCode}\nOutput:\n${stdout}`);
  }

  const jsonRaw = stdout.slice(jsonStart, jsonEnd + 1);
  let parsed;
  try {
    parsed = JSON.parse(jsonRaw);
  } catch (parseErr) {
    throw new Error(`INFRASTRUCTURE_ERROR: Failed to JSON.parse vitest output: ${parseErr.message}\nRaw JSON substring:\n${jsonRaw}`);
  }

  return { exitCode, parsed, rawOutput: stdout };
}

function verifyBaseline(description) {
  console.log(`\n=== Checking baseline (${description}) ===`);
  const res = runVitestJson("test/contract.test.ts test/ledgerExtraction.test.ts test/geminiLedgerProvider.test.ts");
  const { parsed } = res;

  // Check test files for top-level compilation or import errors
  if (parsed.testResults) {
    for (const suite of parsed.testResults) {
      if (suite.status === "failed" && suite.message) {
        throw new Error(`BASELINE_FAILED: Suite ${suite.name} failed to load/compile: ${suite.message}`);
      }
    }
  }

  if (parsed.numFailedTests > 0 || parsed.numTotalTests === 0 || !parsed.success) {
    throw new Error(`BASELINE_FAILED: Expected green baseline (${description}), but got numFailedTests=${parsed.numFailedTests}, numTotalTests=${parsed.numTotalTests}`);
  }

  console.log(`Baseline GREEN: ${parsed.numTotalTests} tests passed cleanly.`);
}

function verifyMutation(name, file, targetPattern, replacement, testFile, expectedTestSubstring) {
  console.log(`\n--- Verifying Mutation: ${name} ---`);
  const content = fs.readFileSync(file, "utf8");
  const regex = typeof targetPattern === "string" ? new RegExp(escapeRegex(targetPattern), "g") : targetPattern;
  const match = content.match(regex);
  const occurrencesBefore = match ? match.length : 0;
  if (occurrencesBefore === 0) {
    throw new Error(`ABORT: Target pattern not found in ${file} (0 occurrences before replacement)!`);
  }
  console.log(`Found ${occurrencesBefore} occurrence(s) before replacement.`);

  const mutatedContent = content.replace(regex, replacement);
  const matchAfter = mutatedContent.match(regex);
  const occurrencesAfter = matchAfter ? matchAfter.length : 0;
  console.log(`Remaining occurrences after replacement: ${occurrencesAfter}`);
  if (occurrencesBefore === occurrencesAfter) {
    throw new Error(`ABORT: Replacement failed, occurrences did not decrease in ${file}!`);
  }

  fs.writeFileSync(file, mutatedContent, "utf8");

  try {
    // Run vitest filtered by test pattern
    const res = runVitestJson(`${testFile} -t "${expectedTestSubstring}"`);
    const { parsed } = res;

    // Check if test file failed to compile/load
    if (parsed.testResults) {
      for (const suite of parsed.testResults) {
        if (suite.status === "failed" && suite.message) {
          throw new Error(`ABORT_COMPILATION_ERROR: Suite failed to load/compile under mutation "${name}":\n${suite.message}`);
        }
      }
    }

    // Require that tests actually matched and executed
    if (!parsed.numTotalTests || parsed.numTotalTests === 0) {
      throw new Error(`ABORT_FILTER_EMPTY: Filter -t "${expectedTestSubstring}" did not match any tests in ${testFile}!`);
    }

    if (parsed.numFailedTests === 0) {
      throw new Error(`FAILURE: Tests unexpectedly passed when guard "${name}" was neutralized! (numTotalTests=${parsed.numTotalTests}, numFailedTests=0)`);
    }

    // Require assertion failure matching expectedTestSubstring
    const failedAssertions = [];
    if (parsed.testResults) {
      for (const suite of parsed.testResults) {
        for (const test of (suite.assertionResults || [])) {
          if (test.status === "failed") {
            failedAssertions.push(test);
          }
        }
      }
    }

    const matchingFailure = failedAssertions.find(
      (t) => (t.title && t.title.includes(expectedTestSubstring)) ||
             (t.fullName && t.fullName.includes(expectedTestSubstring))
    );

    if (!matchingFailure) {
      const titles = failedAssertions.map((t) => t.fullName || t.title).join(", ");
      throw new Error(`ABORT_UNEXPECTED_FAILURE: Tests failed under mutation "${name}", but none matched expected substring "${expectedTestSubstring}". Failed tests: [${titles}]`);
    }

    console.log(`SUCCESS: Expected test failure confirmed for guard "${name}".`);
    console.log(`  Failed test: "${matchingFailure.fullName || matchingFailure.title}"`);
    if (matchingFailure.failureMessages && matchingFailure.failureMessages.length > 0) {
      const firstLine = matchingFailure.failureMessages[0].split("\n")[0];
      console.log(`  Assertion message: ${firstLine}`);
    }
  } finally {
    fs.writeFileSync(file, content, "utf8");
  }
}

function escapeRegex(string) {
  return string.replace(/[/\-\\^$*+?.()|[\]{}]/g, "\\$&");
}

let failed = false;

try {
  // Step 1: Initial green baseline
  verifyBaseline("Initial");

  // Step 2: Neutralize money number rejection: allow numbers as amountCents
  verifyMutation(
    "Reject JSON number as money",
    extractionPath,
    'if (typeof rawAmount !== "string" || !CANONICAL_CENTS_RE.test(rawAmount))',
    'if (false && (typeof rawAmount !== "string" || !CANONICAL_CENTS_RE.test(rawAmount)))',
    "test/ledgerExtraction.test.ts",
    "money is never a JSON number"
  );

  // Step 3: Neutralize MAX_LEDGER_OPERATIONS limit: raise it from 25 to 50
  verifyMutation(
    "Cap of 25 operations (MAX_LEDGER_OPERATIONS)",
    extractionPath,
    "if (rawOperations.length > MAX_LEDGER_OPERATIONS)",
    "if (rawOperations.length > 50)",
    "test/ledgerExtraction.test.ts",
    "rejects document when operations exceed MAX_LEDGER_OPERATIONS"
  );

  // Step 4: Neutralize whitelist of keys in ledger operation
  verifyMutation(
    "Whitelist of operation keys",
    extractionPath,
    "if (!hasOnlyKeys(value, OPERATION_ALLOWED_KEYS))",
    "if (false && !hasOnlyKeys(value, OPERATION_ALLOWED_KEYS))",
    "test/ledgerExtraction.test.ts",
    "discards operation with unwhitelisted key"
  );

  // Step 5: Neutralize contract v3 allow-list: remove key count check
  verifyMutation(
    "Whitelist of v3 contract request keys",
    contractPath,
    "ownNames.length !== EXTRACT_LEDGER_KEYS.length",
    "false && ownNames.length !== 0",
    "test/contract.test.ts",
    "validateExtractLedgerPageRequest"
  );

  // Step 6 (Fase B Paso 2): Neutralize token budget calculation: reduce MAX_LEDGER_OUTPUT_TOKENS to 512
  verifyMutation(
    "Ledger output token budget (MAX_LEDGER_OUTPUT_TOKENS >= worst-case)",
    ledgerProviderPath,
    "export const MAX_LEDGER_OUTPUT_TOKENS = 8192;",
    "export const MAX_LEDGER_OUTPUT_TOKENS = 512;",
    "test/geminiLedgerProvider.test.ts",
    "MAX_LEDGER_OUTPUT_TOKENS strictly exceeds 512 and is sufficient"
  );

  // Step 7 (Fase B Paso 2): Neutralize multimodal image part: remove inlineData mimeType
  verifyMutation(
    "Pass image as inlineData with mimeType to Gemini",
    ledgerProviderPath,
    "mimeType: input.imageMimeType,",
    "mimeType: \"text/plain\",",
    "test/geminiLedgerProvider.test.ts",
    "passes image as inlineData with mimeType"
  );

  // Step 8: Post-mutation restoration green baseline
  verifyBaseline("Post-restoration");

  console.log("\n============================================================");
  console.log(">>> ALL 6 HONESTY MUTATION TESTS PASSED (EVIDENCE CONFIRMED) <<<");
  console.log("============================================================");
} catch (e) {
  console.error("Honesty test failed:", e.message || e);
  failed = true;
} finally {
  fs.writeFileSync(contractPath, originalContract, "utf8");
  fs.writeFileSync(extractionPath, originalExtraction, "utf8");
  fs.writeFileSync(ledgerProviderPath, originalLedgerProvider, "utf8");
  if (failed) {
    process.exit(1);
  }
}
