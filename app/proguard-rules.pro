# ── ZXing (zxing-android-embedded) ───────────────────────────────────
# CaptureActivity is instantiated reflectively via ScanContract.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# ── Android NSD (mDNS) ───────────────────────────────────────────────
# DiscoveryListener / ResolveListener / ServiceInfoCallback callbacks
# are registered via NsdManager — method names must survive obfuscation.
-keep class * implements android.net.nsd.NsdManager$DiscoveryListener { *; }
-keep class * implements android.net.nsd.NsdManager$ResolveListener { *; }
-keep class * implements android.net.nsd.NsdManager$ServiceInfoCallback { *; }

# ── Data classes that may roundtrip through SharedPreferences ────────
-keep class cz.blikacka.ridepanel.KnownDevices$Entry { *; }
-keep class cz.blikacka.ridepanel.MdnsDiscoverer$Found { *; }

# ── PXC protocol value classes — keep cmd code constants intact ──────
-keep class cz.blikacka.ridepanel.PxcFrame { *; }
-keep class cz.blikacka.ridepanel.MirrorPortsServer$Protocol { *; }

# ── Manifest-registered components (AGP usually handles; explicit) ───
-keep class cz.blikacka.ridepanel.MirrorService
-keep class cz.blikacka.ridepanel.MainActivity

# ── Kotlin metadata (reflection support) ─────────────────────────────
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── Crash-friendly stack traces ──────────────────────────────────────
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
