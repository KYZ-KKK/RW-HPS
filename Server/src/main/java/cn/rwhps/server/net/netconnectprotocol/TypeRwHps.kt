/*
 * Copyright 2020-2022 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package cn.rwhps.server.net.netconnectprotocol

import cn.rwhps.server.data.global.Data
import cn.rwhps.server.io.GameOutputStream
import cn.rwhps.server.io.packet.GameSavePacket
import cn.rwhps.server.io.packet.Packet
import cn.rwhps.server.net.core.ConnectionAgreement
import cn.rwhps.server.net.core.TypeConnect
import cn.rwhps.server.net.core.server.AbstractNetConnect
import cn.rwhps.server.net.netconnectprotocol.realize.GameVersionServer
import cn.rwhps.server.util.ExtractUtil
import cn.rwhps.server.util.IpUtil
import cn.rwhps.server.util.PacketType
import cn.rwhps.server.util.ReflectionUtils
import cn.rwhps.server.util.Time.concurrentSecond
import cn.rwhps.server.util.log.Log

open class TypeRwHps : TypeConnect {
    val con: GameVersionServer
    var conClass: Class<out GameVersionServer>? = null

    var cache0 = false

    override val abstractNetConnect: AbstractNetConnect
        get() = con

    constructor(con: GameVersionServer) {
        this.con = con
    }
    constructor(con: Class<out GameVersionServer>) {
        // will not be used ; just override the initial value to avoid refusing to compile
        this.con = GameVersionServer(ConnectionAgreement())
        // use for instantiation
        conClass = con
    }

    override fun getTypeConnect(connectionAgreement: ConnectionAgreement): TypeConnect {
        return TypeRwHps(ReflectionUtils.accessibleConstructor(conClass!!, ConnectionAgreement::class.java).newInstance(connectionAgreement))
    }

    @Throws(Exception::class)
    override fun typeConnect(packet: Packet) {
        con.lastReceivedTime()

        if (packet.type != 110 && packet.type != 160 && packet.type != 109) {
            if (cache0) {
                Data.core.admin.bannedIP24.add(IpUtil.ipToLong24(con.ip))
                con.disconnect()
            }
        }

        //Log.debug(packet.type,ExtractUtil.bytesToHex(packet.bytes))
        if (packet.type == PacketType.GAMECOMMAND_RECEIVE.type) {
            con.receiveCommand(packet)
            con.player.lastMoveTime = concurrentSecond()
        } else {
            when (packet.type) {
                PacketType.PREREGISTER_INFO_RECEIVE.type -> {
                    cache0 = true
                    val o = GameOutputStream()
                    o.writeLong(1000L)
                    o.writeByte(0)
                    con.sendPacket(o.createPacket(PacketType.HEART_BEAT))
                    con.registerConnection(packet)
                }
                PacketType.REGISTER_PLAYER.type -> if (!con.getPlayerInfo(packet)) {
                    con.disconnect()
                }
                PacketType.HEART_BEAT_RESPONSE.type -> {
                    cache0 = false
                    val player = con.player
                    player.ping = (System.currentTimeMillis() - player.timeTemp).toInt() shr 1
                }
                PacketType.CHAT_RECEIVE.type -> con.receiveChat(packet)
                PacketType.DISCONNECT.type -> con.disconnect()
                PacketType.ACCEPT_START_GAME.type -> con.player.start = true
                PacketType.SERVER_DEBUG_RECEIVE.type -> con.debug(packet)
                // 竞争 谁先到就用谁
                PacketType.SYNC.type -> if (Data.game.gameSaveCache == null) {
                    val gameSavePacket = GameSavePacket(packet)
                    //gameSavePacket.analyze()
                    //Core.exit()

                    if (gameSavePacket.checkTick()) {
                        Data.game.gameSaveCache = gameSavePacket
                        synchronized(Data.game.gameSaveWaitObject) {
                            Data.game.gameSaveWaitObject.notifyAll()
                        }
                    }
                }

                PacketType.RELAY_118_117_REC.type -> con.sendRelayServerTypeReply(packet)

                0 -> {
                    // 忽略空包
                }

                else -> {
                    Log.warn("[Unknown Package]", """
                        Type : ${packet.type} Length : ${packet.bytes.size}
                        Hex : ${ExtractUtil.bytesToHex(packet.bytes)}
                    """.trimIndent())
                }
            }
        }
    }

    override val version: String
        get() = "${Data.SERVER_CORE_VERSION}: 1.1.0"
}