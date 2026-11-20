# Full Protobuf and JavaSteam look up message accessors and builders by name.
-keepclassmembers class ** extends com.google.protobuf.GeneratedMessage {
    public *** *(...);
}
-keepclassmembers class ** extends com.google.protobuf.GeneratedMessage$Builder {
    public *** *(...);
}
-keepclassmembers enum ** implements com.google.protobuf.ProtocolMessageEnum {
    public *** *(...);
}

# JavaSteam constructs protocol messages, cached accounts, and RPC services reflectively.
-keepclassmembers class in.dragonbra.javasteam.** implements in.dragonbra.javasteam.base.ISteamSerializable {
    <init>();
}
-keepclassmembers class in.dragonbra.javasteam.steam.handlers.steamfriends.cache.** extends in.dragonbra.javasteam.steam.handlers.steamfriends.cache.Account {
    <init>();
}
-keepclassmembers class ** extends in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService {
    <init>(in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages);
}
-keepclassmembers enum in.dragonbra.javasteam.enums.** {
    <fields>;
    public static *** from(...);
}

# Bouncy Castle builds reflective service names from enclosing class names.
# Keep those names and class identities while allowing unused code to shrink.
-keep,allowshrinking class org.bouncycastle.**

# Keep the registration and service constructors reached through reflection.
-keep,allowoptimization class org.bouncycastle.** extends org.bouncycastle.jcajce.provider.util.AlgorithmProvider {
    public <init>();
}
-keep,allowoptimization class org.bouncycastle.** extends java.security.** {
    public <init>(...);
}
-keep,allowoptimization class org.bouncycastle.** extends javax.crypto.** {
    public <init>(...);
}
# These legacy provider entries use Bouncy Castle's own SPI classes.
-keep,allowoptimization class org.bouncycastle.** extends org.bouncycastle.x509.X509StoreSpi {
    public <init>();
}
-keep,allowoptimization class org.bouncycastle.** extends org.bouncycastle.x509.X509StreamParserSpi {
    public <init>();
}
-keep class org.bouncycastle.jce.provider.BrokenJCEBlockCipher$* { *; }

# Application diagnostics filter JavaSteam logs by these class names.
-keepnames class in.dragonbra.javasteam.steam.CMClient
-keepnames class in.dragonbra.javasteam.networking.steam3.WebSocketConnection
-keepnames class in.dragonbra.javasteam.steam.discovery.SmartCMServerList
-keepnames class in.dragonbra.javasteam.steam.webapi.SteamDirectory
