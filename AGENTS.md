# Walker Studio: guide for programming agents

This repo is a browser sandbox for **showing** a walker design for the Create / Create: Aeronautics / Sable
walker mod: its shape, its walking loop, and how it scores, without launching Minecraft. Use it to propose a
design and back it up with numbers that are easy to compare.

Units are Minecraft units throughout: **blocks (b)**, **ticks** (20 per second), **RPM** and **SU** (Create stress).

## The showcase loop

1. **Describe the design** in `walkers/<id>.json`. Copy `walkers/_template.json` or the closest existing
   walker. Fill in `proposal.summary`, `proposal.decisions` and `proposal.openQuestions`. The Design tab
   shows them; they explain *why* the design looks the way it does.
2. **Check it headlessly** (takes about a second):
   ```sh
   npm run check -- <id>                      # every course, table of metrics vs targets
   npm run check -- <id> quad-strider         # side by side with a reference walker
   npm run check -- <id> --scenario stairs    # a single course
   npm run check -- <id> --json               # machine-readable output
   npm run check -- --strict                  # exit 1 if any walker fails a target (CI)
   ```
   Iterate on the JSON until the numbers say what you want them to say. Read the `Events` line: it tells
   you *what* went wrong (overreach, toe-stub, void-step, ...).
3. **Show it**:
   ```sh
   npm run dev     # http://localhost:5177, live-reloads on every save
   ```
   Then send the human a link that sets up the view:
   `http://localhost:5177/?walkers=<id>,quad-strider&terrain=stairs&cam=side`
4. **Write it up**: `npm run report -- <id> quad-strider > report.md` produces a markdown table to paste
   into a PR or issue. Include the link from step 3.

## URL parameters

| param | values | |
|---|---|---|
| `walkers` | comma list of ids, max 3 | one lane per walker, compared side by side |
| `terrain` | `flat` `stairs` `ledge` `hill` `gap` `rough` `turn` `startstop` `reverse` | test course (the last three are flat and drive a throttle/steer timeline) |
| `focus` | lane index | which walker the camera, gait chart and Design/Tweak tabs follow |
| `cam` | `iso` `side` `front` `top` `chase` | camera preset |
| `t` + `paused=1` | tick | freeze on a specific tick (good for screenshots of a failure) |
| `speed` | `0.1` … `4` | playback speed |
| `tab` | `metrics` `design` `tweak` `events` `model` | side panel tab |
| `metric` | `margin` `bodyY` `reach` `footErr` `stance` `clearance` `speed` `torque` `trackErr` | timeline chart |
| `hud=0` | | hide all UI chrome, viewport only (press `h` to toggle) |

## Driving the page from a browser tool

`window.walkerStudio` is available once `await walkerStudio.ready` resolves:

```js
await walkerStudio.ready;
await walkerStudio.setWalkers(['my-walker', 'hexapod']);
walkerStudio.setTerrain('gap');
walkerStudio.seek(120);                 // pause on tick 120
walkerStudio.setCamera('side');
walkerStudio.metrics();                 // current course: values, targets, pass/warn/fail, events
walkerStudio.matrix();                  // verdict for every walker on every course
walkerStudio.tweak('gait.duty', 0.7);   // live override, not saved
await walkerStudio.load('draft', {...spec}); // show a spec that isn't in a file yet
walkerStudio.screenshot();              // PNG data URL of the 3D view
```

## What to change where

| You want to... | Edit |
|---|---|
| propose or change a walker | `walkers/<id>.json` (format: `docs/walker-spec.md`) |
| change what counts as "good enough" for everyone | `config/targets.json` (team-wide; say so in your PR) |
| change the bar for one walker only | `"targets"` block inside that walker (shown with a `*`) |
| calibrate server-cost estimates against real profiles | `config/cost-model.json`, then set `"calibrated": true` |
| add a test course (terrain and/or a throttle/steer `commands` timeline) | `src/sim/terrain.js` → `SCENARIOS` |
| change how the walking loop works (gait, IK, foothold search, sway) | `src/sim/simulate.js`; keep `npm test` green |

The simulation core in `src/sim/` is plain ES modules shared by the page and the CLI. Keep it free of DOM
and three.js code. `src/view/` is browser-only.

## The mod (`mod/`)

**Read `docs/decisions.md` first.** It records settled design decisions (hull physics, contraption legs,
block-built legs, v1 scope, controls, balance, speed). Build on them; don't reopen them without the user.

The real NeoForge 1.21.1 mod lives in `mod/` (Java 21, Gradle; details in `mod/README.md`). The dev box has
no GPU set up, so verify mod changes headlessly:

```sh
cd mod && ./gradlew runGameTestServer   # exits non-zero if any @GameTest in the "walkers" namespace fails
```

Don't use `runClient` on this machine. When the Java walking loop exists, it must reproduce `src/sim/`
for the walkers in `walkers/`. Keep the two in sync and test that they match.

## Reference sources (`reference/`)

`npm run reference` clones Create, Sable and Create: Aeronautics (Simulated-Project) at the exact versions
the mod uses into `reference/<name>/` (git-ignored). Read them to answer "how does Create/Sable/Aeronautics
do X" instead of guessing. Never edit them. The sync deletes their `AGENTS.md` files: the project owner
decided that only this repo's instructions apply when working here.

**We never contribute upstream.** Don't open issues, pull requests, forks or comments on Create, Sable,
Aeronautics or any other dependency, and don't push from the `reference/` clones. If a dependency needs a
change, work around it in `mod/` (e.g. a mixin) and tell the user.

## Ground rules for honest showcases

- Don't loosen `config/targets.json` just to make your walker pass. Override targets in the walker and
  explain why in `proposal.decisions`.
- A walker that isn't statically stable needs a Gyro Stabilizer (`physics.stabilizer`, decision D6). Size its
  `maxTorque` from the "Peak righting torque" metric with honest headroom, and remember that it costs stress
  (included in the Stress total). Don't oversize it just to make the stability row pass on a course where
  the walker falls into a trench. Say what you sized it for in `proposal.decisions`.
- Check the motion courses (`turn`, `startstop`, `reverse`) as well as the terrain ones: v1 must turn,
  reverse and stop (D4).
- Server numbers stay warnings until the cost model is calibrated. Don't quote them as measurements.
- See `docs/model.md` for what the sandbox does not model (strafing, leg-vs-hull collisions, real physics).
