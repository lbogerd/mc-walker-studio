<img src="assets/icon.svg" width="64" height="64" alt="">

# mc-walker-studio

A browser sandbox for designing walkers for our Create / Create: Aeronautics / Sable mod. Use it to show a
leg layout, a gait and the numbers behind it (speed, stability, reach, collisions, drive, server cost)
in seconds, without starting Minecraft.

```sh
npm run dev            # http://localhost:5177  (live reload; needs internet once for three.js from jsDelivr)
npm run check          # every walker × every course, metrics vs config/targets.json
npm run report -- quad-strider hexapod > report.md
npm test
```

- `walkers/*.json`: walker designs (format in `docs/walker-spec.md`)
- `config/targets.json`: the shared bar every walker is judged against. **This is the file to agree on.**
- `config/cost-model.json`: server-cost coefficients (placeholders until calibrated, see `docs/model.md`)
- `mod/`: the NeoForge 1.21.1 mod itself (Create + Sable + Aeronautics). See `mod/README.md`
- `reference/`: pinned source checkouts of Create and Sable (`npm run reference`)
- `AGENTS.md`: how a coding agent should showcase a design

No dependencies besides Node ≥ 18. For a static host, run `npm run manifest` first so `walkers/index.json` exists.

Hosted at https://walker-studio.tainer.run behind tainer sign-in (Better Auth).
Run `docker compose up -d --wait` to deploy this workspace to the existing
loopback origin at `127.0.0.1:3850`. Source edits reload live, and the Tweak tab's
save action writes to this workspace's `walkers/` directory. The Compose project
is `walker-studio`, replacing the earlier deployment on that URL.
