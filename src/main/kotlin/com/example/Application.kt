package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.sessions.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Serializable
data class GameSession(val id: String)

@Serializable
data class GameRoom(
    val id: String,
    val sessions: MutableList<GameSession> = mutableListOf(),
    var playersCount: Int = 0,
    var isActive: Boolean = false
)

@Serializable
data class JoinResponse(val message: String, val roomId: String?)

val rooms = ConcurrentHashMap<String, GameRoom>()
val openRooms = mutableListOf<GameRoom>()
val waitingClients = ConcurrentHashMap<String, ConcurrentLinkedQueue<CompletableDeferred<JoinResponse>>>()
const val MAX_PLAYERS = 4
val mutex = Mutex()

class Application {
    companion object {
        @JvmStatic
        fun main() {
            embeddedServer(Netty, port = 8080) {
                install(ContentNegotiation) {
                    json()
                }
                install(Sessions) {
                    cookie<GameSession>("SESSION")
                }
                routing {
                    post("/join") {
                        handleJoin(call, null)
                    }

                    post("/join/{roomId}") {
                        val roomId = call.parameters["roomId"]
                        handleJoin(call, roomId)
                    }

                    post("/reset") {
                        mutex.withLock {
                            rooms.clear()
                            openRooms.clear()
                            waitingClients.clear()
                        }
                        call.respond(HttpStatusCode.OK, "Server state reset")
                    }
                }
            }.start(wait = true)
        }

        private suspend fun handleJoin(call: ApplicationCall, roomId: String?) {
            val currentSession = call.sessions.get<GameSession>() ?: GameSession(id = generateId())
            val responseDeferred = CompletableDeferred<JoinResponse>()
            val roomIdToJoin: String

            mutex.withLock {
                val room = if (roomId == null) {
                    var existingRoom = openRooms.find { it.playersCount < MAX_PLAYERS }
                    if (existingRoom == null) {
                        val newRoomId = generateId()
                        existingRoom = GameRoom(id = newRoomId, sessions = mutableListOf(currentSession), playersCount = 1)
                        rooms[newRoomId] = existingRoom
                        openRooms.add(existingRoom)
                        roomIdToJoin = newRoomId
                        waitingClients[roomIdToJoin] = ConcurrentLinkedQueue<CompletableDeferred<JoinResponse>>().apply {
                            add(responseDeferred)
                        }
                        println("New room created: $newRoomId")
                    } else {
                        roomIdToJoin = existingRoom.id
                        if (existingRoom.sessions.any { session -> session.id == currentSession.id }) {
                            call.respond(HttpStatusCode.OK, JoinResponse("You are already in the game.", roomIdToJoin))
                            println("Player already in the room: $roomIdToJoin")
                            return@handleJoin
                        } else {
                            existingRoom.sessions.add(currentSession)
                            existingRoom.playersCount += 1
                            waitingClients.computeIfAbsent(roomIdToJoin) { ConcurrentLinkedQueue() }
                                .add(responseDeferred)
                            println("Player joined room: $roomIdToJoin")
                            if (existingRoom.playersCount >= MAX_PLAYERS) {
                                existingRoom.isActive = true
                                openRooms.remove(existingRoom)
                                notifyClients(existingRoom.id, "Game room created! The game is starting.")
                                println("Room $roomIdToJoin is full and active.")
                            } else {}
                        }
                    }
                } else {
                    val existingRoom = rooms[roomId]
                    if (existingRoom != null) {
                        if (existingRoom.sessions.any { session -> session.id == currentSession.id }) {
                            call.respond(HttpStatusCode.OK, JoinResponse("You are already in the game.", roomId))
                            return@handleJoin
                        }
                        if (existingRoom.playersCount >= MAX_PLAYERS) {
                            println("Room $roomId is already full. You can't join.")
                            call.respond(HttpStatusCode.BadRequest, "Room is already full. You can't join.")
                            return@handleJoin
                        }
                        existingRoom.sessions.add(currentSession)
                        existingRoom.playersCount += 1
                        roomIdToJoin = roomId
                        println("Player joined room: $roomIdToJoin")
                        waitingClients.computeIfAbsent(roomId) { ConcurrentLinkedQueue() }
                            .add(responseDeferred)
                        if (existingRoom.playersCount >= MAX_PLAYERS) {
                            existingRoom.isActive = true
                            openRooms.remove(existingRoom)
                            notifyClients(existingRoom.id, "Game room created! The game is starting.")
                            println("Room $roomIdToJoin is full and active.")
                        } else {}
                    } else {
                        println("Room not found: $roomId")
                        call.respond(HttpStatusCode.NotFound, "Room not found.")
                        return@handleJoin
                    }
                }
            }

            call.sessions.set(currentSession)

            try {
                val response = responseDeferred.await()
                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Failed to get response")
            }
        }

        private fun generateId(): String {
            return java.util.UUID.randomUUID().toString()
        }

        private suspend fun notifyClients(roomId: String, message: String) {
            val callbacks = waitingClients.remove(roomId) ?: return
            coroutineScope {
                callbacks.forEach { callback ->
                    launch {
                        callback.complete(JoinResponse(message, roomId))
                    }
                }
            }
        }
    }
}
