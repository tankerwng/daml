// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.apiserver

import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS

import com.digitalasset.resources.{Resource, ResourceOwner}
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultThreadFactory

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

final class EventLoopGroupOwner(threadPoolName: String, parallelism: Int)
    extends ResourceOwner[EventLoopGroup] {
  override def acquire()(implicit executionContext: ExecutionContext): Resource[EventLoopGroup] =
    Resource(
      Future(new NioEventLoopGroup(
        parallelism,
        new DefaultThreadFactory(s"$threadPoolName-grpc-event-loop-${UUID.randomUUID()}", true))))(
      group => {
        val promise = Promise[Unit]()
        val future = group.shutdownGracefully(0, 0, MILLISECONDS)
        future.addListener((f: io.netty.util.concurrent.Future[_]) =>
          promise.complete(Try(f.get).map(_ => ())))
        promise.future
      }
    )
}
