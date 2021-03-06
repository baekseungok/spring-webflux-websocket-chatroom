/*
 *   Copyright (C) 2017-2018 Ze Hao Xiao
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.github.kvnxiao.spring.webflux.chatroom.handler.websocket.room

import com.github.kvnxiao.spring.webflux.chatroom.handler.websocket.event.WebSocketEvent
import com.github.kvnxiao.spring.webflux.chatroom.model.ChatLobby
import com.github.kvnxiao.spring.webflux.chatroom.model.Session
import com.github.kvnxiao.spring.webflux.chatroom.model.User
import mu.KLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RoomSocketHandler @Autowired constructor(
    private val lobby: ChatLobby
) : WebSocketHandler {

    companion object : KLogging()

    override fun handle(session: WebSocketSession): Mono<Void> {
        val user = session.attributes[Session.USER] as User
        val roomId = session.attributes[Session.ROOM_ID] as String

        // Room should be available or else
        val room = lobby.get(roomId) ?: return errorLog(
            "Tried to handle /room WS connection for user $user, but room $roomId does not exist.")

        // create handler for received payloads to be processed by the event processor
        val receiveSubscriber = RoomSocketSubscriber(lobby, room, user)

        // try up to 3 times with exponential back-off to check if user is NOT in a room already
        return lobby.addUserToRoom(user, room)
            .retryBackoff(3, Duration.ofMillis(500), Duration.ofMillis(3000))
            .onErrorResume {
                // close connection if we failed to add a user to the room, which in other words means
                // the user was still in a room for the whole duration of the checks
                session.close()
                    // cast Mono<Void> as Mono<Boolean> to simply allow the completion signal to propagate
                    .cast(Boolean::class.java)
            }
            // only proceed with websocket connection handling if user is not already in a room
            .flatMap {

                // room-global chat messages
                val finalFlux = room.chatFlux
                    // latency test messages (local)
                    .mergeWith(receiveSubscriber.localEventProcessor.publish().autoConnect(1))
                    .map(WebSocketEvent::toJson)
                    .map(session::textMessage)

                session.receive()
                    .filter { it.type == WebSocketMessage.Type.TEXT }
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnSubscribe { receiveSubscriber.onSubscribe() }
                    .doOnNext(receiveSubscriber::onReceive)
                    .doOnError(receiveSubscriber::onError)
                    .doFinally { receiveSubscriber.onComplete() }
                    .subscribe()

                session.send(finalFlux)
            }
    }

    private fun errorLog(str: String): Mono<Void> {
        logger.error { str }
        return Mono.empty()
    }
}
