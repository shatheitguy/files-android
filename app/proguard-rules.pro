# jcifs-ng (SMB) loads some classes by name and references desktop-only APIs.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# PDF markup (PdfBox) loads fonts and resources by name.
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.**
