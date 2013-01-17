package rtkaczyk.eris.service.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.IOException
import rtkaczyk.eris.api.Common
import android.util.Log
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.bluetooth.BtMessages.{Request, Response, Error}
import scala.collection.JavaConversions._
import scala.collection.mutable.Buffer
import scala.math.{min, max}
import scala.annotation.tailrec

class Protocol(socket: BluetoothSocket, full: Boolean) extends Common {

  private val in = socket.getInputStream
  private val out = socket.getOutputStream

  val address = socket.getRemoteDevice.getAddress
  val name = socket.getRemoteDevice.getName
  
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
  def to = if (tr._2 > 631148400000L) tr._2 else tr._2 * 1000
  
  def requestPackets(from: Long = 0, to: Long = 0, 
      limit: Int = 0, batch: Int = 0) {
    
    val request = prepareRequest(from, to, limit, batch).toByteArray
    Log.d(TAG, "Request is %d bytes long" format request.length)
    writeLen(request.length)
    out write request
  }
  
  def getPackets(): List[Packet] = {
    val n = readLen()
    Log.d(TAG, "Response is %d bytes long" format n)
    
    if (n > 0) {
      val response = Response parseFrom readNBytes(n)
      
      if (response.hasError)
        attendError(response.getError)
        
      if (response.hasNoPackets)
        all = response.getNoPackets
        
      if (!full)
        updateTimerange(response.getFr0M, response.getTo)
      
      Log.d(TAG, "Converting response to list")
      val packets = for {
        p <- response.getPacketsList.toList
        data = p.getData
        timestamp = if (p.hasTimestamp) p.getTimestamp else response.getTo 
      } yield {
        if (full)
          updateTimerange(timestamp)
        new Packet(name, address, timestamp, data.toByteArray)
      }
      Log.d(TAG, "Got %d packets" format packets.size)
      packets
    } 
    else {
      Log.d(TAG, "Got 0 packets")
      Nil
    }
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
      builder setFr0M (from / 1000).toInt
    if (to > 0)
      builder setTo (to / 1000).toInt
    if (limit > 0)
      builder setLimit limit
    if (batch > 0)
      builder setBatch batch
    if (!full)
      builder setFull full
      
    builder.build
  }
  
  private def writeLen(n: Int) {
    if (n > (1 << 28) - 1) 
      throw new IllegalArgumentException("Request too long")
    
    var i = 0
    var msb = 0x80
    while (msb != 0) {
      var byte = ((n >> (7 * i)) & 0x7F)
      msb = if (n >> (7 * (i + 1)) == 0) 0 else 0x80
      byte |= msb
      i += 1
      out write byte
    }
  }
  
  private def readLen(): Int = {
    Log.w(TAG, "readLen, available: %d" format in.available)
    var done = false
    var n = 0
    var i = 0
    while (!done) {
      val byte = in.read
      if (byte == -1)
        throw new ConnectionError("Unexpected end of data stream")
      n += (byte & 0x7F) << (7 * i)
      i += 1
      if ((byte & 0x80) == 0) 
        done = true
      if (i > 4)
        throw new InvalidResponse("Response too long")
    }
    n
  }


  private def readNBytes(N: Int) = {
    Log.w(TAG, "readNBytes, available: %d" format in.available)

    val bytes = new Array[Byte](N)
    
    @tailrec
    def read(n: Int): Int = {
      val rd = in.read(bytes, N - n, n)
      if (rd == -1)
        N - n
      else if (rd < n)
        read(n - rd)
      else
        N
    }
    
    val actual = read(N)
    if (actual < N)
      throw new ConnectionError("Unexpected end of data stream: " +
          "actual: %d, expected: %d" format (actual, N))

    Log.w(TAG, "readNBytes finished")
    bytes
  }
}