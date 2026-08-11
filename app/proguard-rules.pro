# sherpa-onnx JNI: native code constructs/calls these by reflection.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# MediaPipe LLM inference: JNI-reflected, and references compile-only AutoValue annotations.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.auto.value.**
# Vision-image API surface unused by Kate (text-only LLM sessions).
-dontwarn com.google.mediapipe.framework.image.**
# tasks-text pulls framework protos not shipped in the AAR.
-dontwarn com.google.mediapipe.proto.**
