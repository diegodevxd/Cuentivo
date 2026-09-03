# CuentivoAI Privacy Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a clear, optional-use privacy section for CuentivoAI to the public Cuentivo privacy policy.

**Architecture:** Modify the single static policy document, `index.html`. Append one navigable section after the ten existing sections, preserving the copy and visual numbering of every pre-existing section; add a matching table-of-contents link.

**Tech Stack:** Static HTML and CSS; PowerShell content checks.

## Global Constraints

- Do not change the wording of the ten existing policy sections.
- Do not mention microphone, voice, or audio.
- Do not state a Google-side retention period; direct readers to Google's applicable terms.
- State that CuentivoAI is optional, paid ($299 MXN/month), and inactive unless used.
- State that the phrase may contain client names and amounts, travels through Cuentivo's Google Cloud `us-south1` server before Google Gemini, and is encrypted in transit with HTTPS/TLS.
- State that Cuentivo does not retain phrase content; it retains only the request identifier and result, and technical logs exclude personal data, balances, and payment identifiers.
- State that CuentivoAI only proposes an operation and requires user confirmation before saving it.
- Refer to the existing international-transfer section rather than duplicating it.

---

### Task 1: Add the CuentivoAI privacy section and navigation link

**Files:**
- Modify: `C:\Users\pibed\Downloads\Cuentivo-politicasdeprivacidad-main\index.html`
- Test: `C:\Users\pibed\Downloads\Cuentivo-politicasdeprivacidad-main\index.html` (static content checks)

**Interfaces:**
- Consumes: Existing table-of-contents links and `<article class="policy glass-card">` section sequence.
- Produces: A `#cuentivoai` table-of-contents anchor and an independently readable CuentivoAI section.

- [ ] **Step 1: Add a static content assertion command**

Run the following command before editing. It intentionally fails because CuentivoAI is absent:

```powershell
$html = Get-Content -Raw index.html
if ($html -notmatch 'id="cuentivoai"') { throw 'Missing CuentivoAI policy section' }
```

Expected: failure with `Missing CuentivoAI policy section`.

- [ ] **Step 2: Add the new index entry and policy section**

In the table of contents, append the exact entry after Cambios y contacto:

```html
<li><a href="#cuentivoai">CuentivoAI</a></li>
```

After `<section id="cambios">`, insert a new section with `id="cuentivoai"`, visual number `11`, title `CuentivoAI`, and Spanish plain-language copy that implements every Global Constraint. Refer readers to the existing sections “Seguridad” and “Transferencias y tratamiento internacional”. Do not alter the wording or visual numbering of the ten existing sections.

- [ ] **Step 3: Verify the new section and prohibited-content absence**

Run:

```powershell
$html = Get-Content -Raw index.html
if ($html -notmatch '<a href="#cuentivoai">CuentivoAI</a>') { throw 'Missing CuentivoAI navigation link' }
if ($html -notmatch '<section id="cuentivoai">') { throw 'Missing CuentivoAI policy section' }
if ($html -match '(?i)micr[óo]fono|voz|audio') { throw 'Prohibited audio-related content found' }
if ($html -notmatch 'HTTPS/TLS') { throw 'Missing encryption statement' }
if ($html -notmatch 'us-south1') { throw 'Missing server location' }
if ($html -notmatch 'Gemini') { throw 'Missing Google AI provider' }
'PASS: CuentivoAI content checks'
```

Expected: `PASS: CuentivoAI content checks`.

- [ ] **Step 4: Verify document structure and unchanged existing section text**

Run:

```powershell
$html = Get-Content -Raw index.html
$sectionOpenCount = ([regex]::Matches($html, '<section(?:\s|>)')).Count
$sectionCloseCount = ([regex]::Matches($html, '</section>')).Count
if ($sectionOpenCount -ne 12 -or $sectionCloseCount -ne 12) { throw "Unexpected section count: open=$sectionOpenCount close=$sectionCloseCount" }
if ($html -notmatch '<section id="transferencias">') { throw 'Existing transfer section missing' }
if ($html -notmatch '<section id="seguridad">') { throw 'Existing security section missing' }
'PASS: policy structure checks'
```

Expected: `PASS: policy structure checks`.

- [ ] **Step 5: Record local completion**

This directory has no `.git` repository. Do not attempt to commit. Report the edited file, the generated design and plan documents, and the passing verification commands to the user.
