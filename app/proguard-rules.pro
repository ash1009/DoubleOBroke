# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable

# JavaMail (Gmail and Yahoo over IMAP) finds its parts by name at run time.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-keep class org.apache.harmony.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
-dontwarn org.apache.harmony.**
