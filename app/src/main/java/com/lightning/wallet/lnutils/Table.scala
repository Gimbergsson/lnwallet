package com.lightning.wallet.lnutils

import com.lightning.wallet.ln.Tools.{none, runAnd}
import net.sqlcipher.database.SQLiteOpenHelper
import net.sqlcipher.database.SQLiteDatabase
import android.content.Context
import android.net.Uri


object StorageTable extends Table {
  val (table, key, value) = ("storage", "value", "key")
  def updSql = s"UPDATE $table SET $value = ? WHERE $key = ?"
  def selectSql = s"SELECT * FROM $table WHERE $key = ?"
  def killSql = s"DELETE FROM $table WHERE $key = ?"

  def newSql = s"""
    INSERT OR IGNORE INTO $table
    ($key, $value) VALUES (?, ?)"""

  def createSql = s"""
    CREATE TABLE $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $key TEXT NOT NULL UNIQUE,
      $value TEXT NOT NULL
    )"""
}

object ChannelTable extends Table {
  val (table, identifier, data) = ("channel", "identifier", "data")
  def updSql = s"UPDATE $table SET $data = ? WHERE $identifier = ?"
  // Order is important! New channels should always appear on top
  def selectAllSql = s"SELECT * FROM $table ORDER BY $id DESC"
  def killSql = s"DELETE FROM $table WHERE $identifier = ?"

  def newSql = s"""
    INSERT OR IGNORE INTO $table
    ($identifier, $data) VALUES (?, ?)"""

  def createSql = s"""
    CREATE TABLE $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $identifier TEXT NOT NULL UNIQUE,
      $data TEXT NOT NULL
    )"""
}

object PaymentTable extends Table {
  import com.lightning.wallet.ln.PaymentInfo.{HIDDEN, WAITING, SUCCESS, FAILURE}
  val Tuple11(table, hash, preimage, incoming, msat, status, stamp, text, pr, rd, search) =
    ("payment", "hash", "preimage", "incoming", "msat", "status", "stamp", "text", "pr", "rd", "search")

  // Inserting new records for data and fast search
  def newVirtualSql = s"INSERT INTO $fts$table ($search, $hash) VALUES (?, ?)"
  val insert9 = s"$hash, $preimage, $incoming, $msat, $status, $stamp, $text, $pr, $rd"
  def newSql = s"INSERT OR IGNORE INTO $table ($insert9) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

  val limit = 24
  def searchSql = s"SELECT * FROM $table WHERE $hash IN (SELECT DISTINCT $hash FROM $fts$table WHERE $search MATCH ? LIMIT $limit)"
  def selectRecentSql = s"SELECT * FROM $table WHERE $status <> $HIDDEN ORDER BY $id DESC LIMIT $limit"
  def selectSql = s"SELECT * FROM $table WHERE $hash = ?"

  // Updating various parts of data
  def updRoutingSql = s"UPDATE $table SET $rd = ? WHERE $hash = ?"
  def updStatusSql = s"UPDATE $table SET $status = ? WHERE $hash = ?"
  def updFailWaitingSql = s"UPDATE $table SET $status = $FAILURE WHERE $status = $WAITING"
  def updOkOutgoingSql = s"UPDATE $table SET $status = $SUCCESS, $preimage = ? WHERE $hash = ?"
  def updOkIncomingSql = s"UPDATE $table SET $status = $SUCCESS, $msat = ?, $stamp = ? WHERE $hash = ?"
  def createVirtualSql = s"CREATE VIRTUAL TABLE $fts$table USING $fts($search, $hash)"

  def createSql = s"""
    CREATE TABLE $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $hash STRING UNIQUE NOT NULL,
      $preimage STRING NOT NULL,
      $incoming INTEGER NOT NULL,
      $msat INTEGER NOT NULL,
      $status INTEGER NOT NULL,
      $stamp INTEGER NOT NULL,
      $text STRING NOT NULL,
      $pr STRING NOT NULL,
      $rd STRING NOT NULL
    );
    CREATE INDEX idx1 ON $table ($status);
    CREATE INDEX idx2 ON $table ($hash);
    COMMIT"""
}

trait Table { val (id, fts) = "_id" -> "fts4" }
class CipherOpenHelper(context: Context, version: Int, secret: String)
extends SQLiteOpenHelper(context, "lndata4.db", null, version) { me =>

  SQLiteDatabase loadLibs context
  val base = getWritableDatabase(secret)
  def onUpgrade(db: SQLiteDatabase, oldVer: Int, newVer: Int) = none
  def change(sql: String, params: Any*) = base.execSQL(sql, params.map(_.toString).toArray)
  def select(sql: String, params: Any*) = base.rawQuery(sql, params.map(_.toString).toArray)
  def sqlPath(tbl: String) = Uri parse s"sqlite://com.lightning.wallet/table/$tbl"

  def txWrap(process: => Unit) = try {
    runAnd(base.beginTransaction)(process)
    base.setTransactionSuccessful
  } finally base.endTransaction

  def onCreate(dbs: SQLiteDatabase) = {
    dbs execSQL PaymentTable.createVirtualSql
    dbs execSQL PaymentTable.createSql
    dbs execSQL StorageTable.createSql
    dbs execSQL ChannelTable.createSql
  }
}