// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.perf

import java.io.File

import com.digitalasset.daml.lf.archive.UniversalArchiveReader
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.ledger.api.domain.LedgerId
import com.digitalasset.ledger.api.testing.utils.{OwnedResource, Resource}
import com.digitalasset.platform.common.LedgerIdMode
import com.digitalasset.platform.sandbox.SandboxServer
import com.digitalasset.platform.sandbox.config.SandboxConfig
import com.digitalasset.platform.sandbox.services.SandboxClientResource
import com.digitalasset.resources.ResourceOwner
import com.digitalasset.testing.postgresql.PostgresResource

import scala.concurrent.ExecutionContext

object LedgerFactories {

  private def getPackageIdOrThrow(file: File): Ref.PackageId =
    UniversalArchiveReader().readFile(file).map(_.all.head._1).get

  private def sandboxConfig(jdbcUrl: Option[String], darFiles: List[File]) =
    SandboxConfig.default.copy(
      port = 0,
      damlPackages = darFiles,
      ledgerIdMode =
        LedgerIdMode.Static(LedgerId(Ref.LedgerString.assertFromString("ledger-server"))),
      jdbcUrl = jdbcUrl,
    )

  val mem = "InMemory"
  val sql = "Postgres"

  def createSandboxResource(store: String, darFiles: List[File])(
      implicit executionContext: ExecutionContext
  ): Resource[LedgerContext] =
    new OwnedResource(
      for {
        jdbcUrl <- store match {
          case `mem` =>
            ResourceOwner.successful(None)
          case `sql` =>
            PostgresResource.owner().map(fixture => Some(fixture.jdbcUrl))
        }
        server <- SandboxServer.owner(sandboxConfig(jdbcUrl, darFiles))
        channel <- SandboxClientResource.owner(server.port)
      } yield new LedgerContext(channel, darFiles.map(getPackageIdOrThrow))
    )
}
