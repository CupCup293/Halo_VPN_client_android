# JNI ядра: native-методы и обратный вызов packageOf (его ищет helpers.c по имени).
-keep class app.halo.vpn.core.Core {
    native <methods>;
    public static java.lang.String packageOf(int, java.lang.String, int, java.lang.String, int);
    public static void onLog(int, byte[]);
}

# WorkManager хранит задачи в базе Room: R8 в полном режиме вырезает конструктор её класса — приложение падало при запуске.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
