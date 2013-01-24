package rtkaczyk.eris.service.storage

import rtkaczyk.eris.api.Common
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import rtkaczyk.eris.service.ErisService
import android.util.Log
import rtkaczyk.eris.api.Packet
import android.content.ContentValues
import android.database.SQLException
import java.lang.{ Integer, Long => JLong }
import rtkaczyk.eris.service.storage.CursorIterator._
import scala.annotation.tailrec
import android.database.Cursor
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.service.bluetooth.DeviceInfo

object DbAdapter {
  val dbName = "eris.db"
  
  val PACKETS = "packets"
  val ID = "idkey"
  val TIMESTAMP = "timestamp"
  val DEVICE = "device"
  val DATA = "data"
  val FWD = "fwd"
  val CACHE = "cache"
  val TO = "`to`"
  val TAU = "tau"
    
  implicit val err = "Error during db operations"
    
  implicit def packet2CV(p: Packet): ContentValues = {
    val cv = new ContentValues
    cv put (TIMESTAMP, p.time)
    cv put (DEVICE, p.device)
    cv put (DATA, p.data)
    cv put (FWD, Integer valueOf 0)
    cv
  }
  
  implicit def device2CV(dev: (DeviceId, DeviceInfo)): ContentValues = {
    val cv = new ContentValues
    cv put (DEVICE, dev._1.id)
    cv put (TO, JLong valueOf dev._2.to)
    cv put (TAU, JLong valueOf dev._2.tau)
    cv
  }
}

