"use strict";

// Release gate for Firebase Functions. This is intentionally a standalone Node script so it runs
// the same way from PowerShell and Git Bash. It never deploys; Firebase's predeploy hook is the
// last compilation guard immediately before a separately authorised deploy.

const { spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const functionsDir = path.resolve(__dirname, "..");
const projectDir = path.resolve(functionsDir, "..");
const nodeBin = path.dirname(process.execPath);
const npm = process.platform === "win32" ? process.execPath : "npm";
const npx = process.platform === "win32" ? process.execPath : "npx";
const npmPrefix = process.platform === "win32"
  ? [path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npm-cli.js")]
  : [];
const npxPrefix = process.platform === "win32"
  ? [path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npx-cli.js")]
  : [];
const jbrBin = process.platform === "win32"
  ? "C:\\Program Files\\Android\\Android Studio\\jbr\\bin"
  : "/c/Program Files/Android/Android Studio/jbr/bin";

const environment = { ...process.env };
const steps = [];

function printSummary() {
  console.log("\n=== Functions release check summary ===");
  for (const step of steps) {
    const count = step.count === undefined ? "" : `; ${step.count}`;
    console.log(`${step.status.toUpperCase()}: ${step.name}${count}`);
  }
}

function countPassedTests(output) {
  const matches = [...output.matchAll(/^\s*Tests\s+(\d+)\s+passed\b/gm)];
  if (matches.length === 0) return "test count unavailable";
  return `${matches[matches.length - 1][1]} tests passed`;
}

function runCommand(name, command, args, options = {}) {
  console.log(`\n=== ${name} ===`);
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? functionsDir,
    env: environment,
    encoding: "utf8",
  });
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  if (output) process.stdout.write(output);

  const step = { name, status: "passed" };
  if (name === "unit tests" || name === "Firestore emulator gate") {
    step.count = countPassedTests(output);
  }
  steps.push(step);

  if (result.error || result.status !== 0) {
    step.status = "failed";
    const detail = result.error?.message ?? `exit code ${result.status ?? "unknown"}`;
    throw new Error(`${name} failed (${detail}).`);
  }
}

function cleanAndBuild() {
  const name = "clean lib and compile";
  console.log(`\n=== ${name} ===`);
  const step = { name, status: "passed" };
  steps.push(step);

  try {
    fs.rmSync(path.join(functionsDir, "lib"), { recursive: true, force: true });
    runCommand("compile", npm, [...npmPrefix, "run", "build"]);
    // Keep the externally reported sequence at five steps; compilation is part of this step.
    steps.pop();
  } catch (error) {
    step.status = "failed";
    throw error;
  }
}

function configureJava() {
  if (!fs.existsSync(jbrBin)) {
    throw new Error(
      `Java Runtime not found at ${jbrBin}. The mandatory Firestore emulator gate cannot run.`,
    );
  }
  environment.JAVA_HOME = path.dirname(jbrBin);
  environment.PATH = `${jbrBin}${path.delimiter}${nodeBin}${path.delimiter}${environment.PATH ?? ""}`;
}

try {
  configureJava();
  runCommand("npm ci", npm, [...npmPrefix, "ci"]);
  runCommand("lint", npm, [...npmPrefix, "run", "lint"]);
  runCommand("unit tests", npm, [...npmPrefix, "test"]);
  runCommand(
    "Firestore emulator gate",
    npx,
    [
      ...npxPrefix,
      "-y",
      "firebase-tools@latest",
      "emulators:exec",
      "--only",
      "firestore",
      "--project",
      "organitazionlabs",
      "npm --prefix functions run test:firestore-emulator",
    ],
    { cwd: projectDir },
  );
  cleanAndBuild();
  printSummary();
} catch (error) {
  console.error(`\nRelease check stopped: ${error.message}`);
  if (!steps.some((step) => step.status === "failed")) {
    steps.push({ name: "environment setup", status: "failed" });
  }
  printSummary();
  process.exitCode = 1;
}
