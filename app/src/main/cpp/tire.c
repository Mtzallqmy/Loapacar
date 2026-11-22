#include "tire.h"
#include <math.h>

/* Combined tire-force envelope in newtons at a single contact patch. */
float longitudinal_capacity(float normal_load, float friction, float lateral_force) {
    const float limit = fmaxf(0.f, normal_load * friction);
    return sqrtf(fmaxf(0.f, limit * limit - lateral_force * lateral_force));
}
