# liblinphone 通过 JNI 按名字回调 Java 层，混淆/裁剪会导致运行时找不到类或方法
-keep class org.linphone.** { *; }
-dontwarn org.linphone.**
