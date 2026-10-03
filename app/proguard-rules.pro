# usb-serial-for-android instantiates drivers reflectively through UsbSerialProber.
-keep class com.hoho.android.usbserial.driver.** { *; }
-keep class com.hoho.android.usbserial.util.** { *; }
-dontwarn com.hoho.android.usbserial.**
