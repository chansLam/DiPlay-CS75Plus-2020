# NDK platform must be <= the device API (19): ART refuses to load a .so built for
# android-28 on a KitKat head unit. Match our minSdk so the native libs load on the CS75.
APP_PLATFORM := android-19
APP_STL := none
