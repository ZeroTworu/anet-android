# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Keep JNI callbacks and custom telemetry views
-keepclassmembers class org.alco.anet.MainActivity {
    public void onConnectivityUpdated(int, int, long, boolean, java.lang.String, java.lang.String, int, int, int, int);
    public void onTelemetryUpdated(long, long, long, long, long, java.lang.String, java.lang.String, java.lang.String, java.lang.String, float[], float[]);
    public void onStatusChanged(java.lang.String);
}
-keepclassmembers class org.alco.anet.ANetVpnService {
    public void onConnectivityUpdated(int, int, long, boolean, java.lang.String, java.lang.String, int, int, int, int);
    public void onTelemetryUpdated(long, long, long, long, long, java.lang.String, java.lang.String, java.lang.String, java.lang.String, float[], float[]);
    public void onStatusChanged(java.lang.String);
    public void onVpnStateChanged(int, java.lang.String, java.lang.String);
    public void onAccountInfo(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String);
    public void onTrafficStats(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}
-keep class org.alco.anet.PulseLineView { *; }
-keep class org.alco.anet.ConnectivitySegmentsView { *; }
-keep class org.alco.anet.TelemetrySparklineView { *; }
