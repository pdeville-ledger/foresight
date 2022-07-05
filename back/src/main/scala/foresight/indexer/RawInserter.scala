package foresight.indexer

import akka.stream.alpakka.slick.scaladsl.Slick
import akka.stream.alpakka.slick.scaladsl.SlickSession
import akka.stream.scaladsl.Flow
import common.model._
import foresight.model._
import scala.concurrent._
import slick.jdbc.GetResult

//noinspection TypeAnnotation
final case class RawInserter(session: SlickSession) {
  import session.profile.api._

  implicit val ec = session.db.executor.executionContext
  implicit val se = session

  def insertHeaderQuery(raw: Raw.Block) =
    sqlu"""INSERT INTO raw_blocks (height, created_at, data) VALUES (
        ${raw.height.value}, 
        ${raw.createdAt}, 
        ${raw.header.toString()}::jsonb
        ) ON CONFLICT (height) 
        DO 
          UPDATE SET
          created_at = EXCLUDED.created_at,
          data = EXCLUDED.data
          """

  def insertRawTransactionQuery(raw: Raw.PendingTransaction) =
    sqlu"""INSERT INTO raw_transactions(hash, created_at, data) VALUES (
        ${raw.hash},
        ${raw.createdAt},
        ${raw.data.toString()}::jsonb
        ) ON CONFLICT (hash) DO NOTHING"""

  def insertProcessedTransactionQuery(raw: Raw.PendingTransaction) = {
    val processed = Processed.Transaction.fromPending(raw)
    sqlu"""INSERT INTO processed_transactions(
          hash,
          type,
          created_at,
          block_height,
          block_hash,
          sender,
          receiver,
          gas,
          gas_price,
          max_fee_per_gas,
          max_priority_fee_per_gas,
          nonce,
          transaction_index,
          input,
          value
        ) VALUES (
          ${processed.hash},
          ${processed.transactionType.value},
          ${processed.createdAt},
          ${processed.blockHeight.map(_.value)},
          ${processed.blockHash},
          ${processed.sender},
          ${processed.receiver},
          ${processed.gas},
          ${processed.gasPrice},
          ${processed.maxFeePerGas},
          ${processed.maxPriorityFeePerGas},
          ${processed.nonce},
          ${processed.transactionIndex},
          ${processed.input},
          ${processed.value}
        )"""
  }

  def updateMinedTransactionQuery(height: Height)(raw: Raw.MinedTransaction) =
    sqlu"""INSERT INTO raw_transactions(hash, block_height, created_at, mined_at, data) VALUES (
        ${raw.hash},
        ${height.value},
        ${raw.minedAt},
        ${raw.minedAt},
        ${raw.data.toString()}::jsonb
        ) ON CONFLICT (hash)
        DO
          UPDATE SET 
            block_height = ${height.value}, 
            mined_at = ${raw.minedAt.value}
          """

  def updateMinedProcessedTransactionQuery(
      height: Height
  )(raw: Raw.MinedTransaction) = {
    val processed = Processed.Transaction.fromMined(raw)
    sqlu"""UPDATE processed_transactions
            SET block_hash = ${processed.blockHash},
                mined_at = ${processed.minedAt},
                block_height = ${processed.blockHeight.map(_.value)}
            WHERE hash = ${processed.hash}
        """
  }

  def updateDroppedTransactionQuery(raw: Raw.DroppedTransaction) =
    sqlu"""UPDATE raw_transactions
          SET dropped_at = ${raw.droppedAt.value}
          WHERE hash = ${raw.hash}"""

  def insertPendingTransaction(raw: Raw.PendingTransaction) = {
    DBIO
      .fold(
        Seq(
          insertRawTransactionQuery(raw),
          insertProcessedTransactionQuery(raw)
        ),
        0
      )(_ + _)
      .transactionally
  }
  def insertBlockQuery(raw: Raw.Block) = {
    val header = insertHeaderQuery(raw)
    val transactions =
      raw.transactions.map(updateMinedTransactionQuery(raw.height))
    val processedTransactions =
      raw.transactions.map(updateMinedProcessedTransactionQuery(raw.height))
    DBIO
      .fold(Seq(header) ++ transactions ++ processedTransactions, 0)(_ + _)
      .transactionally
  }

  def insertBlock() = Flow[Raw.Block].via(Slick.flow(insertBlockQuery))

  def insertTransaction() =
    Flow[Raw.PendingTransaction].via(Slick.flow(insertPendingTransaction))

}
