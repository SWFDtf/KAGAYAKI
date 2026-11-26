package de.swfdtf.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDecoderAndChannelTest {

    @Test
    void synthesizesToneAndRoundTripsThroughWavEncodeAndDecode(@TempDir Path tempDir) throws IOException {
        AudioDecoder.DecodedAudio synth = AudioDecoder.synthesizeTone(440.0, 0.25, 44100, 0.6f);
        assertEquals(1, synth.channels());
        assertEquals(44100, synth.sampleRate());
        assertEquals(0.25, synth.durationSeconds(), 1e-4);
        assertEquals(11025, synth.frameCount());

        byte[] wavBytes = AudioDecoder.encodeWav(synth.pcm16(), synth.channels(), synth.sampleRate());
        AudioDecoder.DecodedAudio fromBytes = AudioDecoder.decodeWav(wavBytes);
        assertEquals(synth.channels(), fromBytes.channels());
        assertEquals(synth.sampleRate(), fromBytes.sampleRate());
        assertEquals(synth.durationSeconds(), fromBytes.durationSeconds(), 1e-6);
        assertArrayEquals(synth.pcm16(), fromBytes.pcm16());

        Path wavFile = tempDir.resolve("tone.wav");
        Files.write(wavFile, wavBytes);
        AudioDecoder.DecodedAudio fromFile = AudioDecoder.decode(wavFile);
        assertArrayEquals(synth.pcm16(), fromFile.pcm16());

        // Also test stereo WAV + extra RIFF JUNK chunk handling
        short[] stereoPcm = new short[]{1000, -1000, 2000, -2000, 3000, -3000};
        byte[] stereoWav = AudioDecoder.encodeWav(stereoPcm, 2, 48000);
        byte[] wavWithJunk = insertJunkChunkBeforeData(stereoWav);
        AudioDecoder.DecodedAudio decodedStereo = AudioDecoder.decodeWav(wavWithJunk);
        assertEquals(2, decodedStereo.channels());
        assertEquals(48000, decodedStereo.sampleRate());
        assertArrayEquals(stereoPcm, decodedStereo.pcm16());
        float[] monoAvg = decodedStereo.toMonoFloat();
        assertEquals(3, monoAvg.length);
        for (float s : monoAvg) {
            assertEquals(0.0f, s, 1e-4f);
        }
    }

    @Test
    void decodesValidOggVorbisViaStbVorbisAndRejectsInvalidOggGracefully(@TempDir Path tempDir) throws IOException {
        byte[] oggBytes = Base64.getDecoder().decode(SAMPLE_OGG_440HZ_MONO_B64);
        AudioDecoder.DecodedAudio fromMemory = AudioDecoder.decodeOgg(oggBytes);
        assertEquals(1, fromMemory.channels());
        assertEquals(44100, fromMemory.sampleRate());
        assertTrue(fromMemory.frameCount() > 2000);
        assertTrue(fromMemory.durationSeconds() > 0.04);
        float[] mono = fromMemory.toMonoFloat();
        assertTrue(AudioDsp.computeRms(mono) > 0.1f);
        assertTrue(AudioDsp.computeGoertzelMagnitude(mono, 0, Math.min(2048, mono.length), 440.0f, 44100.0f) > 0.2f);

        Path oggPath = tempDir.resolve("tone.ogg");
        Files.write(oggPath, oggBytes);
        AudioDecoder.DecodedAudio fromFile = AudioDecoder.decode(oggPath);
        assertArrayEquals(fromMemory.pcm16(), fromFile.pcm16());

        AudioDecoder.DecodedAudio bleep = AudioDecoder.synthesizeVoiceBleep(520.0, 0.06, 44100);
        assertEquals(1, bleep.channels());
        assertEquals(44100, bleep.sampleRate());
        assertTrue(bleep.frameCount() > 2000);
        assertTrue(AudioDsp.computeRms(bleep.monoOrInterleavedFloat()) > 0.05f);
        assertEquals(0.0f, bleep.monoOrInterleavedFloat()[0], 1e-6f);

        assertThrows(IOException.class, () -> AudioDecoder.decodeOgg(new byte[0]));
        assertThrows(IOException.class, () -> AudioDecoder.decodeOgg(new byte[]{'O', 'g', 'g', 'S', 0, 1, 2, 3}));
    }

    @Test
    void channelHandlesQueueFadeCrossfadeDspFilterAndSnapshots() {
        AudioChannel channel = new AudioChannel("music", false);
        AudioDecoder.DecodedAudio lowTone = AudioDecoder.synthesizeTone(220.0, 2.0, 44100, 0.8f);
        AudioDecoder.DecodedAudio highTone = AudioDecoder.synthesizeTone(6000.0, 2.0, 44100, 0.8f);

        // Queue FIFO
        channel.queue(List.of("track2.wav", "track3.wav"));
        assertEquals(List.of("track2.wav", "track3.wav"), channel.getQueuedPaths());
        assertEquals(Optional.of("track2.wav"), channel.pollNextQueued());
        assertEquals(Optional.of("track3.wav"), channel.pollNextQueued());
        assertEquals(Optional.empty(), channel.pollNextQueued());

        // Fade-in envelope
        channel.play("low.wav", lowTone, true, 1.0, 0.8f);
        assertTrue(channel.isPlaying());
        assertEquals(0.0f, channel.getFadeGain(), 1e-5f);
        channel.update(0.5);
        assertEquals(0.5f, channel.getFadeGain(), 1e-5f);
        assertEquals(0.4f, channel.getCurrentEffectiveVolume(), 1e-5f);
        channel.update(0.5);
        assertEquals(1.0f, channel.getFadeGain(), 1e-5f);
        assertEquals(1.0, channel.getPlaybackSeconds(), 1e-6);

        // Crossfade into second track
        channel.play("high.wav", highTone, true, 1.0, 1.0f);
        assertTrue(channel.isCrossfading());
        assertEquals(0.0f, channel.getFadeGain(), 1e-5f);
        assertEquals(1.0f, channel.getOutgoingFadeGain(), 1e-5f);
        channel.update(0.5);
        assertTrue(channel.isCrossfading());
        assertEquals(0.5f, channel.getFadeGain(), 1e-5f);
        assertEquals(0.5f, channel.getOutgoingFadeGain(), 1e-5f);
        channel.update(0.6);
        assertFalse(channel.isCrossfading());
        assertEquals(1.0f, channel.getFadeGain(), 1e-5f);

        // DSP Filter toggle attenuates 6000 Hz tone via 500 Hz lowpass
        float dryRms = channel.getCurrentRms();
        assertTrue(dryRms > 0.4f, "Expected strong dry RMS, got " + dryRms);
        channel.setFilter(AudioDsp.BiquadFilter.createLowpass(44100.0f, 500.0f, 0.7071f));
        float wetRms = channel.getCurrentRms();
        assertTrue(wetRms < 0.05f, "Expected lowpass to attenuate 6000 Hz tone, got " + wetRms);
        channel.setFilter(null);
        assertEquals(dryRms, channel.getCurrentRms(), 1e-5f);

        // Capture rollback snapshot before stopping
        AudioChannel.ChannelSnapshot snapshot = channel.captureSnapshot();
        assertTrue(snapshot.playing());
        assertEquals("high.wav", snapshot.assetPath());
        double capturedPos = snapshot.playbackSeconds();

        // Fade-out stop
        channel.stop(0.4);
        assertTrue(channel.isFadingOut());
        channel.update(0.2);
        assertTrue(channel.isPlaying());
        assertEquals(0.5f, channel.getFadeGain(), 1e-4f);
        channel.update(0.25);
        assertFalse(channel.isPlaying());
        assertEquals(0.0f, channel.getCurrentRms(), 1e-6f);

        // Restore rollback snapshot
        channel.restoreSnapshot(snapshot);
        assertTrue(channel.isPlaying());
        assertEquals("high.wav", channel.getAssetPath());
        assertEquals(capturedPos, channel.getPlaybackSeconds(), 1e-6);
        assertTrue(channel.getCurrentRms() > 0.4f);

        // Non-looping completion
        AudioDecoder.DecodedAudio shortClip = AudioDecoder.synthesizeTone(440.0, 0.1, 44100, 0.5f);
        channel.play("sfx.wav", shortClip, false, 0.0, 1.0f);
        channel.update(0.15);
        assertFalse(channel.isPlaying());
        AudioChannel.ChannelSnapshot stoppedSnap = channel.captureSnapshot();
        assertFalse(stoppedSnap.playing());
        assertNotNull(channel.getAssetPath());

        channel.restoreSnapshot(stoppedSnap);
        assertFalse(channel.isPlaying());
        assertNull(channel.getAssetPath());
    }

    private static byte[] insertJunkChunkBeforeData(byte[] wavBytes) {
        byte[] junkHeader = new byte[]{'J', 'U', 'N', 'K', 3, 0, 0, 0, 1, 2, 3, 0}; // size=3 padded to 4
        int split = 36; // right before "data"
        byte[] out = new byte[wavBytes.length + junkHeader.length];
        System.arraycopy(wavBytes, 0, out, 0, split);
        System.arraycopy(junkHeader, 0, out, split, junkHeader.length);
        System.arraycopy(wavBytes, split, out, split + junkHeader.length, wavBytes.length - split);
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, out.length - 8);
        return out;
    }

    static final String SAMPLE_OGG_440HZ_MONO_B64 =
            "T2dnUwACAAAAAAAAAABcUtg6AAAAAMkvl0EBHgF2b3JiaXMAAAAAAUSsAAAAAAAA8E8BAAAAAAC4AU9nZ1MAAAAAAAAAAAAA"
            + "XFLYOgEAAAAn21LhD4f/////////////////kQN2b3JiaXM0AAAAWGlwaC5PcmcgbGliVm9yYmlzIEkgMjAyMDA3MDQgKFJl"
            + "ZHVjaW5nIEVudmlyb25tZW50KQIAAAASAAAARU5DT0RFUj1saWJzbmRmaWxlKQAAAFNPRlRXQVJFPUxhdmY2Mi4xMi4xMDEg"
            + "KGxpYnNuZGZpbGUtMS4yLjIpAQV2b3JiaXMmQkNWAQAIAACAIkwYxIDQkFUAABAAAKCsN5Z7yL333nuBqEcUe4i9995746xH"
            + "0HqIuffee+69pxp7y7333nMgNGQVAAAEAIApCJpy4ELqvfceGeYRURoqx733HhmFiTCUGYU9ldpa6yGT3ELqPeceCA1ZBQAA"
            + "AgBACCGEFFJIIYUUUkghhRRSSCmlmGKKKaaYYsoppxxzzDHHIIMOOuikk1BCCSmkUEoqqaSUUkot1lpz7r0H3XPvQfgghBBC"
            + "CCGEEEIIIYQQQghCQ1YBACAAAARCCCFkEEIIIYQUUkghpphiyimngNCQVQAAIACAAAAAAEmRFMuxHM3RHM3xHM8RJVESJdEy"
            + "LdNSNVMzPVVURdVUVVdVXV13bdV2bdWWbddWbdV2bdVWbVm2bdu2bdu2bdu2bdu2bdu2bSA0ZBUAIAEAoCM5kiMpkiIpkuM4"
            + "kgSEhqwCAGQAAAQAoCiK4ziO5EiOJWmSZnmWZ4maqJma6KmeCoSGrAIAAAEABAAAAAAA4HiK53iOZ3mS53iOZ3map2mapmma"
            + "pmmapmmapmmapmmapmmapmmapmmapmmapmmapmmapmmapmmapmlAaMgqAEACAEDHcRzHcRzHcRxHciQHCA1ZBQDIAAAIAEBS"
            + "JMdyLEdzNMdzPEd0RMd0TMmUVMm1XAsIDVkFAAACAAgAAAAAAEATLEVTPMeTPM8TNc/TNM0TTVE0TdM0TdM0TdM0TdM0TdM0"
            + "TdM0TdM0TdM0TdM0TdM0TdM0TdM0TVMUgdCQVQAABAAAIZ1mlmqACDOQYSA0ZBUAgAAAABihCEMMCA1ZBQAABAAAiKHkIJrQ"
            + "mvPNOQ6a5aCpFJvTwYlUmye5qZibc84555xszhnjnHPOKcqZxaCZ0JpzzkkMmqWgmdCac855EpsHranSmnPOGeecDsYZYZxz"
            + "zmnSmgep2Vibc85Z0JrmqLkUm3POiZSbJ7W5VJtzzjnnnHPOOeecc86pXpzOwTnhnHPOidqba7kJXZxzzvlknO7NCeGcc845"
            + "55xzzjnnnHPOCUJDVgEAQAAABGHYGMadgiB9jgZiFCGmIZMedI8Ok6AxyCmkHo2ORkqpg1BSGSeldILQkFUAACAAAIQQUkgh"
            + "hRRSSCGFFFJIIYYYYoghp5xyCiqopJKKKsoos8wyyyyzzDLLrMPOOuuwwxBDDDG00kosNdVWY4215p5zrjlIa6W11lorpZRS"
            + "SimlIDRkFQAAAgBAIGSQQQYZhRRSSCGGmHLKKaegggoIDVkFAAACAAgAAADwJM8RHdERHdERHdERHdERHc/xHFESJVESJdEy"
            + "LVMzPVVUVVd2bVmXddu3hV3Ydd/Xfd/XjV8XhmVZlmVZlmVZlmVZlmVZlmUJQkNWAQAgAAAAQgghhBRSSCGFlGKMMcecg05C"
            + "CYHQkFUAACAAgAAAAABHcRTHkRzJkSRLsiRN0izN8jRP8zTRE0VRNE1TFV3RFXXTFmVTNl3TNWXTVWXVdmXZtmVbt31Ztn3f"
            + "933f933f933f933f13UgNGQVACABAKAjOZIiKZIiOY7jSJIEhIasAgBkAAAEAKAojuI4jiNJkiRZkiZ5lmeJmqmZnumpogqE"
            + "hqwCAAABAAQAAAAAAKBoiqeYiqeIiueIjiiJlmmJmqq5omzKruu6ruu6ruu6ruu6ruu6ruu6ruu6ruu6ruu6ruu6ruu6ruu6"
            + "QGjIKgBAAgBAR3IkR3IkRVIkRXIkBwgNWQUAyAAACADAMRxDUiTHsixN8zRP8zTREz3RMz1VdEUXCA1ZBQAAAgAIAAAAAADA"
            + "kAxLsRzN0SRRUi3VUjXVUi1VVD1VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVXVNE3TNIHQkJUAABAAAA06+Bp7"
            + "yZjEkntojEIMeuuYc456zYwiyHHsEDOIeQuVIwR5jZlEiHEgNGRFABAFAAAYgxxDzCHnnKROUuSco9JRapxzlDpKHaUUa8q1"
            + "o1RiS7U2zjlKHaWMUsq1tNpRSrWmGgsAAAhwAAAIsBAKDVkRAEQBABAIIaWQUkgp5pxyDimlnGPOIaaUc8o55ZyD0kmpnHPS"
            + "OSmRUso55ZxyzknpnFTOOSmdhAIAAAIcAAACLIRCQ1YEAHECAA7H8TxJ00RR0jRR9EzRdT3RdF1J00xTE0VV1URRVU1XtW3R"
            + "VGVb0jTT1ERRVTVRVFVRNW3ZVFXb9kzTlk3X1W1RVXVbtm1heG3b9z3TtG1RVW3ddF1bd23Z92Vb141H00xTE0VX1URRdU1X"
            + "1W1TdW1dE0XXFVVXlkXVlWVXlnVflWXd10TRdUXVlF1RdWVblV3fdmVZ903X9XVVloVflWXht3VdGG7fN55RVXVflV3fV2XZ"
            + "F27dNn7b94Vn0jTT1ETRVTXRVF3TVXXddF3b1kTRdUVXtWXRVF3ZlW3fV13Z9jVRdF3RVWVZdFVZVmXZ911Z9nVRVX1blWXf"
            + "V13Z923fF4bZ1n3hdF1dV2XZF1ZZ9n3b15Xl1nXh+EzTtk3X1XXTdX3f9nVnmXVd+EXX9X1Vln1jtWVf+IXfqfvG8Yyqquuq"
            + "7Qq/KsvCsAu789y+L5R12/ht3Wfcvo/x4/zGkWvbwjHrtnPcvq4sv/MzfmVYeqZp26br+rrpur4v67ox3L6vFFXV11VbNobV"
            + "lYXjFn7j2H3hOEbX9X1Vln1jtWVh2H3feH5heJ7Xto3h9n3KbOtGH3yf8sy6je37xnL7Oud3js7wDAkAABhwAAAIMKEMFBqy"
            + "IgCIEwBgEHIOMQUhUgxCCCGlDkJKEWMQMuekZMxJCaWkFkpJLWIMQuaYlMw5KaGUlkIpLYUSWgulxBZKaa21VmtqLdYQSmuh"
            + "lBhDKS2m1mpMrdUaMQYhc05K5pyUUkproZTWMueodA5S6iCklFJqsaQUY+WclAw6Kh2ElEoqMZWUYgypxFZSirWkVGNrseUW"
            + "Y86hlBZLKrGVlGJtMeUYY8w5YgxC5pyUzDkpoZTWSkktVs5J6SCklDkoqaQUYykpxcw5SR2ElDroKJWUYkwtxRZKia2kVGMp"
            + "qcUWY84txVhDSS2WlGItKcXYYsy5xZZbB6G1kEqMoZQYW4w5t9ZqDaXEWFKKtaRUY4y19hhjzqGUGEsqNZaUYm019tpirDm1"
            + "lmtqseYWY8+15dZrzr2n1mpNseXaYsw95hhkzbkHD0JroZQWQykxttZqbTHmHEqJraRUYykp1hhjzi3W2kMpMZaUYi0p1Rpj"
            + "zDnW2GtqLdcWY8+pxZprzsHHmGNPLdYcY8w9xZZrzbn3mluQBQAADDgAAASYUAYKDVkJAEQBABCEKMUYhAYhxpyT0CDEmHNS"
            + "Ksacg5BKxZhzEErKnINQSkqZcxBKSSmUkkpKrYVSSkqptQIAAAocAAACbNCUWByg0JCVAEAqAIDBcSzL80RRNWXZsSTPE0XT"
            + "VFXbdizL80TRNFXVti3PE0XTVFXX1XXL80TRVFXVdXXdE0XVVFXXlWXf90TRNFXVdWXZ903TdFXXlWXb9n3TNFXXdWVZtn1h"
            + "dVXXlWXb1m1jWFXXdWXZtm1dOW7d1nXhF4ZhmNq67vu+LwzH8EwDAMATHACACmxYHeGkaCyw0JCVAEAGAABhDEIGIYUMQkgh"
            + "hZRCSCklAABgwAEAIMCEMlBoyEoAIBUAACDEWmuttdZaYqm11lprrbWGSmuttdZaa6211lprrbXWWmuttdZaa6211lprrbXW"
            + "WmuttZRSSimllFJKKaWUUkoppZRSSimllFJKKaWUUkoppZRSSimllFJKKaWUUkoppZRSSimllFJKKRUA6FfhAOD/YMPqCCdF"
            + "Y4GFhqwEAMIBAABjlGIMOukkpNQw5RiEUlJJpZVGMecglJJSSq1VzklIpaXWWouxck5KSSm1FluMHYSUWmotxhhj7CCklFpr"
            + "McYYYyilpRhjrDHWWkNJqbUYY4w111pSai3GWmutufeSUosxxlxr7rmX1mKsteacc849tRZjrTXn3HPwqbUYY8619957UK3F"
            + "WGuuOQfhewEA3A0OABAJNs6wknRWOBpcaMhKACAkAIBAiDHGnHMOQgghREox5pxzEEIIIYRIKcaccw5CCCGEkDHmnHMQQgih"
            + "lFIyxpxzDkIIJZRQSuaccxBCCKGUUkrJnHMOQgghlFJKKR10EEIIoZRSSimlcw5CCKGUUkoppYQQQiillFJKKaWUEEIIpZRS"
            + "SimllBJCCKWUUkoppZRSQgihlFJKKaWkUkoIoZRSSimllFJKCSGUUkoppZRSSimhhFJKKaWUUkopJZRQSimllFJKKqUUAABw"
            + "4AAAEGAEnWRUWYSNJlx4AAoNWQkAAAEAIM5abClGRjHnIIbIIMQghgopxZy1DCmDHKZMKYSUlc4xhoiTFlsLFQMAAEAQAEAg"
            + "ZAKBAigwkAEABwgJUgBAYYGhQ4QIEKPAwLi4tAEACEJkhkhELAaJCdVAUTEdACwuMOQDQIbGRtrFBXQZ4IIu7joQQhCCEMTi"
            + "AApIwMEJNzzxhifc4ASdolIHAQAAAABwAAAPAADHBhAR0RxHh8cHSIjICElJAAAAAADgAMAHAMBhAkRENMfR4fEBEiIyQlIS"
            + "AAAAAAAAAAAABAQEAAAAAAACAAAABARPZ2dTAATIDQAAAAAAAFxS2DoCAAAAFMfHjgglGDImORspKSS3X5c3fxiu/w8JDAgA"
            + "8GPc/fI4k2dy5Rk9eGYVsXuxWKHv+z5Mu7++fsKzaXLx82kAgAACAHscPRT88wBa2t6t67cf8QHzAMIZgAAAAAAAADvPM/v+"
            + "22wI888SW3x9vau/GTHOhTk7O23e3t4iAL7a3ipXyW2JAvvkfgFpAhAAEFAAAAAA+OrzBAAATHrFjgNgAugAttre5q+S9yOu"
            + "gHmEQFoAAAAAAAAABoOgX2+ZYLKfDF/z7cMPrr2tpxtXW2WVET/88P///8P9MgAARLu/vt5jmTSWAAAEAAAAE2Df+cJKIQHS"
            + "/UwAPLvfTgnD6g/ccwdgBACWZF2T+v//+39TnvMFc9H//tnLZWZGjQri7gAEr+sf7/8elcgxPxLACACE/bfv+8aLVPXidRv8"
            + "s3++Jbby4tX68/ZWAw==";
}
