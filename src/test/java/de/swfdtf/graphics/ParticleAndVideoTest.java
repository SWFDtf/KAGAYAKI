package de.swfdtf.graphics;

import de.swfdtf.math.Transform2D;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleAndVideoTest {

    @Test
    void particlePresetsSimulateStayInBoundsAndAttachToForeground() {
        ParticleSystem system = new ParticleSystem();

        ParticleSystem.ParticleEmitter snow = ParticleSystem.snowBlossom(40, 120.0f, 30.0f, new Random(1L));
        ParticleSystem.ParticleEmitter rain = ParticleSystem.rain(50, 900.0f, 150.0f, new Random(2L));
        ParticleSystem.ParticleEmitter cherry = ParticleSystem.cherryBlossoms(30, new Random(3L));
        ParticleSystem.ParticleEmitter sparks = ParticleSystem.sparks(25, new Random(4L));
        ParticleSystem.ParticleEmitter dust = ParticleSystem.dust(35, new Random(5L));

        assertEquals(ParticleSystem.EmitterKind.SNOW_BLOSSOM, snow.getKind());
        assertEquals(ParticleSystem.EmitterKind.RAIN, rain.getKind());
        assertEquals(ParticleSystem.EmitterKind.CHERRY_BLOSSOM, cherry.getKind());
        assertEquals(ParticleSystem.EmitterKind.SPARKS, sparks.getKind());
        assertEquals(ParticleSystem.EmitterKind.DUST, dust.getKind());

        system.addEmitter(snow)
                .addEmitter(rain)
                .addEmitter(cherry)
                .addEmitter(sparks)
                .addEmitter(dust);

        assertEquals(180, system.getTotalParticleCount());

        float snowStartY = snow.getParticles().get(0).getY();
        float sparkStartY = sparks.getParticles().get(0).getY();

        // Advance simulation across multiple frames including large steps to force wrap-around
        for (int i = 0; i < 60; i++) {
            system.update(0.1);
            for (ParticleSystem.ParticleEmitter emitter : system.getEmitters()) {
                for (ParticleSystem.Particle p : emitter.getParticles()) {
                    assertTrue(p.x >= 0.0f && p.x < ParticleSystem.DEFAULT_WIDTH,
                            "Particle x out of bounds: " + p.x);
                    assertTrue(p.y >= 0.0f && p.y < ParticleSystem.DEFAULT_HEIGHT,
                            "Particle y out of bounds: " + p.y);
                    assertEquals(4, p.getColorRgba().length);
                }
            }
        }

        // Verify particles actually moved and sparks respawned cleanly
        assertNotEquals(snowStartY, snow.getParticles().get(0).getY());
        assertNotEquals(sparkStartY, sparks.getParticles().get(0).getY());
        for (ParticleSystem.Particle p : sparks.getParticles()) {
            assertTrue(p.life > 0.0f && p.life <= p.maxLife);
        }

        // Attach to SceneGraph foreground layer (Z = 100)
        SceneGraph graph = new SceneGraph();
        SpriteNode attached = system.attachToForeground(graph);
        SceneLayer fg = graph.getLayer(SceneGraph.LAYER_FOREGROUND);
        assertEquals(SceneLayer.Z_FOREGROUND, fg.getZOrder());
        assertEquals(1, fg.size());
        assertSame(system, attached.getCustomDisplayable());
        assertEquals(1920.0f, attached.getWidth());
        assertEquals(1080.0f, attached.getHeight());
    }

    @Test
    void videoPlayerAndMovieSpriteProgressLoopPauseStopHeadlessly() {
        VideoPlayer.ProceduralTestFrameSource finiteSource =
                new VideoPlayer.ProceduralTestFrameSource(64, 36, 10.0, 3);
        try (VideoPlayer player = new VideoPlayer(false);
             MovieSprite movieSprite = new MovieSprite("cutscene", player)) {

            assertEquals(VideoPlayer.State.STOPPED, player.getState());
            assertNull(player.getCurrentTexture());

            movieSprite.setLoop(true);
            movieSprite.play(finiteSource);

            assertTrue(player.isPlaying());
            assertEquals(64.0f, movieSprite.getWidth());
            assertEquals(36.0f, movieSprite.getHeight());
            assertEquals(0L, player.getCurrentFrameIndex());

            byte[] frame0 = player.getLatestFrameRgba();
            assertNotNull(frame0);
            assertEquals(64 * 36 * 4, frame0.length);

            // Step to frame 1 (dt = 0.1s at 10 fps)
            movieSprite.update(0.1);
            assertEquals(1L, player.getCurrentFrameIndex());
            byte[] frame1 = player.getLatestFrameRgba();
            assertFalse(java.util.Arrays.equals(frame0, frame1));

            // Pause freezes frame progression
            movieSprite.pause();
            assertTrue(player.isPaused());
            movieSprite.update(0.5);
            assertEquals(1L, player.getCurrentFrameIndex());

            // Resume advances to frame 2 then loops back to frame 0
            movieSprite.resume();
            assertTrue(player.isPlaying());
            movieSprite.update(0.1);
            assertEquals(2L, player.getCurrentFrameIndex());

            movieSprite.update(0.1); // wraps around due to loop=true
            assertTrue(player.isPlaying());
            assertEquals(0L, player.getCurrentFrameIndex());
            assertArrayEquals(frame0, player.getLatestFrameRgba());

            // Non-looping reaches ENDED state at EOF
            movieSprite.setLoop(false);
            movieSprite.update(0.2); // advances frame 1 -> frame 2
            assertEquals(2L, player.getCurrentFrameIndex());
            movieSprite.update(0.1); // hits EOF
            assertTrue(player.isEnded());

            // Stop resets cleanly
            movieSprite.stop();
            assertTrue(player.isStopped());
            assertEquals(-1L, player.getCurrentFrameIndex());

            // Missing video file / absent ffmpeg fails gracefully without crashing
            movieSprite.play(Path.of("does_not_exist_12345.mp4"));
            assertTrue(player.isStopped());
            movieSprite.update(0.1);
            assertEquals(List.of(), movieSprite.getAttributes());
            assertNotNull(new Transform2D());
        }
    }
}
