# QuickJS wrapper: JNI looks up these classes, fields and methods by name.
-keep class com.whl.quickjs.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Host functions handed to JS are called through the JSCallFunction interface.
-keep class * implements com.whl.quickjs.wrapper.JSCallFunction { *; }
