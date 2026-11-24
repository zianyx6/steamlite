package org.steamlite

import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService
import org.steamlite.protocol.WishProtocol.*

class StoreBrowseService(unified: SteamUnifiedMessages) : UnifiedService(unified) {
    override val serviceName = "StoreBrowse"
    override fun handleNotificationMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) = Unit
    override fun handleResponseMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        if(methodName == "GetItems") postResponseMsg<BrowseResponse.Builder>(BrowseResponse::class.java, packetMsg)
    }
    fun get(ids: List<Int>, country: String) = unifiedMessages!!.sendMessage<BrowseRequest.Builder, BrowseResponse.Builder>(BrowseResponse.Builder::class.java, "StoreBrowse.GetItems#1", BrowseRequest.newBuilder()
        .addAllIds(ids.map { ItemID.newBuilder().setAppid(it).build() })
        .setContext(BrowseContext.newBuilder().setLanguage("schinese").setCountryCode(country))
        .setDataRequest(DataRequest.newBuilder().setIncludeRelease(true).setIncludeBestPurchaseOption(true)).build())
}
