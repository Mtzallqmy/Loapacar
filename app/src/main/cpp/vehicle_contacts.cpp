#include <jni.h>
#include <algorithm>
#include <cmath>
#include "tire.h"

extern "C" JNIEXPORT void JNICALL
Java_com_masalik_yemen_game_TireSolver_solve(JNIEnv *env, jobject, jfloatArray input, jfloatArray output) {
    // One call per simulation tick for all four tires. Kotlin owns the two reusable arrays.
    float p[20], result[7] = {};
    env->GetFloatArrayRegion(input, 0, 20, p);
    if (env->ExceptionCheck()) return;
    const float mass = p[0], wheelbase = p[1], track = p[2], height = p[3];
    const float speed = p[4], steering = p[5], acceleration = p[6];
    const float drive = p[7], brake = p[8];
    const bool awd = p[13] > .5f, abs = p[14] > .5f, tcs = p[15] > .5f, esc = p[16] > .5f;
    const float total = mass * 9.81f;
    const float frontFraction = std::clamp(p[18] - acceleration * height / (9.81f * wheelbase), .25f, .76f);
    const float lateralAccel = speed * speed * std::tan(steering) / wheelbase;
    const float leftFraction = std::clamp(.5f + lateralAccel * height / (9.81f * track), .12f, .88f);
    for (int wheel = 0; wheel < 4; ++wheel) {
        const bool front = wheel < 2, left = wheel % 2 == 0;
        const float share = (front ? frontFraction : 1.f - frontFraction) *
                            (left ? leftFraction : 1.f - leftFraction);
        const float normal = total * share;
        const float grip = p[9 + wheel];
        const float lateral = std::min(std::fabs(lateralAccel) * mass * share, normal * grip * .97f);
        const float capacity = longitudinal_capacity(normal, grip, lateral);
        const float driveRequest = drive * (awd ? .25f : (front == (p[19] > .5f) ? .5f : 0.f));
        const float brakeRequest = brake * (front ? .31f : .19f);
        const bool spin = std::fabs(driveRequest) > capacity;
        const bool lock = brakeRequest > capacity && std::fabs(speed) > 1.f;
        result[0] += std::copysign(std::min(std::fabs(driveRequest), capacity * (spin && !tcs ? .81f : 1.f)), driveRequest);
        const float braking = std::min(brakeRequest, capacity * (lock && !abs ? .75f : 1.f));
        result[1] += braking;
        result[2] += (left ? 1.f : -1.f) * braking * track * .5f; // signed split-grip yaw moment
        if (lock && abs) result[3] = 1;
        if (spin && tcs) result[4] = 1;
    }
    if (esc && std::fabs(speed) > 5.f && std::fabs(lateralAccel) > 8.f*p[17]) {
        result[5] = 1;
        result[1] += .045f * total;
        result[0] *= .72f;
    }
    result[6] = frontFraction;
    env->SetFloatArrayRegion(output, 0, 7, result);
}
