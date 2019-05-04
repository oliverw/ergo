package org.ergoplatform.nodeView.wallet.persistence

import java.io.File

import io.iohk.iodb.{ByteArrayWrapper, LSMStore, Store}
import org.ergoplatform.nodeView.wallet.persistence.RegistryOpA.RegistryOp
import org.ergoplatform.settings.{ErgoSettings, WalletSettings}
import org.ergoplatform.wallet.boxes.TrackedBox
import scorex.core.VersionTag
import scorex.crypto.authds.ADKey
import scorex.util.encode.Base16
import scorex.util.{ModifierId, ScorexLogging, idToBytes}

import scala.util.Try

/**
  * Provides an access to version-sensitive wallet-specific indexes.
  * (Such as UTXO's, balances)
  */
final class WalletRegistry(store: Store)(ws: WalletSettings) extends ScorexLogging {

  import RegistryOps._
  import org.ergoplatform.nodeView.wallet.IdUtils._

  private val keepHistory = ws.keepHistory

  def readIndex: RegistryIndex =
    getIndex.transact(store)

  def readCertainBoxes: Seq[TrackedBox] = {
    val query = for {
      allBoxes <- getAllBoxes
      index <- getIndex
    } yield {
      val uncertainIds = index.uncertainBoxes
      allBoxes.filterNot(b => uncertainIds.contains(encodedId(b.box.id)))
    }
    query.transact(store)
  }

  def readUncertainBoxes: Seq[TrackedBox] = {
    val query = for {
      index <- getIndex
      uncertainBoxes <- getBoxes(ADKey @@ index.uncertainBoxes.map(decodedId))
    } yield uncertainBoxes.flatten
    query.transact(store)
  }

  def updateOnBlock(certainBxs: Seq[TrackedBox], uncertainBxs: Seq[TrackedBox],
                    inputs: Seq[(ModifierId, EncodedBoxId)])
                   (blockId: ModifierId, blockHeight: Int): Unit = {
    val update = for {
      _ <- putBoxes(certainBxs ++ uncertainBxs)
      spentBoxesWithTx <- getAllBoxes.map(_.flatMap(bx =>
        inputs.find(_._2 == encodedId(bx.box.id)).map { case (txId, _) => txId -> bx }))
      _ <- updateIndex { case RegistryIndex(_, balance, tokensBalance, _) =>
        val spentBoxes = spentBoxesWithTx.map(_._2)
        val spentAmt = spentBoxes.map(_.box.value).sum
        val spentTokensAmt = spentBoxes
          .flatMap(_.box.additionalTokens)
          .foldLeft(Map.empty[EncodedTokenId, Long]) { case (acc, (id, amt)) =>
            acc.updated(encodedId(id), acc.getOrElse(encodedId(id), 0L) + amt)
          }
        val receivedTokensAmt = certainBxs
          .flatMap(_.box.additionalTokens)
          .foldLeft(Map.empty[EncodedTokenId, Long]) { case (acc, (id, amt)) =>
            acc.updated(encodedId(id), acc.getOrElse(encodedId(id), 0L) + amt)
          }
        val decreasedTokensBalance = spentTokensAmt
          .foldLeft(tokensBalance) { case (acc, (encodedId, amt)) =>
            val decreasedAmt = acc.getOrElse(encodedId, 0L) - amt
            if (decreasedAmt > 0) acc.updated(encodedId, decreasedAmt) else acc - encodedId
          }
        val newTokensBalance = receivedTokensAmt
          .foldLeft(decreasedTokensBalance) { case (acc, (encodedId, amt)) =>
            acc.updated(encodedId, acc.getOrElse(encodedId, 0L) + amt)
          }
        val receivedAmt = certainBxs.map(_.box.value).sum
        val newBalance = balance - spentAmt + receivedAmt
        val uncertain = uncertainBxs.map(x => encodedId(x.box.id))
        RegistryIndex(blockHeight, newBalance, newTokensBalance, uncertain)
      }
      _ <- processHistoricalBoxes(spentBoxesWithTx, blockHeight)
    } yield ()

    update.transact(store, idToBytes(blockId))
  }

  def rollback(version: VersionTag): Try[Unit] =
    Try(store.rollback(ByteArrayWrapper(Base16.decode(version).get)))

  /**
    * Transits used boxes to spent state or simply deletes them depending on settings.
    */
  private def processHistoricalBoxes(spentBoxes: Seq[(ModifierId, TrackedBox)],
                                     spendingHeight: Int): RegistryOp[Unit] = {
    if (keepHistory) {
      updateBoxes(spentBoxes.map(_._2.box.id)) { tb =>
        val spendingTxIdOpt = spentBoxes
          .find { case (_, x) => encodedId(x.box.id) == encodedId(tb.box.id) }
          .map(_._1)
        tb.copy(spendingHeightOpt = Some(spendingHeight), spendingTxIdOpt = spendingTxIdOpt)
      }
    } else {
      removeBoxes(spentBoxes.map(_._2.box.id))
    }
  }

}

object WalletRegistry {

  def readOrCreate(settings: ErgoSettings): WalletRegistry = {
    val dir = new File(s"${settings.directory}/wallet/registry")
    dir.mkdirs()
    new WalletRegistry(new LSMStore(dir))(settings.walletSettings)
  }

}
