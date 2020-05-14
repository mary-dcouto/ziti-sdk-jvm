/*
 * Copyright (c) 2018-2020 NetFoundry, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netfoundry.ziti.net

import io.netfoundry.ziti.net.nio.AsyncTLSChannel
import io.netfoundry.ziti.util.Logged
import io.netfoundry.ziti.util.ZitiLog
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import javax.net.ssl.SSLContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal interface Transport : Closeable {

    companion object {
        fun dial(address: String, ssl: SSLContext): Transport {
            val url = URI.create(address)
            return TLS(url.host, url.port, ssl)
        }
    }

    fun isClosed(): Boolean

    suspend fun write(buf: ByteBuffer)
    suspend fun read(buf: ByteBuffer, full: Boolean = true)

    private abstract class ContinuationHandler<V,R>: CompletionHandler<V,Continuation<R>> {
        override fun failed(exc: Throwable, cont: Continuation<R>) = cont.resumeWithException(exc)
    }

    class TLS(host: String, port: Int, sslContext: SSLContext) : Transport, Logged by ZitiLog("ziti-tls") {
        val socket: AsynchronousSocketChannel

        init {
            d { "connecting to $host:$port on t[${Thread.currentThread().name}" }
            socket = AsyncTLSChannel(sslContext)
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port)).get()
        }

        override suspend fun write(buf: ByteBuffer):Unit = suspendCoroutine {
            val h = object : ContinuationHandler<Int,Unit>() {
                override fun completed(result: Int, c: Continuation<Unit>) {
                    t{"wrote ${result} bytes"}
                    if (buf.hasRemaining()) {
                        socket.write(buf, c, this)
                    } else {
                        c.resume(Unit)
                    }
                }
            }

            socket.write(buf, it, h)
        }

        override suspend fun read(buf: ByteBuffer, full: Boolean):Unit = suspendCoroutine {
            val h = object : ContinuationHandler<Int, Unit>() {
                override fun completed(result: Int, c: Continuation<Unit>) {
                    t{"read $result bytes"}
                    if (buf.hasRemaining() && full) {
                        socket.read(buf, c, this)
                    } else {
                        c.resume(Unit)
                    }
                }
            }

            socket.read(buf, it, h)
        }

        override fun close() {
            socket.close()
        }

        override fun isClosed(): Boolean = !socket.isOpen
    }

}
