#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <opus.h>

/*
 * One 20ms/48kHz/mono PCM16LE frame becomes one raw Opus access unit.
 * No RTP or encryption is performed here; the existing CarPlay packetizer owns both.
 * libopus sources are provided at build time from a pinned upstream tag.
 */
enum { FRAME_SAMPLES = 960, FRAME_BYTES = 1920, MAX_PACKET_BYTES = 1275 };

static OpusEncoder *encoder_from_handle(jlong handle) {
    return (OpusEncoder *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_shihab_diplay_media_NativeOpusEncoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint bitrate, jint complexity) {
    (void)env;
    (void)clazz;
    if (bitrate < 6000 || bitrate > 128000 || complexity < 0 || complexity > 10) return 0;

    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (encoder == NULL || error != OPUS_OK) {
        if (encoder != NULL) opus_encoder_destroy(encoder);
        return 0;
    }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_DTX(0)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shihab_diplay_media_NativeOpusEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray pcm) {
    (void)clazz;
    OpusEncoder *encoder = encoder_from_handle(handle);
    if (encoder == NULL || pcm == NULL || (*env)->GetArrayLength(env, pcm) != FRAME_BYTES)
        return NULL;

    jbyte input[FRAME_BYTES];
    opus_int16 samples[FRAME_SAMPLES];
    unsigned char encoded[MAX_PACKET_BYTES];
    (*env)->GetByteArrayRegion(env, pcm, 0, FRAME_BYTES, input);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
        uint16_t lo = (uint8_t)input[i * 2];
        uint16_t hi = (uint8_t)input[i * 2 + 1];
        samples[i] = (opus_int16)(lo | (hi << 8));
    }
    int count = opus_encode(encoder, samples, FRAME_SAMPLES, encoded, MAX_PACKET_BYTES);
    if (count <= 0 || count > MAX_PACKET_BYTES) return NULL;
    jbyteArray packet = (*env)->NewByteArray(env, count);
    if (packet == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, packet, 0, count, (const jbyte *)encoded);
    return packet;
}

JNIEXPORT void JNICALL
Java_com_shihab_diplay_media_NativeOpusEncoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    OpusEncoder *encoder = encoder_from_handle(handle);
    if (encoder != NULL) opus_encoder_destroy(encoder);
}
