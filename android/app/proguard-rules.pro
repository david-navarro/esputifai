# R8 keeps only reachable code; these are what it can't see.
# Readable stack traces matter more than the few KB obfuscation saves.
-dontobfuscate

# NewPipeExtractor runs YouTube's player JavaScript with Rhino, which loads classes by
# reflection, and loads its date patterns by name (same rules as the NewPipe app).
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn javax.lang.model.**
-dontwarn org.jspecify.**

# NewPipeExtractor builds some YouTube requests with protobuf-lite, which reads message fields
# by reflection.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
