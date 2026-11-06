# Stage 1 has no reflection-based domain serializer. Keep rules stay intentionally minimal.
# App code is small; keep it intact so R8 only shrinks libraries (Compose, Media3, OkHttp, icons).
-keep class pro.noodle.mobile.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
