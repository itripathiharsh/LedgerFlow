# Keep Kotlinx Serialization generated serializers & Room entities
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { *** serializer(...); }
-keep,includedescriptorclasses class com.ledgerflow.app.**$$serializer { *; }
-keepclassmembers class com.ledgerflow.app.** { *** Companion; }
-keepclasseswithmembers class com.ledgerflow.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep in-app updater receivers, FileProvider, and persisted state classes across R8 shrinking
-keep class com.ledgerflow.app.update.UpdateInstallStatusReceiver { *; }
-keep class com.ledgerflow.app.update.AppReplacedReceiver { *; }
-keep class com.ledgerflow.app.domain.UpdateManifest { *; }
-keep class com.ledgerflow.app.security.VaultSessionState { *; }
-keep class androidx.core.content.FileProvider { *; }
