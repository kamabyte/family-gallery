# --- SMBJ (SMB2/3 client) -----------------------------------------------------
# SMBJ resolves protocol handlers and crypto providers reflectively, so keep its
# classes intact and silence warnings for its optional/soft dependencies.
-keep class com.hierynomus.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**

# Coil registers our custom SMB Keyer/Fetcher against SmbImage through generic component
# signatures. Aggressive R8 optimization can merge/erase those adapters while leaving the UI
# functional but every image request unresolved (only placeholders are drawn). This layer is
# tiny, and keeping it intact is safer than weakening optimization for all of Coil.
-keep class com.familygallery.tv.smb.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod

# --- MBassador event bus (net.engio.mbassy), used internally by SMBJ ----------
# SMBJ dispatches connection/lease events through MBassador. MBassador scans
# annotations, invokes methods and creates dispatchers/filters reflectively.
# Keeping this small library intact also prevents R8 from merging its optional
# javax.el filter into the normal dispatcher, which breaks class verification on
# older Android TV devices where javax.el is intentionally not installed.
-keep class net.engio.mbassy.** { *; }
-keepattributes RuntimeVisibleAnnotations
# MBassador's optional Expression-Language message filter references a Java EE
# EL runtime that SMBJ never activates; the unresolved reference is safe while
# the filter remains isolated in its own class.
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**
