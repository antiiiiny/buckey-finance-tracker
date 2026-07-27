# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

-keepclassmembers class * extends com.google.api.client.json.GenericJson {
    *;
}
-keep class com.google.api.services.sheets.v4.** { *; }