# Native engine registers its methods on this exact class name.
-keep class com.dualboost.app.core.TunnelNative { *; }
-keepclasseswithmembernames class * { native <methods>; }