class DbAdapter(service: ErisService) 
extends SQLiteOpenHelper(service, DbAdapter.dbName, null, 1)  
with Common {
  import DbAdapter._

  lazy val storage = service.storage
  implicit lazy val db = getWritableDatabase
  lazy val countStmt = db.compileStatement("SELECT COUNT(*) FROM " + PACKETS)
  
  def onCreate(db: SQLiteDatabase) {
    Log.d(TAG, "onCreate")
    try {
      db.execSQL("CREATE TABLE %s (%s INTEGER PRIMARY KEY AUTOINCREMENT, " +
          "%s TEXT, %s INT8, %s BLOB, %s INT)" format 
        (PACKETS, ID, DEVICE, TIMESTAMP, DATA, FWD))
      db.execSQL("CREATE TABLE %s (%s TEXT, %s INT8, %s INT8)" format 
          (CACHE, DEVICE, TO, TAU))
    } catch {
      case e: Exception =>
        Log.wtf(TAG, "Could initialize database", e)
    }
  }

  def onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
  
  def insertPackets(ps: List[Packet]) = {
    var n = 0
    transaction {
      ps foreach {
        db.insert(PACKETS, null, _)
      }
      n = ps.size
    }
    if (storage.vacEachBatch)
      vaccum
    n
  }
  
  def selectPackets(from: Long, to: Long, device: String, limit: Int): List[Packet] = {
    try {
      val clause = selWhere(from, to, device)
      queryPackets(clause, limit, true).toPackets.toList
    } catch {
      case e: Exception => {
        Log.e(TAG, "Error while selecting packets", e)
        List()
      }
    }
  }
  
  def prepareForwarding(batchSize: Int): (Int, Int) = {
    @tailrec
    def fillFwd(limit: Int, n: Int): Int = {
      val clause = fwdWhere(limit)
      val cv = new ContentValues()
      cv put (FWD, Integer valueOf n)
      
      val rows = db.update(PACKETS, cv, clause, null)
      if (rows > 0)
        fillFwd(limit, n + 1)
      else
        n - 1
    }
    
    var n = 0
    var count = 0
    transaction {
      count = packetsCount
      n = fillFwd(batchSize, 1)
    }
    (n, count)
  }
  
  def selectToForward(n: Int): List[Packet] = {
    try {
      val clause = FWD + " = " + n
      queryPackets(clause).toPackets.toList
    } catch {
      case e: Exception => {
        Log.e(TAG, "Error while selecting packets", e)
        List()
      }
    }
  }
  
  def deleteForwarded(n: Int) {
    if (n > 0)
      transaction {
        val clause = FWD + " > 0  AND " + FWD + " <= " + n
        db.delete(PACKETS, clause, null)
        
        val cv = new ContentValues
        cv put (FWD, Integer valueOf 0)
        db.update(PACKETS, cv, null, null)
      }
  }
  
  def packetsCount: Int = try {
    countStmt.simpleQueryForLong.toInt
  } catch {
    case e: Exception => {
      Log.e(TAG, "Error while counting packets", e)
      0
    }
  }
  
  def loadCache: Map[DeviceId, DeviceInfo] = {
    try {
      val cursor = db.query(CACHE, Array(DEVICE, TO, TAU), 
          null, null, null, null, null)
      cursor.toDevices.toMap
    } catch {
      case e: Exception => {
        Log.e(TAG, "Error while loading cache", e)
        Map()
      }
    }
  }
  
  def persistCache(map: Map[DeviceId, DeviceInfo]) {
    transaction {
      db.delete(CACHE, null, null)
      map foreach {
        db.insert(CACHE, null, _)
      }
    }
  }
  
  def clearCache {
    transaction {
      db.delete(CACHE, null, null)
    }
  }
  
  def deletePackets(from: Long, to: Long, device: String) {
    transaction {
      val clause = selWhere(from, to, device)
      db.delete(PACKETS, clause, null)
    }
  }
  
  
  
  
  private def vaccum {
    val size = (service getDatabasePath dbName).length
    Log.d(TAG, "Size: %d, Capacity: %d" format (size / 1024, storage.capacity / 1024))
    if (size >= storage.capacity) {
      transaction {
        Log.i(TAG, "Vacuuming db. About to delete %.1f%% packets" format storage.vacPercent)
        val count = (packetsCount * storage.vacPercent).toInt
        val sql = db compileStatement vacStmt(count)
        sql.execute
        db.execSQL("VACUUM")
        Log.i(TAG, "Deleted %d packets" format (count - packetsCount))
        Log.d(TAG, "Size: %d, Capacity: %d" format (size / 1024, storage.capacity / 1024))
      }
    }
  }
  
  private def queryPackets(clause: String = "", limit: Int = 0, ordered: Boolean = false): Cursor = {
    val cls = if (clause != "") clause else null
    val lim = if (limit != 0) limit.toString else null
    val ord = if (ordered) TIMESTAMP + " DESC" else null
    
    db.query(
        PACKETS, 
        Array(TIMESTAMP, DEVICE, DATA), 
        cls, null, null, null, ord, lim)
  }
  
  private def fwdWhere(limit: Int) = 
    ID + " IN " +
    "(SELECT " + ID + " FROM " + PACKETS + " WHERE " + FWD + " = 0 " +
    "LIMIT " + limit + ")"
    
  private def vacStmt(n: Int) = 
    "DELETE FROM " + PACKETS + 
    " WHERE " + TIMESTAMP + " IN " +
    "(SELECT " + TIMESTAMP + " FROM " + PACKETS + " ORDER BY " + TIMESTAMP + " ASC LIMIT " + n + ")" 
  
  private def selWhere(from: Long, to: Long, device: String): String = {
    val list = 
      (if (from > 0)     TIMESTAMP + " >= " + from   else "") ::
      (if (to > 0)       TIMESTAMP + " <= " + to     else "") ::
      (if (device != "") DEVICE    + " = "  + device else "") :: Nil
      
    val clause = list filter { "" != }
    
    if (clause.isEmpty) 
      null
    else
      clause mkString (" ", " AND ", " ")
  }
  
  private def transaction(body: => Unit)(implicit db: SQLiteDatabase, err: String) {
    try {
      db.beginTransaction
      body
      db.setTransactionSuccessful
    } catch {
      case e: Exception =>
      Log.e(TAG, err, e)
    } finally {
      db.endTransaction
    }
  }
}