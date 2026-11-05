package com.lbogerd.walkers.sim;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live-controller API: causal, tick by tick, and the same as the batch run. */
class GaitControllerTest {
    static WalkerSpec walker(String id) throws Exception {
        try (InputStream in = GaitControllerTest.class.getResourceAsStream("/sim-fixtures/" + id + "__flat.json.gz")) {
            var json = com.google.gson.JsonParser.parseString(new String(new java.util.zip.GZIPInputStream(in).readAllBytes(), StandardCharsets.UTF_8));
            return SpecNormalizer.normalizeSpec(json.getAsJsonObject().get("raw"), id);
        }
    }

    @Test
    void liveControllerMatchesBatchSimulate() throws Exception {
        WalkerSpec spec = walker("quad-strider");
        var course = Scenarios.makeTerrain("reverse");
        Simulate.Result batch = Simulate.simulate(spec, course);
        GaitController live = new GaitController(spec, Scenarios.makeTerrain("reverse"), Scenarios.Command.STOP, 0);
        for (int t = 0; t <= batch.ticks(); t++) {
            GaitController.TickState s = live.tick(Scenarios.commandAt(course.commands(), t));
            Simulate.Frame f = batch.frames().get(t);
            assertArrayEquals(f.actual().pos(), s.actual().pos(), "hull at tick " + t);
            for (int li = 0; li < s.legs().size(); li++)
                assertArrayEquals(f.legs().get(li).foot(), s.legs().get(li).foot(), "foot " + li + " at tick " + t);
        }
    }

    @Test
    void identicalCommandsUpToATickGiveIdenticalStatesUpToIt() throws Exception {
        WalkerSpec spec = walker("quad-crawler");
        GaitController a = new GaitController(spec, Scenarios.makeTerrain("flat"));
        GaitController b = new GaitController(spec, Scenarios.makeTerrain("flat"));
        List<double[]> fa = new ArrayList<>(), fb = new ArrayList<>();
        for (int t = 0; t <= 300; t++) {
            Scenarios.Command ca = new Scenarios.Command(1, 0);
            Scenarios.Command cb = t < 150 ? ca : new Scenarios.Command(0.5, -1);
            var sa = a.tick(ca);
            var sb = b.tick(cb);
            if (t <= 150) {
                assertArrayEquals(sa.actual().pos(), sb.actual().pos(), "hull at tick " + t);
                for (int li = 0; li < sa.legs().size(); li++)
                    assertArrayEquals(sa.legs().get(li).foot(), sb.legs().get(li).foot(), "foot " + li + " at tick " + t);
            }
            fa.add(sa.actual().pos());
            fb.add(sb.actual().pos());
        }
        assertNotEquals(fa.get(300)[2], fb.get(300)[2], "the futures diverge");
    }

    @Test
    void standingStartPlantsEveryFootAndDecidesFootholdsCausally() throws Exception {
        WalkerSpec spec = walker("hexapod");
        GaitController c = new GaitController(spec, (ix, iz) -> 0); // any Terrain: here a lambda
        var s = c.tick(Scenarios.Command.STOP);
        assertEquals(0, s.t());
        assertEquals(spec.gait.restPlanted, s.legs().stream().filter(l -> l.st().stance()).count());
        for (int t = 1; t < 200; t++) {
            s = c.tick(new Scenarios.Command(1, 0.3));
            for (var l : s.legs()) {
                assertTrue(l.st().fh().tDecided <= t, "foothold decided in the future");
                if (l.st().next() != null) assertTrue(l.st().next().tDecided <= t, "next foothold decided in the future");
            }
        }
        assertTrue(s.path().x() > 5, "walked forward");
    }

    /** Live trimming keeps memory bounded over a long run and changes nothing about the results. */
    @Test
    void trimmedHistoryIsBoundedAndChangesNothing() throws Exception {
        for (String id : new String[] {"quad-strider", "quad-crawler", "hexapod", "biped"}) {
            WalkerSpec spec = walker(id);
            GaitController full = new GaitController(spec, Scenarios.makeTerrain("rough"), Scenarios.Command.STOP, 0);
            GaitController trimmed = new GaitController(spec, Scenarios.makeTerrain("rough"), Scenarios.Command.STOP, 0);
            int maxHeld = 0, maxFootholds = 0;
            for (int t = 0; t < 6000; t++) {
                // walk, turn, stop and reverse in a repeating pattern
                int p = (t / 300) % 4;
                Scenarios.Command c = p == 0 ? new Scenarios.Command(1, 0) : p == 1 ? new Scenarios.Command(0.5, 0.6)
                        : p == 2 ? Scenarios.Command.STOP : new Scenarios.Command(-0.7, -0.3);
                GaitController.TickState a = full.tick(c), b = trimmed.tick(c);
                if (t % 200 == 0) trimmed.trimHistory(400);
                assertArrayEquals(a.actual().pos(), b.actual().pos(), id + " hull at tick " + t);
                for (int li = 0; li < a.legs().size(); li++)
                    assertArrayEquals(a.legs().get(li).foot(), b.legs().get(li).foot(), id + " foot " + li + " at tick " + t);
                maxHeld = Math.max(maxHeld, trimmed.path().heldTicks());
                maxFootholds = Math.max(maxFootholds, trimmed.heldFootholds());
            }
            assertTrue(maxHeld < 1000, id + ": planner held " + maxHeld + " ticks");
            // between trims (every 200 ticks) each leg adds about one foothold per gait cycle
            int bound = spec.legs.size() * (4 + (int) Math.ceil(200 / spec.gait.cycleTicks));
            assertTrue(maxFootholds <= bound, id + ": held " + maxFootholds + " footholds (bound " + bound + ")");
            assertTrue(full.path().heldTicks() > 6000, "untrimmed control keeps everything");
        }
    }
}
