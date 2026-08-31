"use strict";

const fs = require("node:fs");
const path = require("node:path");

const libDir = path.resolve(__dirname, "..", "lib");
if (fs.existsSync(libDir)) {
  fs.rmSync(libDir, { recursive: true, force: true });
}
