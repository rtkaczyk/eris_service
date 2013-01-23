package rtkaczyk.eris.service.storage

import android.database.Cursor
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.service.bluetooth.DeviceInfo
import android.util.Log
import rtkaczyk.eris.api.Common

object CursorIterator {
  implicit def cursor2PCI(cur: Cursor): CursorIterator[_] = 
    new CursorIterator[Any] { val cursor = cur }
}

trait CursorIterator[+A] extends Iterator[A] with Common {
  def cursor: Cursor

  private var _hasNext = false
  def hasNext = _hasNext
  
  def init {
    _hasNext = 
        if (cursor == null) {
          Log.w(TAG, "Got null cursor")
          false 
        }
        else cursor.moveToFirst
  }
  
  protected def move {
    cursor.moveToNext
    if (cursor.isAfterLast) {
      _hasNext = false
      cursor.close
    }
  }
  
  def next: A = throw new UnsupportedOperationException
  
  def toPackets = new PacketCursorIterator(cursor)
  def toDevices = new DeviceCursorIterator(cursor)
} 
  
class PacketCursorIterator(val cursor: Cursor) extends CursorIterator[Packet] {
  init
  override def next = {
    val dev = cursor getString (cursor getColumnIndex DbAdapter.DEVICE)
    val time = cursor getLong (cursor getColumnIndex DbAdapter.TIMESTAMP)
    val data = cursor getBlob (cursor getColumnIndex DbAdapter.DATA)
    move
    new Packet(dev, time, data)
  }
}

class DeviceCursorIterator(val cursor: Cursor) extends CursorIterator[(DeviceId, DeviceInfo)] {
  init
  override def next = {
    val dev = cursor getString 0
    val to = cursor getLong 1
    val tau = cursor getLong 2
    move
    (DeviceId(dev), DeviceInfo(None, to, tau))
  }
}

 