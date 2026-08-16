# Gradle wrapper integrity — Noodle Mobile alpha7

Pinned distribution: Gradle 9.5.1 (`gradle-9.5.1-bin.zip`).

Expected official SHA-256 values:

- Gradle 9.5.1 binary distribution: `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`
- Gradle 9.5.1 wrapper JAR: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

`gradle-wrapper.properties` pins the distribution checksum. The wrapper JAR is intentionally **not fabricated** in this source delivery: the active build runtime does not contain it. When restoring it from an official Gradle 9.5.1 distribution, verify the wrapper JAR against the checksum above before running `./gradlew`.
