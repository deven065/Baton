# Baton UI Generator (Figma plugin)

A local Figma plugin that draws the complete Baton frontend design into the open
Figma file as normal, editable layers. It uses no AI and makes no network calls:
everything is built with the Figma Plugin API.

## What it creates

| Page | Frames |
|---|---|
| Baton · 00 Cover | Cover |
| Baton · 01 Design system | Foundations (colour tokens, type scale, evidence model) · Components |
| Baton · 02 Auth | Sign in · Create account · Handover invite (recipient) |
| Baton · 03 Lead workspace | Dashboard · Connect sources (+ Add-source authorisation modal) · Reconstruct (live) · Review workspace · Resolve conflict · Decision timeline · View as · Approval & versions · Share & acknowledgement |
| Baton · 04 Leaver | Exit interview |
| Baton · 05 Recipient | Start here · Ownership map · Start here (mobile) |
| Baton · 06 Admin | Users & groups · Audit log |

Design-system output:
- **Variables**: collection `Baton tokens` (ink, surface, brand, status, sensitivity colours). Every fill and stroke is bound to it.
- **Styles**: text styles `Baton/Display` … `Baton/Mono small`; effect styles `Baton/Shadow card`, `Baton/Shadow pop`.
- **Components**: Button (Kind × Size), Status badge, Sensitivity chip, Input, Checkbox, Avatar, Nav item (with icon swap), Evidence quote, Stat card, and 14 icons.

All content is the synthetic "Project Annapurna" demo data.

## Run it

1. Open the **Figma desktop app** and the file **Baton — Complete Product UI/UX**.
2. Menu → **Plugins → Development → Import plugin from manifest…** and pick
   `design/figma-plugin/manifest.json`.
3. Menu → **Plugins → Development → Baton UI Generator**. It takes about 10–20 seconds.

Running it again replaces only the pages it created earlier (marked with plugin data).
It never touches your other pages.

## Edit it

The sources are in `src/`, and `code.js` is built from them:

```bash
sh design/figma-plugin/build.sh
```
