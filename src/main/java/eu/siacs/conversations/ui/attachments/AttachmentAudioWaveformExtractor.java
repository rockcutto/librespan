package eu.siacs.conversations.ui.attachments;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes audio to PCM and reduces the actual signal envelope into a small waveform. */
public final class AttachmentAudioWaveformExtractor {

    public static final int DEFAULT_BUCKETS = 72;

    private static final long CODEC_TIMEOUT_US = 10_000L;

    private AttachmentAudioWaveformExtractor() {}

    public static float[] extract(
            final Context context, final Uri uri, final int bucketCount) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(context, uri, null);
            return extract(extractor, bucketCount);
        } finally {
            if (decoder != null) {
                decoder.release();
            }
            extractor.release();
        }
    }

    public static float[] extract(final String filePath, final int bucketCount)
            throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(filePath);
            return extract(extractor, bucketCount);
        } finally {
            extractor.release();
        }
    }

    private static float[] extract(
            final MediaExtractor extractor, final int requestedBucketCount) throws IOException {
        final int bucketCount = Math.max(16, requestedBucketCount);
        final int trackIndex = findAudioTrack(extractor);
        if (trackIndex < 0) {
            throw new IOException("No audio track found");
        }

        extractor.selectTrack(trackIndex);
        final MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
        final String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        if (mime == null) {
            throw new IOException("Audio track has no MIME type");
        }
        final long durationUs =
                inputFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? Math.max(1L, inputFormat.getLong(MediaFormat.KEY_DURATION))
                        : 1L;

        final MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        try {
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            final double[] bucketEnergy = new double[bucketCount];
            final long[] bucketSamples = new long[bucketCount];
            final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            int sampleRate =
                    inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            : 48_000;
            int channelCount =
                    inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            : 1;

            while (!outputDone) {
                if (!inputDone) {
                    final int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        final ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IOException("Decoder input buffer unavailable");
                        }
                        inputBuffer.clear();
                        final int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    Math.max(0L, extractor.getSampleTime()),
                                    0);
                            extractor.advance();
                        }
                    }
                }

                final int outputIndex =
                        decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat outputFormat = decoder.getOutputFormat();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outputIndex >= 0) {
                    final ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        final ByteBuffer pcm = outputBuffer.duplicate();
                        pcm.position(bufferInfo.offset);
                        pcm.limit(bufferInfo.offset + bufferInfo.size);
                        pcm.order(ByteOrder.nativeOrder());

                        accumulateEnergy(
                                pcm.slice().order(ByteOrder.nativeOrder()),
                                pcmEncoding,
                                Math.max(1, sampleRate),
                                Math.max(1, channelCount),
                                Math.max(0L, bufferInfo.presentationTimeUs),
                                durationUs,
                                bucketEnergy,
                                bucketSamples);
                    }
                    outputDone =
                            (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }

            return normalize(bucketEnergy, bucketSamples);
        } finally {
            try {
                decoder.stop();
            } catch (final Exception ignored) {
            }
            decoder.release();
        }
    }

    private static int findAudioTrack(final MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            final MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static void accumulateEnergy(
            final ByteBuffer pcm,
            final int encoding,
            final int sampleRate,
            final int channelCount,
            final long presentationTimeUs,
            final long durationUs,
            final double[] bucketEnergy,
            final long[] bucketSamples) {
        final int bytesPerSample;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            bytesPerSample = 4;
        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            bytesPerSample = 1;
        } else {
            bytesPerSample = 2;
        }

        final int frameBytes = bytesPerSample * channelCount;
        if (frameBytes <= 0) {
            return;
        }

        // About 600 sampled frames per second is plenty for a 72-column visual waveform and keeps
        // long recordings cheap to analyze.
        final int frameStride = Math.max(1, sampleRate / 600);
        long frameIndex = 0L;

        while (pcm.remaining() >= frameBytes) {
            if (frameIndex % frameStride != 0L) {
                pcm.position(pcm.position() + frameBytes);
                frameIndex++;
                continue;
            }

            double frameEnergy = 0d;
            for (int channel = 0; channel < channelCount; channel++) {
                final float sample;
                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    sample = Math.max(-1f, Math.min(1f, pcm.getFloat()));
                } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                    sample = ((pcm.get() & 0xff) - 128) / 128f;
                } else {
                    sample = pcm.getShort() / 32768f;
                }
                frameEnergy += sample * sample;
            }

            final long sampleTimeUs =
                    presentationTimeUs + (frameIndex * 1_000_000L) / sampleRate;
            final int bucket =
                    Math.min(
                            bucketEnergy.length - 1,
                            (int)
                                    ((Math.min(durationUs - 1L, sampleTimeUs)
                                                    * bucketEnergy.length)
                                            / durationUs));
            bucketEnergy[bucket] += frameEnergy / channelCount;
            bucketSamples[bucket]++;
            frameIndex++;
        }
    }

    private static float[] normalize(
            final double[] bucketEnergy, final long[] bucketSamples) {
        final float[] rms = new float[bucketEnergy.length];
        float maximum = 0f;
        for (int i = 0; i < rms.length; i++) {
            if (bucketSamples[i] == 0L) {
                continue;
            }
            rms[i] =
                    (float)
                            Math.sqrt(
                                    bucketEnergy[i]
                                            / Math.max(1L, bucketSamples[i]));
            maximum = Math.max(maximum, rms[i]);
        }
        if (maximum <= 0f) {
            return rms;
        }
        for (int i = 0; i < rms.length; i++) {
            final float normalized = Math.min(1f, rms[i] / maximum);
            // Mild perceptual compression keeps quiet speech visible without inventing data.
            rms[i] = (float) Math.sqrt(normalized);
        }
        return rms;
    }

}
