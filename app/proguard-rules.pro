# Glance instantiates action callbacks by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
# WorkManager creates workers reflectively.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
