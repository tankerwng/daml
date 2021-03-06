// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.store

import java.time.Instant

import com.digitalasset.api.util.TimestampConversion.fromInstant
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.ledger.ApplicationId
import com.digitalasset.ledger.api.domain.RejectionReason
import com.digitalasset.ledger.api.v1.command_completion_service.{
  Checkpoint,
  CompletionStreamResponse
}
import com.digitalasset.ledger.api.v1.completion.Completion
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.platform.store.dao.LedgerDao
import com.digitalasset.platform.store.entries.LedgerEntry
import com.google.rpc.status.Status
import io.grpc.Status.Code

// Turn a stream of transactions into a stream of completions for a given application and set of parties
// TODO Restrict the scope of this to com.digitalasset.platform.store.dao when
// TODO - the in-memory sandbox is gone
private[platform] object CompletionFromTransaction {

  def toApiCheckpoint(recordTime: Instant, offset: LedgerDao#LedgerOffset): Some[Checkpoint] =
    Some(
      Checkpoint(
        recordTime = Some(fromInstant(recordTime)),
        offset = Some(LedgerOffset(LedgerOffset.Value.Absolute(offset.toString)))))

  // We _rely_ on the following compiler flags for this to be safe:
  // * -Xno-patmat-analysis _MUST NOT_ be enabled
  // * -Xfatal-warnings _MUST_ be enabled
  def toErrorCode(rejection: RejectionReason): Code = {
    rejection match {
      case _: RejectionReason.Inconsistent | _: RejectionReason.Disputed |
          _: RejectionReason.PartyNotKnownOnLedger =>
        Code.INVALID_ARGUMENT
      case _: RejectionReason.OutOfQuota | _: RejectionReason.TimedOut =>
        Code.ABORTED
      case _: RejectionReason.SubmitterCannotActViaParticipant =>
        Code.PERMISSION_DENIED
    }
  }

  // Filter completions for transactions for which we have the full submitter information: appId, submitter, cmdId
  // This doesn't make a difference for the sandbox (because it represents the ledger backend + api server in single package).
  // But for an api server that is part of a distributed ledger network, we might see
  // transactions that originated from some other api server. These transactions don't contain the submitter information,
  // and therefore we don't emit CommandAccepted completions for those
  def apply(appId: ApplicationId, parties: Set[Ref.Party]): PartialFunction[
    (LedgerDao#LedgerOffset, LedgerEntry),
    (LedgerDao#LedgerOffset, CompletionStreamResponse)] = {
    case (
        offset,
        LedgerEntry.Transaction(
          Some(commandId),
          transactionId,
          Some(`appId`),
          Some(submitter),
          _,
          _,
          recordTime,
          _,
          _)) if parties(submitter) =>
      offset -> CompletionStreamResponse(
        checkpoint = toApiCheckpoint(recordTime, offset),
        Seq(Completion(commandId, Some(Status()), transactionId))
      )
    case (offset, LedgerEntry.Rejection(recordTime, commandId, `appId`, submitter, reason))
        if parties(submitter) =>
      offset -> CompletionStreamResponse(
        checkpoint = toApiCheckpoint(recordTime, offset),
        Seq(Completion(commandId, Some(Status(toErrorCode(reason).value(), reason.description))))
      )
    case (offset, LedgerEntry.Checkpoint(recordTime)) =>
      offset -> CompletionStreamResponse(
        checkpoint = toApiCheckpoint(recordTime, offset),
        completions = Seq())
  }

}
