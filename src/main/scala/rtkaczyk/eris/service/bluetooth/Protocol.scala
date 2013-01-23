package rtkaczyk.eris.service.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.service.bluetooth.BtMessages.{Request, Response, Error}
import scala.collection.JavaConversions._
import scala.math.{min, max}
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.service.networking.Exceptions._
import rtkaczyk.eris.service.networking.Messaging
import rtkaczyk.eris.service.networking.TOInputStream

class Protocol(socket: BluetoothSocket, full: Boolean, timeout: Long) extends Messaging {

  val in = new TOInputStream(socket.getInputStream, timeout)
  val out = socket.getOutputStream

  val deviceId = DeviceId(socket.getRemoteDevice).id
  
  private var all = 0
  def allPackets = all
  
  private var tr = (Long.MaxValue, 0L)
  private def updateTimerange(from: Long, to: Long): Unit = {
    val f = min(tr._1, from)
    val t = max(tr._2, to)
    tr = (f, t)
  }
  private def updateTimerange(t: Long): Unit = updateTimerange(t, t)
  def from = tr._1
  def to = tr._2
  
  def requestPackets(from: Long = 0, to: Long = 0, 
      limit: Int = 0, batch: Int = 0) {
    
    val request = prepareRequest(from, to, limit, batch).toByteArray
    Log.d(TAG, "Request is %d bytes long" format request.length)
    writeLen(request.length)
    out write request
  }
  
  def getPackets(): List[Packet] = {
    val n = readLen
    Log.d(TAG, "Response is %d bytes long" format n)
    
    if (n > 0) {
      val response = Response parseFrom readNBytes(n)
      
      if (response.hasError)
        attendError(response.getError)
        
      if (response.hasNoPackets)
        all = response.getNoPackets
        
      if (!full)
        updateTimerange(response.getFrm, response.getTo)
      
      for {
        p <- response.getPacketsList.toList
        data = p.getData
        timestamp = if (p.hasTimestamp) p.getTimestamp else response.getTo 
      } yield {
        if (full)
          updateTimerange(timestamp)
        new Packet(deviceId, timestamp, data.toByteArray)
      }
    } 
    else {
      Nil
    }
  }
  
  def confirm: Boolean = {
    writeLen(allPackets)
    readLen > 0
  }
  
  private def attendError(err: Error) {
    err.getCode match {
      case Error.Code.CONNECTION_ERROR =>
        throw new ConnectionError(err.getDescription)
      case Error.Code.INTERNAL_ERROR =>
        throw new InternalError(err.getDescription)
      case Error.Code.INVALID_REQUEST =>
        throw new InvalidRequest(err.getDescription)
      case _ =>
        throw new ConnectionError("Unknown error")
    } 
  }
  
  private def prepareRequest(from: Long, to: Long, limit: Int, batch: Int): Request = {
    val builder = Request.newBuilder()
    if (from > 0) 
      builder setFrm from
    if (to > 0)
      builder setTo to
    if (limit > 0)
      builder setLimit limit
    if (batch > 0)
      builder setBatch batch
    if (!full)
      builder setFull full
      
    builder.build
  }
}