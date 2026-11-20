package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain-JVM regression tests for multi-traf and truncated fragmented-MP4 parsing. */
public final class FragmentMoofParserTest {

    private static int passed;
    private static final int NON_SYNC = 0x00010000;

    public static void main(String[] args) throws Exception {
        testAudioAndVideoTrafsInOneMoof();
        testPerSampleFlagsOverrideFirstSampleFlags();
        testTruncatedSampleFlagsAreRejected();
        testUnsignedTfdtOverflowIsRejected();
        System.out.println("FRAGMENT MOOF PARSER: " + passed + " checks passed");
    }

    private static void testAudioAndVideoTrafsInOneMoof() throws Exception {
        Map<Integer, FragmentMoofParser.Track> tracks = tracks();
        byte[] audio = traf(2, 1, 0x1_0000_0000L, 0x20, 0, 0, 0);
        // A subtitle traf is present in the same moof but must not be used as audio.
        byte[] subtitle = traf(3, 0, 1_000L, 0x20, 0, 0, 0);
        // The tfhd default marks the sample non-sync; trun's first_sample_flags
        // explicitly overrides it to sync. Audio is intentionally first in the moof.
        byte[] video = traf(1, 0, 90_000L, 0x20, 0x4, 0, NON_SYNC);
        List<FragmentMoofParser.Fragment> fragments =
                FragmentMoofParser.parse(concat(box("mfhd", ints(0, 1)), audio, subtitle, video), tracks);

        check(fragments != null && fragments.size() == 2,
                "one moof returns both known traf tracks");
        FragmentMoofParser.Fragment audioFragment = fragments.get(0);
        FragmentMoofParser.Fragment videoFragment = fragments.get(1);
        check(audioFragment.trackId == 2 && audioFragment.samples == 1,
                "audio traf is parsed even when it precedes video");
        check(audioFragment.tfdt == 0x1_0000_0000L,
                "version-1 tfdt preserves 64-bit decode time");
        check(videoFragment.trackId == 1 && videoFragment.tfdt == 90_000L,
                "video traf retains its own track id and decode time");
        check(videoFragment.sync,
                "first_sample_flags can override a non-sync tfhd default");
        check(videoFragment.durTicks == 3_000L,
                "track default sample duration is accumulated");
        check(fragments.stream().noneMatch(fragment -> fragment.trackId == 3),
                "subtitle traf is ignored rather than classified as audio");
    }

    private static void testPerSampleFlagsOverrideFirstSampleFlags() throws Exception {
        Map<Integer, FragmentMoofParser.Track> tracks = tracks();
        byte[] video = traf(1, 0, 180_000L, 0x20, 0x404, 0, NON_SYNC);
        List<FragmentMoofParser.Fragment> fragments =
                FragmentMoofParser.parse(video, tracks);
        check(fragments != null && fragments.size() == 1,
                "video traf with per-sample flags parses");
        check(!fragments.get(0).sync,
                "per-sample flags override first_sample_flags for the sample");
    }

    private static void testTruncatedSampleFlagsAreRejected() throws Exception {
        Map<Integer, FragmentMoofParser.Track> tracks = tracks();
        byte[] malformedTrun = box("trun", ints(0x4, 1)); // Missing first_sample_flags word.
        byte[] tfhd = box("tfhd", ints(0, 1));
        byte[] tfdt = box("tfdt", ints(0, 270_000));
        byte[] malformedTraf = box("traf", concat(tfhd, tfdt, malformedTrun));
        check(FragmentMoofParser.parse(malformedTraf, tracks) == null,
                "truncated trun flag table cannot produce an index entry");
    }

    private static void testUnsignedTfdtOverflowIsRejected() throws Exception {
        Map<Integer, FragmentMoofParser.Track> tracks = tracks();
        byte[] tfhd = box("tfhd", ints(0, 1));
        byte[] tfdt = box("tfdt", concat(ints(0x01000000), longs(Long.MIN_VALUE)));
        byte[] trun = box("trun", ints(0, 1));
        byte[] traf = box("traf", concat(tfhd, tfdt, trun));
        check(FragmentMoofParser.parse(traf, tracks) == null,
                "unsigned version-1 tfdt outside signed-long range is rejected");
    }

    private static Map<Integer, FragmentMoofParser.Track> tracks() {
        FragmentMoofParser.Track audio = new FragmentMoofParser.Track();
        audio.id = 2;
        audio.timescale = 48_000;
        audio.video = false;
        audio.audio = true;
        audio.defaultDuration = 1_024;

        FragmentMoofParser.Track subtitle = new FragmentMoofParser.Track();
        subtitle.id = 3;
        subtitle.timescale = 1_000;
        subtitle.video = false;
        subtitle.audio = false;

        FragmentMoofParser.Track video = new FragmentMoofParser.Track();
        video.id = 1;
        video.timescale = 90_000;
        video.video = true;
        video.defaultDuration = 3_000;
        video.defaultFlags = NON_SYNC;

        Map<Integer, FragmentMoofParser.Track> result = new HashMap<>();
        result.put(audio.id, audio);
        result.put(subtitle.id, subtitle);
        result.put(video.id, video);
        return result;
    }

    /** flags in tfhd describe optional fields; trunFlags describe its sample table. */
    private static byte[] traf(int trackId, int tfdtVersion, long decodeTime,
                               int tfhdFlags, int trunFlags,
                               int firstSampleFlags, int defaultFlags) throws Exception {
        ByteArrayOutputStream tfhdBody = new ByteArrayOutputStream();
        DataOutputStream tfhdOut = new DataOutputStream(tfhdBody);
        tfhdOut.writeInt(tfhdFlags);
        tfhdOut.writeInt(trackId);
        if ((tfhdFlags & 0x20) != 0) {
            tfhdOut.writeInt(defaultFlags);
        }

        ByteArrayOutputStream tfdtBody = new ByteArrayOutputStream();
        DataOutputStream tfdtOut = new DataOutputStream(tfdtBody);
        tfdtOut.writeInt(tfdtVersion == 1 ? 0x01000000 : 0);
        if (tfdtVersion == 1) {
            tfdtOut.writeLong(decodeTime);
        } else {
            tfdtOut.writeInt((int) decodeTime);
        }

        ByteArrayOutputStream trunBody = new ByteArrayOutputStream();
        DataOutputStream trunOut = new DataOutputStream(trunBody);
        trunOut.writeInt(trunFlags);
        trunOut.writeInt(1);
        if ((trunFlags & 0x4) != 0) {
            trunOut.writeInt(firstSampleFlags);
        }
        if ((trunFlags & 0x400) != 0) {
            trunOut.writeInt(defaultFlags);
        }

        return box("traf", concat(
                box("tfhd", tfhdBody.toByteArray()),
                box("tfdt", tfdtBody.toByteArray()),
                box("trun", trunBody.toByteArray())));
    }

    private static byte[] box(String type, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(8 + body.length);
        out.writeBytes(type);
        out.write(body);
        return bytes.toByteArray();
    }

    private static byte[] ints(int... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (int value : values) {
            out.writeInt(value);
        }
        return bytes.toByteArray();
    }

    private static byte[] longs(long... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (long value : values) {
            out.writeLong(value);
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] value : values) {
            bytes.write(value);
        }
        return bytes.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
