# jcifs-ng (SMB) loads some classes by name and references desktop-only APIs.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
