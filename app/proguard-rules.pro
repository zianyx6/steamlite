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

# JCA resolves Bouncy Castle provider implementations dynamically.
-keep class org.bouncycastle.** { *; }

# Application diagnostics filter JavaSteam logs by these class names.
-keepnames class in.dragonbra.javasteam.steam.CMClient
-keepnames class in.dragonbra.javasteam.networking.steam3.WebSocketConnection
-keepnames class in.dragonbra.javasteam.steam.discovery.SmartCMServerList
-keepnames class in.dragonbra.javasteam.steam.webapi.SteamDirectory
